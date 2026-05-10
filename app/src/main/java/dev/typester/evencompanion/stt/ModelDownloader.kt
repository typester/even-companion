package dev.typester.evencompanion.stt

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ModelDownloader private constructor(private val appContext: Context) {

    interface Listener {
        fun onProgress(percent: Int)
        fun onSuccess(downloadedFile: File)
        fun onFailed(reason: String)
        fun onGone()
    }

    private val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val active = ConcurrentHashMap<Long, Pair<File, Listener>>()
    @Volatile private var poller: Thread? = null

    fun enqueue(url: String, destFile: File, title: String, listener: Listener): Long {
        val request = DownloadManager.Request(Uri.parse(url))
            .setDestinationUri(Uri.fromFile(destFile))
            .setTitle(title)
            .setDescription("Downloading model…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        val id = dm.enqueue(request)
        active[id] = Pair(destFile, listener)
        ensurePollerRunning()
        return id
    }

    fun reattach(downloadId: Long, destFile: File, listener: Listener) {
        active[downloadId] = Pair(destFile, listener)
        ensurePollerRunning()
    }

    fun cancel(downloadId: Long) {
        active.remove(downloadId)
        dm.remove(downloadId)
    }

    private fun ensurePollerRunning() {
        synchronized(this) {
            if (poller?.isAlive == true) return
            val t = Thread(::pollLoop).also { it.isDaemon = true; it.name = "ModelDownloader-Poller" }
            poller = t
            t.start()
        }
    }

    private fun pollLoop() {
        while (active.isNotEmpty()) {
            val ids = active.keys.toLongArray()
            val query = DownloadManager.Query().setFilterById(*ids)
            val cursor = dm.query(query)

            val found = mutableSetOf<Long>()
            cursor.use { c ->
                val colId = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val colStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val colBytes = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val colTotal = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val colReason = c.getColumnIndex(DownloadManager.COLUMN_REASON)

                while (c.moveToNext()) {
                    val id = c.getLong(colId)
                    found.add(id)
                    val (destFile, listener) = active[id] ?: continue

                    when (c.getInt(colStatus)) {
                        DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                            val total = c.getLong(colTotal)
                            val bytes = c.getLong(colBytes)
                            val percent = if (total > 0) (bytes * 100 / total).toInt() else 0
                            listener.onProgress(percent)
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            active.remove(id)
                            listener.onSuccess(destFile)
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = reasonString(c.getInt(colReason))
                            active.remove(id)
                            listener.onFailed(reason)
                        }
                        DownloadManager.STATUS_PAUSED -> {
                            // paused but not failed, keep waiting
                        }
                    }
                }
            }

            // IDs no longer in DownloadManager (e.g., user cancelled from notification)
            for (id in ids) {
                if (id !in found) {
                    val (_, listener) = active.remove(id) ?: continue
                    listener.onGone()
                }
            }

            if (active.isNotEmpty()) Thread.sleep(500)
        }
    }

    private fun reasonString(reason: Int): String = when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage not found"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file exists"
        DownloadManager.ERROR_FILE_ERROR -> "file error"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "insufficient space"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "unhandled HTTP code"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "queued for wifi"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "waiting for network"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "waiting to retry"
        else -> "error $reason"
    }

    companion object {
        @Volatile private var instance: ModelDownloader? = null

        fun get(context: Context): ModelDownloader =
            instance ?: synchronized(this) {
                instance ?: ModelDownloader(context.applicationContext).also { instance = it }
            }
    }
}
