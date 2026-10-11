package com.shinydiscoballsdev.kifossk

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Makes a lightweight HTTP HEAD request to the configured target about once a
 * second, but only while MainActivity says that nopage.html is the visible page
 * and the Activity is resumed and focused. Network I/O never runs on the UI thread.
 */
class NoPagePinger(
    private val targetUrlProvider: () -> String,
    private val shouldPing: () -> Boolean,
    private val onSuccessfulPing: (String) -> Unit
) : AutoCloseable {

    companion object {
        private const val TAG = "NoPagePinger"
        private const val INTERVAL_MS = 1_000L
        private const val CONNECT_TIMEOUT_MS = 700
        private const val READ_TIMEOUT_MS = 700
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kiTavern-url-pinger").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)
    private val generation = AtomicInteger(0)

    @Volatile
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            if (!shouldPing()) {
                stop()
                return
            }

            val runId = generation.get()
            if (inFlight.compareAndSet(false, true)) {
                val target = targetUrlProvider()
                try {
                    executor.execute {
                        try {
                            performPing(target, runId)
                        } finally {
                            inFlight.set(false)
                        }
                    }
                } catch (e: RuntimeException) {
                    inFlight.set(false)
                    Log.w(TAG, "Could not schedule URL ping", e)
                }
            }

            if (running && generation.get() == runId) {
                mainHandler.postDelayed(this, INTERVAL_MS)
            }
        }
    }

    fun start() {
        if (running || !shouldPing()) return
        generation.incrementAndGet()
        running = true
        mainHandler.removeCallbacks(ticker)
        mainHandler.post(ticker)
    }

    fun stop() {
        running = false
        generation.incrementAndGet()
        mainHandler.removeCallbacks(ticker)
        // Do not disconnect a network socket on the UI thread: some Android
        // implementations can block in disconnect(). The in-flight request has
        // short timeouts and finally closes its own connection; generation
        // invalidation prevents any callback from an old request.
    }

    private fun performPing(target: String, runId: Int) {
        var connection: HttpURLConnection? = null
        try {
            if (!isCurrentRun(runId)) return
            val parsedUrl = URL(target)
            val headConnection = parsedUrl.openConnection() as? HttpURLConnection ?: return
            connection = headConnection
            if (!isCurrentRun(runId)) return

            headConnection.instanceFollowRedirects = true
            headConnection.requestMethod = "HEAD"
            headConnection.connectTimeout = CONNECT_TIMEOUT_MS
            headConnection.readTimeout = READ_TIMEOUT_MS
            headConnection.useCaches = false
            headConnection.setRequestProperty("Cache-Control", "no-cache, no-store")
            headConnection.setRequestProperty("Pragma", "no-cache")
            val status = headConnection.responseCode
            var finalStatus = status

            // Some servers do not implement HEAD. Fall back to a tiny GET in that case.
            if ((status == HttpURLConnection.HTTP_BAD_METHOD || status == HttpURLConnection.HTTP_NOT_IMPLEMENTED) &&
                isCurrentRun(runId)
            ) {
                headConnection.disconnect()
                val getConnection = parsedUrl.openConnection() as? HttpURLConnection ?: return
                connection = getConnection
                if (!isCurrentRun(runId)) return
                getConnection.requestMethod = "GET"
                getConnection.connectTimeout = CONNECT_TIMEOUT_MS
                getConnection.readTimeout = READ_TIMEOUT_MS
                getConnection.useCaches = false
                getConnection.setRequestProperty("Range", "bytes=0-0")
                getConnection.setRequestProperty("Cache-Control", "no-cache, no-store")
                finalStatus = getConnection.responseCode
                // Don't download the entire fallback GET body; we only need its status.
            }

            // A responding HTTP error page (401/404/500) is NOT recovery.
            if (finalStatus in 200..399 && isCurrentRun(runId)) {
                mainHandler.post {
                    // Network callbacks can arrive after focus, URL or Activity changes.
                    if (isCurrentRun(runId) && shouldPing()) {
                        onSuccessfulPing(target)
                    }
                }
            }
        } catch (e: Exception) {
            // Failure is expected while the target is offline; do not crash the app.
            Log.d(TAG, "Target ping failed: ${e.message}")
        } finally {
            if (connection != null) {
                connection.disconnect()
            }
        }
    }

    private fun isCurrentRun(runId: Int): Boolean =
        running && generation.get() == runId

    override fun close() {
        stop()
        executor.shutdownNow()
    }
}
