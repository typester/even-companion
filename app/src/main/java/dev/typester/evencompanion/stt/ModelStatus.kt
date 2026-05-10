package dev.typester.evencompanion.stt

sealed class ModelStatus {
    object NotDownloaded : ModelStatus()
    data class Downloading(val percent: Int) : ModelStatus()
    object Extracting : ModelStatus()
    object Ready : ModelStatus()
    data class Failed(val reason: String) : ModelStatus()
}
