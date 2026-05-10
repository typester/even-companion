package dev.typester.evencompanion.stt

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.typester.evencompanion.core.uniffi.Language
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

sealed class ModelStatus {
    object NotDownloaded : ModelStatus()
    data class Downloading(val percent: Int) : ModelStatus()
    object Ready : ModelStatus()
    data class Failed(val reason: String) : ModelStatus()
}

class VoskModelManager private constructor(private val appContext: Context) {

    private val statuses = EnumMap<Language, MutableState<ModelStatus>>(Language::class.java)
    private val workers = EnumMap<Language, AtomicReference<Thread?>>(Language::class.java)
    private val cancelFlags = EnumMap<Language, AtomicBoolean>(Language::class.java)

    init {
        for (lang in Language.values()) {
            val stamp = File(appContext.filesDir, "vosk/${stableDir(lang)}/.extracted")
            statuses[lang] = mutableStateOf(if (stamp.exists()) ModelStatus.Ready else ModelStatus.NotDownloaded)
            workers[lang] = AtomicReference(null)
            cancelFlags[lang] = AtomicBoolean(false)
        }
    }

    fun status(language: Language): State<ModelStatus> = statuses[language]!!

    fun isReady(language: Language): Boolean = statuses[language]!!.value is ModelStatus.Ready

    fun modelPath(language: Language): String? =
        if (isReady(language)) File(appContext.filesDir, "vosk/${stableDir(language)}").absolutePath else null

    fun download(language: Language) {
        val current = statuses[language]!!.value
        if (current is ModelStatus.Downloading || current is ModelStatus.Ready) return
        cancelFlags[language]!!.set(false)
        statuses[language]!!.value = ModelStatus.Downloading(0)
        val thread = Thread { doDownload(language) }.also { it.isDaemon = true }
        workers[language]!!.set(thread)
        thread.start()
    }

    fun cancel(language: Language) {
        cancelFlags[language]!!.set(true)
    }

    fun delete(language: Language) {
        cancel(language)
        File(appContext.filesDir, "vosk/${stableDir(language)}").deleteRecursively()
        statuses[language]!!.value = ModelStatus.NotDownloaded
    }

    private fun doDownload(language: Language) {
        val statusState = statuses[language]!!
        val cancelled = cancelFlags[language]!!
        val dir = File(appContext.filesDir, "vosk/${stableDir(language)}")
        val stagingDir = File(appContext.cacheDir, "vosk-downloads").also { it.mkdirs() }
        val partFile = File(stagingDir, "${language.name.lowercase()}.zip.part")
        val zipFile = File(stagingDir, "${language.name.lowercase()}.zip")

        partFile.delete()
        zipFile.delete()

        try {
            if (appContext.filesDir.freeSpace < 100L * 1024 * 1024) {
                statusState.value = ModelStatus.Failed("not enough space")
                return
            }

            val conn = URL(downloadUrl(language)).openConnection() as HttpURLConnection
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
                            statusState.value = ModelStatus.Downloading((downloaded * 100 / total).toInt())
                            lastUpdateMs = now
                        }
                    }
                }
            }

            if (cancelled.get()) throw DownloadCancelled()
            partFile.renameTo(zipFile)

            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            val canonDir = dir.canonicalPath

            ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (cancelled.get()) throw DownloadCancelled()
                    val rel = entry.name.substringAfter('/')
                    if (rel.isNotEmpty() && !entry.isDirectory) {
                        val out = File(dir, rel)
                        if (!out.canonicalPath.startsWith("$canonDir${File.separator}"))
                            throw SecurityException("zip slip detected: $rel")
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                    entry = zip.nextEntry
                }
            }

            zipFile.delete()
            File(dir, ".extracted").createNewFile()
            statusState.value = ModelStatus.Ready
        } catch (_: DownloadCancelled) {
            partFile.delete()
            zipFile.delete()
            statusState.value = ModelStatus.NotDownloaded
        } catch (e: Exception) {
            partFile.delete()
            zipFile.delete()
            if (dir.exists() && !File(dir, ".extracted").exists()) dir.deleteRecursively()
            statusState.value = if (cancelled.get()) ModelStatus.NotDownloaded
                                else ModelStatus.Failed(e.message ?: "download failed")
        } finally {
            workers[language]!!.set(null)
        }
    }

    private class DownloadCancelled : Exception()

    companion object {
        @Volatile private var instance: VoskModelManager? = null

        fun get(context: Context): VoskModelManager =
            instance ?: synchronized(this) {
                instance ?: VoskModelManager(context.applicationContext).also { instance = it }
            }

        private fun stableDir(language: Language) = when (language) {
            Language.JA -> "vosk-model-small-ja"
            Language.EN -> "vosk-model-small-en-us"
        }

        private fun downloadUrl(language: Language) = when (language) {
            Language.JA -> "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
            Language.EN -> "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        }
    }
}
