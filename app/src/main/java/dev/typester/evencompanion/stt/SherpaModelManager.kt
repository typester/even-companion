package dev.typester.evencompanion.stt

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.typester.evencompanion.core.uniffi.Language
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class SherpaModelPaths(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)

class SherpaModelManager private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences("sherpa_prefs", Context.MODE_PRIVATE)

    private val statusEn: MutableState<ModelStatus> = mutableStateOf(
        if (File(appContext.filesDir, "sherpa/$DIR_EN/.extracted").exists())
            ModelStatus.Ready else ModelStatus.NotDownloaded
    )
    private val extractCancelEn = AtomicBoolean(false)

    init {
        restoreInProgressDownloads()
    }

    fun status(language: Language): State<ModelStatus> = when (language) {
        Language.EN -> statusEn
        Language.JA -> mutableStateOf(ModelStatus.NotDownloaded)
    }

    fun isReady(language: Language): Boolean =
        language == Language.EN && statusEn.value is ModelStatus.Ready

    fun modelPaths(language: Language): SherpaModelPaths? {
        if (!isReady(language)) return null
        val dir = File(appContext.filesDir, "sherpa/$DIR_EN")
        val encoder = dir.listFiles { f -> f.name.startsWith("encoder") && f.name.endsWith(".onnx") }
            ?.firstOrNull() ?: return null
        val decoder = dir.listFiles { f -> f.name.startsWith("decoder") && f.name.endsWith(".onnx") }
            ?.firstOrNull() ?: return null
        val joiner = dir.listFiles { f -> f.name.startsWith("joiner") && f.name.endsWith(".onnx") }
            ?.firstOrNull() ?: return null
        val tokens = File(dir, "tokens.txt").takeIf { it.exists() } ?: return null
        return SherpaModelPaths(
            encoder.absolutePath, decoder.absolutePath,
            joiner.absolutePath, tokens.absolutePath,
        )
    }

    fun download(language: Language) {
        if (language != Language.EN) return
        val current = statusEn.value
        if (current is ModelStatus.Downloading || current is ModelStatus.Ready) return

        val destFile = archiveFile()
        destFile.parentFile?.mkdirs()
        statusEn.value = ModelStatus.Downloading(0)

        val id = ModelDownloader.get(appContext).enqueue(
            url = DOWNLOAD_URL_EN,
            destFile = destFile,
            title = "Sherpa-ONNX English",
            listener = makeListener(destFile),
        )
        prefs.edit().putLong(PREF_DOWNLOAD_ID, id).apply()
    }

    fun cancel(language: Language) {
        if (language != Language.EN) return
        val id = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        if (id > 0) {
            ModelDownloader.get(appContext).cancel(id)
            prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
        }
        extractCancelEn.set(true)
        if (statusEn.value !is ModelStatus.Ready) {
            statusEn.value = ModelStatus.NotDownloaded
        }
    }

    fun delete(language: Language) {
        if (language != Language.EN) return
        cancel(language)
        File(appContext.filesDir, "sherpa/$DIR_EN").deleteRecursively()
        statusEn.value = ModelStatus.NotDownloaded
    }

    private fun makeListener(destFile: File) = object : ModelDownloader.Listener {
        override fun onProgress(percent: Int) { statusEn.value = ModelStatus.Downloading(percent) }
        override fun onSuccess(downloadedFile: File) { extractTarBz2Async(downloadedFile) }
        override fun onFailed(reason: String) {
            statusEn.value = ModelStatus.Failed(reason)
            prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
        }
        override fun onGone() {
            statusEn.value = ModelStatus.NotDownloaded
            prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
        }
    }

    private fun extractTarBz2Async(archiveFile: File) {
        extractCancelEn.set(false)
        statusEn.value = ModelStatus.Extracting
        val cancelled = extractCancelEn

        val thread = Thread {
            val dir = File(appContext.filesDir, "sherpa/$DIR_EN")
            try {
                if (dir.exists()) dir.deleteRecursively()
                dir.mkdirs()
                val canonDir = dir.canonicalPath

                TarArchiveInputStream(
                    BZip2CompressorInputStream(archiveFile.inputStream().buffered())
                ).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        if (cancelled.get()) throw ExtractionCancelled()
                        val rel = entry.name.substringAfter('/')
                        if (rel.isNotEmpty() && !entry.isDirectory) {
                            val out = File(dir, rel)
                            if (!out.canonicalPath.startsWith("$canonDir${File.separator}"))
                                throw SecurityException("tar slip detected: $rel")
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it) }
                        }
                        entry = tar.nextTarEntry
                    }
                }

                archiveFile.delete()
                File(dir, ".extracted").createNewFile()
                prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
                statusEn.value = ModelStatus.Ready
            } catch (_: ExtractionCancelled) {
                archiveFile.delete()
                if (dir.exists() && !File(dir, ".extracted").exists()) dir.deleteRecursively()
                statusEn.value = ModelStatus.NotDownloaded
            } catch (e: Exception) {
                archiveFile.delete()
                if (dir.exists() && !File(dir, ".extracted").exists()) dir.deleteRecursively()
                statusEn.value = ModelStatus.Failed("extract: ${e.message ?: "unknown"}")
                prefs.edit().remove(PREF_DOWNLOAD_ID).apply()
            }
        }.also { it.isDaemon = true }
        thread.start()
    }

    private fun restoreInProgressDownloads() {
        if (statusEn.value is ModelStatus.Ready) return

        val id = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        val destFile = archiveFile()

        if (id > 0) {
            statusEn.value = ModelStatus.Downloading(0)
            ModelDownloader.get(appContext).reattach(id, destFile, makeListener(destFile))
        } else if (destFile.exists()) {
            extractTarBz2Async(destFile)
        }
    }

    private class ExtractionCancelled : Exception()

    private fun archiveFile(): File =
        File((appContext.getExternalFilesDir(null) ?: appContext.cacheDir), "model-downloads/sherpa_en.tar.bz2")

    companion object {
        @Volatile private var instance: SherpaModelManager? = null

        fun get(context: Context): SherpaModelManager =
            instance ?: synchronized(this) {
                instance ?: SherpaModelManager(context.applicationContext).also { instance = it }
            }

        private const val DIR_EN = "sherpa-streaming-zipformer-en"
        private const val PREF_DOWNLOAD_ID = "sherpa_download_id_en"

        // int8 quantized model — ~95 MB encoder vs ~250 MB float32
        private const val DOWNLOAD_URL_EN =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2"
    }
}
