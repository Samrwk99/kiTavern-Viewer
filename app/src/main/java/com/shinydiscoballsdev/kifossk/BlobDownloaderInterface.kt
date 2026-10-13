package com.shinydiscoballsdev.kifossk

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Chunked download bridge. A per-page capability prevents unrelated iframes
 * from starting downloads through Android's frame-global JavaScript interface.
 * Chunk tokens are individually unguessable. Files are written sequentially,
 * retaining the same paths and MediaStore behavior as the previous version.
 */
class BlobDownloaderInterface(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val transfers = HashMap<String, Transfer>()
    private var currentPageCapability: String? = null
    private var sweepScheduled = false
    private var generation = 0

    // Data URLs arrive from WebView callbacks on the UI thread. Bounded, lazy
    // worker threads avoid blocking input and bound queued encoded payloads.
    private val dataUrlWorker by lazy {
        ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(4),
            { job -> Thread(job, "KiTavernDataDownloads").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    private data class Transfer(
        val stream: OutputStream,
        val filename: String,
        val mimeType: String,
        val mediaUri: Uri?,
        val file: File?,
        var lastActivityMs: Long = SystemClock.elapsedRealtime()
    )

    companion object {
        private const val TAG = "BlobDownload"
        private const val MAX_TRANSFERS = 3
        private const val MAX_ENCODED_CHUNK_LENGTH = 512 * 1024
        private const val STALE_TRANSFER_MS = 15 * 60 * 1000L
        private const val SWEEP_INTERVAL_MS = 60 * 1000L
    }

    /** Called only on the UI thread while installing a top-level document script. */
    @Synchronized
    fun newPageCapability(): String = UUID.randomUUID().toString().also {
        currentPageCapability = it
    }

    @Synchronized
    fun invalidatePageCapability() {
        currentPageCapability = null
    }

    /**
     * Android injects @JavascriptInterface into every frame, but only the
     * top-level document receives the capability in its private JS closure.
     */
    @JavascriptInterface
    @Synchronized
    fun beginDownload(pageCapability: String, mimeType: String, filename: String): String {
        if (pageCapability != currentPageCapability || currentPageCapability == null) {
            Log.w(TAG, "Rejected Blob download without main-page authorization")
            return ""
        }
        return beginDownloadNative(mimeType, filename)
    }

    @Synchronized
    private fun beginDownloadNative(mimeType: String, filename: String): String {
        cleanStaleTransfers()
        if (transfers.size >= MAX_TRANSFERS) {
            Log.w(TAG, "Too many simultaneous Blob transfers")
            return ""
        }

        val safeFilename = File(filename).name
            .replace(Regex("[\\x00-\\x1f\\x7f]"), "_")
            .take(160)
            .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "download"
        val safeMime = mimeType.take(128).ifBlank { "application/octet-stream" }

        var mediaUri: Uri? = null
        var file: File? = null
        return try {
            val stream: OutputStream
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeFilename)
                    put(MediaStore.Downloads.MIME_TYPE, safeMime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                mediaUri = appContext.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: error("Cannot create Downloads item")
                stream = appContext.contentResolver.openOutputStream(mediaUri!!)
                    ?: error("Cannot open Downloads item")
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                    error("Cannot create Downloads directory")
                }
                file = File(downloadsDir, safeFilename)
                stream = FileOutputStream(file)
            }
            val token = UUID.randomUUID().toString()
            transfers[token] = Transfer(stream, safeFilename, safeMime, mediaUri, file)
            scheduleStaleSweep()
            token
        } catch (e: Exception) {
            Log.e(TAG, "Unable to begin Blob download", e)
            mediaUri?.let { runCatching { appContext.contentResolver.delete(it, null, null) } }
            file?.let { runCatching { it.delete() } }
            showToast("Unable to start download")
            ""
        }
    }

    @JavascriptInterface
    @Synchronized
    fun appendChunk(token: String, base64Chunk: String): Boolean {
        val transfer = transfers[token] ?: return false
        if (base64Chunk.length > MAX_ENCODED_CHUNK_LENGTH) {
            Log.w(TAG, "Oversized download chunk rejected")
            cancelDownload(token)
            return false
        }
        return try {
            transfer.stream.write(Base64.decode(base64Chunk, Base64.DEFAULT))
            transfer.lastActivityMs = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Blob chunk write failed", e)
            cancelDownload(token)
            false
        }
    }

    @JavascriptInterface
    @Synchronized
    fun finishDownload(token: String) {
        val transfer = transfers.remove(token) ?: return
        rescheduleOrStopStaleSweep()
        try {
            transfer.stream.close()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && transfer.mediaUri != null) {
                val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                appContext.contentResolver.update(transfer.mediaUri, values, null, null)
            } else if (transfer.file != null) {
                MediaScannerConnection.scanFile(
                    appContext,
                    arrayOf(transfer.file.absolutePath),
                    arrayOf(transfer.mimeType),
                    null
                )
            }
            showToast("Saved: ${transfer.filename}")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to finalize Blob download", e)
            removeIncomplete(transfer)
            showToast("Download failed")
        }
    }

    @JavascriptInterface
    @Synchronized
    fun cancelDownload(token: String) {
        transfers.remove(token)?.let(::removeIncomplete)
        rescheduleOrStopStaleSweep()
    }

    /** Stop transfers from a dead renderer; doesn't shut down future downloads. */
    @Synchronized
    fun cancelAllDownloads() {
        generation++
        transfers.keys.toList().forEach(::cancelDownload)
    }

    /** Called only when the owner WebView is permanently destroyed. */
    fun close() {
        invalidatePageCapability()
        cancelAllDownloads()
        if (dataUrlWorkerInitialized()) dataUrlWorker.shutdownNow()
    }

    // Do not instantiate the thread pool just to shut down an unused bridge.
    private var workerUsed = false
    private fun dataUrlWorkerInitialized(): Boolean = workerUsed

    fun downloadDataUrlAsync(payload: String, mimeType: String, filename: String, isBase64: Boolean) {
        val expectedGeneration = synchronized(this) { generation }
        try {
            workerUsed = true
            dataUrlWorker.execute {
                if (synchronized(this) { generation != expectedGeneration }) return@execute
                try {
                    if (isBase64) {
                        downloadBase64(payload, mimeType, filename, expectedGeneration)
                    } else {
                        downloadText(Uri.decode(payload), mimeType, filename, expectedGeneration)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to process data URL", e)
                    showToast("Download failed")
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Data URL download worker is busy", e)
            showToast("Unable to start download")
        }
    }

    private fun downloadBase64(
        base64Data: String, mimeType: String, filename: String, expectedGeneration: Int
    ) {
        val token = beginDownloadNative(mimeType, filename)
        if (token.isEmpty()) return
        try {
            // 256 KiB is divisible by four, so individual Base64 blocks decode safely.
            var index = 0
            while (index < base64Data.length) {
                if (synchronized(this) { generation != expectedGeneration }) {
                    cancelDownload(token)
                    return
                }
                val end = (index + 256 * 1024).coerceAtMost(base64Data.length)
                if (!appendChunk(token, base64Data.substring(index, end))) return
                index = end
            }
            finishDownload(token)
        } catch (e: Exception) {
            cancelDownload(token)
            Log.e(TAG, "Unable to decode data URL", e)
        }
    }

    private fun downloadText(
        text: String, mimeType: String, filename: String, expectedGeneration: Int
    ) {
        if (synchronized(this) { generation != expectedGeneration }) return
        val token = beginDownloadNative(mimeType, filename)
        if (token.isEmpty()) return
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            synchronized(this) {
                if (generation != expectedGeneration) {
                    cancelDownload(token)
                    return
                }
                val transfer = transfers[token] ?: return
                transfer.stream.write(bytes)
                transfer.lastActivityMs = SystemClock.elapsedRealtime()
            }
            finishDownload(token)
        } catch (e: Exception) {
            cancelDownload(token)
            Log.e(TAG, "Unable to write data URL", e)
        }
    }

    private fun removeIncomplete(transfer: Transfer) {
        runCatching { transfer.stream.close() }
        transfer.mediaUri?.let { runCatching { appContext.contentResolver.delete(it, null, null) } }
        transfer.file?.let { runCatching { it.delete() } }
    }

    private val staleSweep = object : Runnable {
        override fun run() {
            synchronized(this@BlobDownloaderInterface) {
                sweepScheduled = false
                cleanStaleTransfers()
                scheduleStaleSweep()
            }
        }
    }

    @Synchronized
    private fun scheduleStaleSweep() {
        if (transfers.isNotEmpty() && !sweepScheduled) {
            sweepScheduled = true
            mainHandler.postDelayed(staleSweep, SWEEP_INTERVAL_MS)
        }
    }

    @Synchronized
    private fun rescheduleOrStopStaleSweep() {
        if (transfers.isEmpty() && sweepScheduled) {
            mainHandler.removeCallbacks(staleSweep)
            sweepScheduled = false
        }
    }

    @Synchronized
    private fun cleanStaleTransfers() {
        val now = SystemClock.elapsedRealtime()
        val stale = transfers.filterValues { now - it.lastActivityMs > STALE_TRANSFER_MS }
            .keys.toList()
        stale.forEach { token ->
            Log.w(TAG, "Cancelling inactive Blob transfer")
            cancelDownload(token)
        }
    }

    private fun showToast(message: String) {
        mainHandler.post { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
    }
}
