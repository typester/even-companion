package dev.typester.evencompanion.stt

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.typester.evencompanion.core.uniffi.Language
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class SherpaModelPaths(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)

class SherpaModelManager private constructor(private val appContext: Context) {

    private val statusEn: MutableState<ModelStatus> = mutableStateOf(
        if (File(appContext.filesDir, "sherpa/$DIR_EN/.extracted").exists())
            ModelStatus.Ready else ModelStatus.NotDownloaded
    )
    private val workerEn = AtomicReference<Thread?>(null)
    private val cancelFlagEn = AtomicBoolean(false)

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
        cancelFlagEn.set(false)
        statusEn.value = ModelStatus.Downloading(0)
        val thread = Thread { doDownload() }.also { it.isDaemon = true }
        workerEn.set(thread)
        thread.start()
    }

    fun cancel(language: Language) {
        if (language == Language.EN) cancelFlagEn.set(true)
    }

    fun delete(language: Language) {
        if (language != Language.EN) return
        cancel(language)
        File(appContext.filesDir, "sherpa/$DIR_EN").deleteRecursively()
        statusEn.value = ModelStatus.NotDownloaded
    }

    private fun doDownload() {
        val cancelled = cancelFlagEn
        val dir = File(appContext.filesDir, "sherpa/$DIR_EN")
        val stagingDir = File(appContext.cacheDir, "sherpa-downloads").also { it.mkdirs() }
        val partFile = File(stagingDir, "en.tar.bz2.part")
        val archiveFile = File(stagingDir, "en.tar.bz2")

        partFile.delete()
        archiveFile.delete()

        try {
            if (appContext.filesDir.freeSpace < 500L * 1024 * 1024) {
                statusEn.value = ModelStatus.Failed("not enough space")
                return
            }

            val conn = URL(DOWNLOAD_URL_EN).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()
            val total = conn.contentLengthLong
            var downloaded = 0L
            var lastUpdateMs = 0L

            conn.inputStream.use { input ->
                partFile.outputStream().use { out ->
                    val buf = ByteArray(65_536)
                    while (true) {
                        if (cancelled.get()) throw DownloadCancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastUpdateMs > 200) {
                            statusEn.value = ModelStatus.Downloading((downloaded * 100 / total).toInt())
                            lastUpdateMs = now
                        }
                    }
                }
            }

            if (cancelled.get()) throw DownloadCancelled()
            partFile.renameTo(archiveFile)

            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            val canonDir = dir.canonicalPath

            TarArchiveInputStream(
                BZip2CompressorInputStream(archiveFile.inputStream().buffered())
            ).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    if (cancelled.get()) throw DownloadCancelled()
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
            statusEn.value = ModelStatus.Ready
        } catch (_: DownloadCancelled) {
            partFile.delete()
            archiveFile.delete()
            statusEn.value = ModelStatus.NotDownloaded
        } catch (e: Exception) {
            partFile.delete()
            archiveFile.delete()
            if (dir.exists() && !File(dir, ".extracted").exists()) dir.deleteRecursively()
            statusEn.value = if (cancelled.get()) ModelStatus.NotDownloaded
                             else ModelStatus.Failed(e.message ?: "download failed")
        } finally {
            workerEn.set(null)
        }
    }

    private class DownloadCancelled : Exception()

    companion object {
        @Volatile private var instance: SherpaModelManager? = null

        fun get(context: Context): SherpaModelManager =
            instance ?: synchronized(this) {
                instance ?: SherpaModelManager(context.applicationContext).also { instance = it }
            }

        private const val DIR_EN = "sherpa-streaming-zipformer-en"

        // int8 quantized model — ~95 MB encoder vs ~250 MB float32
        private const val DOWNLOAD_URL_EN =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2"
    }
}
