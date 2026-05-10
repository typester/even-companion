package dev.typester.evencompanion.stt

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.typester.evencompanion.core.uniffi.Language
import java.io.File
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

enum class VoskModelSize { SMALL, LGRAPH, LARGE, GIGASPEECH }

class VoskModelManager private constructor(private val appContext: Context) {

    private val prefs = appContext.getSharedPreferences("vosk_prefs", Context.MODE_PRIVATE)

    private val statuses: Map<Language, EnumMap<VoskModelSize, MutableState<ModelStatus>>> =
        Language.values().associateWith { lang ->
            EnumMap<VoskModelSize, MutableState<ModelStatus>>(VoskModelSize::class.java).apply {
                for (size in variantsFor(lang)) {
                    val stamp = File(appContext.filesDir, "vosk/${stableDir(lang, size)}/.extracted")
                    this[size] = mutableStateOf(if (stamp.exists()) ModelStatus.Ready else ModelStatus.NotDownloaded)
                }
            }
        }

    private val extractCancelFlags: Map<Language, EnumMap<VoskModelSize, AtomicBoolean>> =
        Language.values().associateWith { lang ->
            EnumMap<VoskModelSize, AtomicBoolean>(VoskModelSize::class.java).apply {
                for (size in variantsFor(lang)) this[size] = AtomicBoolean(false)
            }
        }

    private val selectedSizeStates: Map<Language, MutableState<VoskModelSize>> =
        Language.values().associateWith { lang -> mutableStateOf(loadSelectedSize(lang)) }

    init {
        restoreInProgressDownloads()
    }

    fun status(language: Language, size: VoskModelSize): State<ModelStatus> =
        statuses[language]!![size] ?: mutableStateOf(ModelStatus.NotDownloaded)

    fun isReady(language: Language, size: VoskModelSize): Boolean =
        statuses[language]!![size]?.value is ModelStatus.Ready

    fun isAnyReady(language: Language): Boolean =
        variantsFor(language).any { isReady(language, it) }

    fun modelPath(language: Language, size: VoskModelSize): String? {
        if (!isReady(language, size)) return null
        return File(appContext.filesDir, "vosk/${stableDir(language, size)}").absolutePath
    }

    fun effectiveModelPath(language: Language): String? {
        val preferred = selectedSizeStates[language]!!.value
        modelPath(language, preferred)?.let { return it }
        for (size in variantsFor(language)) {
            modelPath(language, size)?.let { return it }
        }
        return null
    }

    fun effectiveSize(language: Language): VoskModelSize? {
        val preferred = selectedSizeStates[language]!!.value
        if (isReady(language, preferred)) return preferred
        return variantsFor(language).firstOrNull { isReady(language, it) }
    }

    fun selectedSize(language: Language): State<VoskModelSize> = selectedSizeStates[language]!!

    fun setSelectedSize(language: Language, size: VoskModelSize) {
        if (size !in variantsFor(language)) return
        selectedSizeStates[language]!!.value = size
        prefs.edit().putString(selectedSizePrefKey(language), size.name.lowercase()).apply()
    }

    fun download(language: Language, size: VoskModelSize) {
        if (size !in variantsFor(language)) return
        val statusState = statuses[language]!![size]!!
        val current = statusState.value
        if (current is ModelStatus.Downloading || current is ModelStatus.Ready) return

        val destFile = archiveFile(language, size)
        destFile.parentFile?.mkdirs()
        statusState.value = ModelStatus.Downloading(0)

        val id = ModelDownloader.get(appContext).enqueue(
            url = downloadUrl(language, size),
            destFile = destFile,
            title = "VOSK ${displayName(language)} (${displayName(size)})",
            listener = makeListener(language, size, destFile),
        )
        prefs.edit().putLong(downloadIdPrefKey(language, size), id).apply()
    }

    fun cancel(language: Language, size: VoskModelSize) {
        if (size !in variantsFor(language)) return
        val statusState = statuses[language]!![size]!!
        val id = prefs.getLong(downloadIdPrefKey(language, size), -1L)
        if (id > 0) {
            ModelDownloader.get(appContext).cancel(id)
            prefs.edit().remove(downloadIdPrefKey(language, size)).apply()
        }
        extractCancelFlags[language]!![size]!!.set(true)
        if (statusState.value !is ModelStatus.Ready) {
            statusState.value = ModelStatus.NotDownloaded
        }
    }

    fun delete(language: Language, size: VoskModelSize) {
        if (size !in variantsFor(language)) return
        cancel(language, size)
        File(appContext.filesDir, "vosk/${stableDir(language, size)}").deleteRecursively()
        statuses[language]!![size]!!.value = ModelStatus.NotDownloaded
    }

    private fun makeListener(language: Language, size: VoskModelSize, destFile: File) =
        object : ModelDownloader.Listener {
            override fun onProgress(percent: Int) {
                statuses[language]!![size]!!.value = ModelStatus.Downloading(percent)
            }
            override fun onSuccess(downloadedFile: File) {
                extractZipAsync(language, size, downloadedFile)
            }
            override fun onFailed(reason: String) {
                statuses[language]!![size]!!.value = ModelStatus.Failed(reason)
                prefs.edit().remove(downloadIdPrefKey(language, size)).apply()
            }
            override fun onGone() {
                statuses[language]!![size]!!.value = ModelStatus.NotDownloaded
                prefs.edit().remove(downloadIdPrefKey(language, size)).apply()
            }
        }

    private fun extractZipAsync(language: Language, size: VoskModelSize, zipFile: File) {
        val statusState = statuses[language]!![size]!!
        val cancelled = extractCancelFlags[language]!![size]!!
        cancelled.set(false)
        statusState.value = ModelStatus.Extracting

        Thread {
            val dir = File(appContext.filesDir, "vosk/${stableDir(language, size)}")
            try {
                if (dir.exists()) dir.deleteRecursively()
                dir.mkdirs()
                val canonDir = dir.canonicalPath

                ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (cancelled.get()) throw ExtractionCancelled()
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
                prefs.edit().remove(downloadIdPrefKey(language, size)).apply()
                statusState.value = ModelStatus.Ready
            } catch (_: ExtractionCancelled) {
                zipFile.delete()
                if (dir.exists() && !File(dir, ".extracted").exists()) dir.deleteRecursively()
                statusState.value = ModelStatus.NotDownloaded
            } catch (e: Exception) {
                zipFile.delete()
                if (dir.exists() && !File(dir, ".extracted").exists()) dir.deleteRecursively()
                statusState.value = ModelStatus.Failed("extract: ${e.message ?: "unknown"}")
                prefs.edit().remove(downloadIdPrefKey(language, size)).apply()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun restoreInProgressDownloads() {
        for (language in Language.values()) {
            for (size in variantsFor(language)) {
                val statusState = statuses[language]!![size]!!
                if (statusState.value is ModelStatus.Ready) continue

                val id = prefs.getLong(downloadIdPrefKey(language, size), -1L)
                val destFile = archiveFile(language, size)

                if (id > 0) {
                    statusState.value = ModelStatus.Downloading(0)
                    ModelDownloader.get(appContext).reattach(id, destFile, makeListener(language, size, destFile))
                } else if (destFile.exists()) {
                    extractZipAsync(language, size, destFile)
                }
            }
        }
    }

    private class ExtractionCancelled : Exception()

    private fun loadSelectedSize(language: Language): VoskModelSize {
        val stored = prefs.getString(selectedSizePrefKey(language), null)
        return when (stored) {
            "small" -> VoskModelSize.SMALL
            "lgraph" -> VoskModelSize.LGRAPH
            "gigaspeech" -> VoskModelSize.GIGASPEECH
            "large" -> when (language) {
                Language.EN -> VoskModelSize.LGRAPH  // legacy: old "large" was lgraph for EN
                Language.JA -> VoskModelSize.LARGE
            }
            else -> VoskModelSize.SMALL
        }
    }

    private fun archiveFile(language: Language, size: VoskModelSize): File =
        File(
            appContext.getExternalFilesDir(null) ?: appContext.cacheDir,
            "model-downloads/vosk_${language.name.lowercase()}_${size.name.lowercase()}.zip"
        )

    companion object {
        @Volatile private var instance: VoskModelManager? = null

        fun get(context: Context): VoskModelManager =
            instance ?: synchronized(this) {
                instance ?: VoskModelManager(context.applicationContext).also { instance = it }
            }

        fun variantsFor(language: Language): List<VoskModelSize> = when (language) {
            Language.JA -> listOf(VoskModelSize.SMALL, VoskModelSize.LARGE)
            Language.EN -> listOf(VoskModelSize.SMALL, VoskModelSize.LGRAPH, VoskModelSize.LARGE, VoskModelSize.GIGASPEECH)
        }

        fun displayName(size: VoskModelSize) = when (size) {
            VoskModelSize.SMALL -> "Small"
            VoskModelSize.LGRAPH -> "Lgraph"
            VoskModelSize.LARGE -> "Full"
            VoskModelSize.GIGASPEECH -> "GigaSpeech"
        }

        fun sizeHint(language: Language, size: VoskModelSize) = when (language to size) {
            Language.JA to VoskModelSize.SMALL -> "~49 MB"
            Language.JA to VoskModelSize.LARGE -> "~1 GB"
            Language.EN to VoskModelSize.SMALL -> "~41 MB"
            Language.EN to VoskModelSize.LGRAPH -> "~128 MB"
            Language.EN to VoskModelSize.LARGE -> "~1.8 GB"
            Language.EN to VoskModelSize.GIGASPEECH -> "~2.3 GB"
            else -> ""
        }

        fun stableDir(language: Language, size: VoskModelSize) = when (language to size) {
            Language.JA to VoskModelSize.SMALL -> "vosk-model-small-ja"
            Language.JA to VoskModelSize.LARGE -> "vosk-model-ja"
            Language.EN to VoskModelSize.SMALL -> "vosk-model-small-en-us"
            Language.EN to VoskModelSize.LGRAPH -> "vosk-model-en-us-lgraph"
            Language.EN to VoskModelSize.LARGE -> "vosk-model-en-us"
            Language.EN to VoskModelSize.GIGASPEECH -> "vosk-model-en-us-gigaspeech"
            else -> error("invalid combination: $language $size")
        }

        private fun downloadUrl(language: Language, size: VoskModelSize) = when (language to size) {
            Language.JA to VoskModelSize.SMALL -> "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
            Language.JA to VoskModelSize.LARGE -> "https://alphacephei.com/vosk/models/vosk-model-ja-0.22.zip"
            Language.EN to VoskModelSize.SMALL -> "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
            Language.EN to VoskModelSize.LGRAPH -> "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip"
            Language.EN to VoskModelSize.LARGE -> "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip"
            Language.EN to VoskModelSize.GIGASPEECH -> "https://alphacephei.com/vosk/models/vosk-model-en-us-0.42-gigaspeech.zip"
            else -> error("invalid combination: $language $size")
        }

        private fun minFreeSpace(language: Language, size: VoskModelSize): Long = when (language to size) {
            Language.JA to VoskModelSize.SMALL -> 100L * 1024 * 1024
            Language.JA to VoskModelSize.LARGE -> 4L * 1024 * 1024 * 1024
            Language.EN to VoskModelSize.SMALL -> 100L * 1024 * 1024
            Language.EN to VoskModelSize.LGRAPH -> 300L * 1024 * 1024
            Language.EN to VoskModelSize.LARGE -> 5L * 1024 * 1024 * 1024
            Language.EN to VoskModelSize.GIGASPEECH -> 7L * 1024 * 1024 * 1024
            else -> 500L * 1024 * 1024
        }

        private fun displayName(language: Language) = when (language) {
            Language.JA -> "Japanese"
            Language.EN -> "English"
        }

        private fun selectedSizePrefKey(language: Language) = when (language) {
            Language.JA -> "vosk_selected_size_ja"
            Language.EN -> "vosk_selected_size_en"
        }

        private fun downloadIdPrefKey(language: Language, size: VoskModelSize) =
            "vosk_download_id_${language.name.lowercase()}_${size.name.lowercase()}"
    }
}
