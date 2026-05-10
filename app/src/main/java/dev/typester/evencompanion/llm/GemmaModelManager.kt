package dev.typester.evencompanion.llm

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.typester.evencompanion.stt.ModelDownloader
import dev.typester.evencompanion.stt.ModelStatus
import java.io.File

class GemmaModelManager private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences("gemma_prefs", Context.MODE_PRIVATE)
    private val statusState: MutableState<ModelStatus> = mutableStateOf(
        if (modelFile().exists()) ModelStatus.Ready else ModelStatus.NotDownloaded
    )

    init {
        restoreInProgressDownload()
    }

    fun status(): State<ModelStatus> = statusState

    fun isReady(): Boolean = statusState.value is ModelStatus.Ready

    fun modelPath(): String? = if (isReady()) modelFile().absolutePath else null

    fun download() {
        val current = statusState.value
        if (current is ModelStatus.Downloading || current is ModelStatus.Ready) return

        val staged = stagedFile()
        staged.parentFile?.mkdirs()
        statusState.value = ModelStatus.Downloading(0)

        val id = ModelDownloader.get(appContext).enqueue(
            url = MODEL_URL,
            destFile = staged,
            title = "Gemma 3 1B",
            listener = makeListener(staged),
        )
        prefs.edit().putLong(PREF_DOWNLOAD_ID, id).apply()
    }

    fun cancel() {
        val id = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (id > 0) {
            ModelDownloader.get(appContext).cancel(id)
            prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
        }
        stagedFile().delete()
        if (statusState.value !is ModelStatus.Ready) {
            statusState.value = ModelStatus.NotDownloaded
        }
    }

    fun delete() {
        cancel()
        modelFile().delete()
        statusState.value = ModelStatus.NotDownloaded
    }

    private fun makeListener(staged: File) = object : ModelDownloader.Listener {
        override fun onProgress(percent: Int) { statusState.value = ModelStatus.Downloading(percent) }
        override fun onSuccess(downloadedFile: File) { moveToFinal(downloadedFile) }
        override fun onFailed(reason: String) {
            statusState.value = ModelStatus.Failed(reason)
            prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
        }
        override fun onGone() {
            statusState.value = ModelStatus.NotDownloaded
            prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
        }
    }

    private fun moveToFinal(staged: File) {
        val dest = modelFile()
        dest.parentFile?.mkdirs()
        try {
            if (!staged.renameTo(dest)) {
                staged.copyTo(dest, overwrite = true)
                staged.delete()
            }
            prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
            statusState.value = ModelStatus.Ready
        } catch (e: Exception) {
            staged.delete()
            dest.delete()
            statusState.value = ModelStatus.Failed("move: ${e.message ?: "unknown"}")
            prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
        }
    }

    private fun restoreInProgressDownload() {
        if (statusState.value is ModelStatus.Ready) return
        val id = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        val staged = stagedFile()
        when {
            id > 0 -> {
                statusState.value = ModelStatus.Downloading(0)
                ModelDownloader.get(appContext).reattach(id, staged, makeListener(staged))
            }
            staged.exists() -> moveToFinal(staged)
        }
    }

    private fun modelFile() = File(appContext.filesDir, "gemma/$MODEL_FILENAME")

    private fun stagedFile() = File(
        (appContext.getExternalFilesDir(null) ?: appContext.cacheDir),
        "model-downloads/$MODEL_FILENAME"
    )

    companion object {
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        private const val MODEL_FILENAME = "gemma-4-e2b-it.litertlm"
        private const val PREF_DOWNLOAD_ID = "gemma_download_id"

        @Volatile private var instance: GemmaModelManager? = null

        fun get(context: Context): GemmaModelManager =
            instance ?: synchronized(this) {
                instance ?: GemmaModelManager(context.applicationContext).also { instance = it }
            }
    }
}
