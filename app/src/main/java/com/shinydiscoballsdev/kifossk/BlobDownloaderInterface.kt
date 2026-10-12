package com.shinydiscoballsdev.kifossk

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Small, sequential, chunked bridge for WebView Blob downloads. It avoids
 * holding a complete downloaded Blob in an Android byte array/Base64 string.
 * JS calls are made on a private WebView thread; all mutable state is guarded.
 */
class BlobDownloaderInterface(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val transfers = HashMap<String, Transfer>()

    private data class Transfer(
        val stream: OutputStream,
        val filename: String,
        val mimeType: String,
        val mediaUri: Uri?,
        val file: File?,
        var lastActivityMs: Long = System.currentTimeMillis()
    )

    companion object {
        private const val TAG = "BlobDownload"
        private const val MAX_TRANSFERS = 3
        private const val MAX_ENCODED_CHUNK_LENGTH = 512 * 1024
        private const val STALE_TRANSFER_MS = 15 * 60 * 1000L
    }

    /** Begin a download. Return an opaque token, or an empty string on failure. */
    @JavascriptInterface
    @Synchronized
    fun beginDownload(mimeType: String, filename: String): String {
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
            transfer.lastActivityMs = System.currentTimeMillis()
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
    }

    /** Called when the owning WebView is permanently destroyed, not on rotation. */
    @Synchronized
    fun cancelAllDownloads() {
        transfers.keys.toList().forEach(::cancelDownload)
    }

    // Support data: URLs without allocating a second full encoded or decoded copy.
    @JavascriptInterface
    fun downloadBase64(base64Data: String, mimeType: String, filename: String) {
        val token = beginDownload(mimeType, filename)
        if (token.isEmpty()) return
        try {
            // 256 KiB is divisible by four, so individual Base64 blocks decode safely.
            var index = 0
            while (index < base64Data.length) {
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

    fun downloadText(text: String, mimeType: String, filename: String) {
        val token = beginDownload(mimeType, filename)
        if (token.isEmpty()) return
        try {
            // On this path data: payloads are usually tiny strings; don't round-trip
            // them through Base64 just to pass them back into Kotlin.
            val bytes = text.toByteArray(Charsets.UTF_8)
            val transfer = synchronized(this) { transfers[token] } ?: return
            synchronized(this) { transfer.stream.write(bytes) }
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

    private fun cleanStaleTransfers() {
        val now = System.currentTimeMillis()
        val stale = transfers.filterValues { now - it.lastActivityMs > STALE_TRANSFER_MS }.keys.toList()
        stale.forEach { cancelDownload(it) }
    }

    private fun showToast(message: String) {
        mainHandler.post { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
    }
}
