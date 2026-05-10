package dev.typester.evencompanion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import dev.typester.evencompanion.llm.GemmaModelManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.typester.evencompanion.core.EvenCore
import dev.typester.evencompanion.core.uniffi.Language
import dev.typester.evencompanion.service.CoreService
import dev.typester.evencompanion.stt.ModelStatus
import dev.typester.evencompanion.stt.SherpaModelManager
import dev.typester.evencompanion.stt.VoskModelManager
import dev.typester.evencompanion.stt.VoskModelSize

@Composable
fun MainScreen() {
    val ctx = LocalContext.current

    var hasFineLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotifications by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33)
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true
        )
    }
    var running by remember { mutableStateOf(EvenCore.instance.isRunning()) }

    val permLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { results ->
        hasFineLocation = results[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFineLocation
        if (Build.VERSION.SDK_INT >= 33) {
            hasNotifications = results[Manifest.permission.POST_NOTIFICATIONS] == true || hasNotifications
        }
    }

    val gemmaMgr = remember { GemmaModelManager.get(ctx) }
    val gemmaStatus by gemmaMgr.status()

    val voskMgr = remember { VoskModelManager.get(ctx) }

    val sherpaMgr = remember { SherpaModelManager.get(ctx) }
    val sherpaEnStatus by sherpaMgr.status(Language.EN)

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Gemma (on-device LLM)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            ModelStatusRow(
                label = "Gemma 4 E2B (~2.6 GB)",
                status = gemmaStatus,
                onDownload = { gemmaMgr.download() },
                onCancel = { gemmaMgr.cancel() },
                onDelete = { gemmaMgr.delete() },
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("VOSK", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            VoskLanguageSection(label = "Japanese", language = Language.JA, manager = voskMgr)
            Spacer(modifier = Modifier.height(8.dp))
            VoskLanguageSection(label = "English", language = Language.EN, manager = voskMgr)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Sherpa-ONNX (streaming)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            ModelStatusRow(
                label = "English",
                status = sherpaEnStatus,
                onDownload = { sherpaMgr.download(Language.EN) },
                onCancel = { sherpaMgr.cancel(Language.EN) },
                onDelete = { sherpaMgr.delete(Language.EN) },
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = if (running) "Running on port ${EvenCore.DEFAULT_PORT}" else "Stopped",
                style = MaterialTheme.typography.bodyLarge
            )
            when {
                !hasFineLocation || !hasNotifications -> Button(
                    onClick = {
                        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                        permLauncher.launch(perms.toTypedArray())
                    },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Grant permissions")
                }
                else -> Button(
                    onClick = {
                        if (running) CoreService.stop(ctx) else CoreService.start(ctx)
                        running = !running
                    },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(if (running) "Stop" else "Start")
                }
            }
        }
    }
}

@Composable
private fun VoskLanguageSection(
    label: String,
    language: Language,
    manager: VoskModelManager,
) {
    val variants = remember(language) { VoskModelManager.variantsFor(language) }
    val selectedSize by manager.selectedSize(language)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))
        for (size in variants) {
            val status by manager.status(language, size)
            VoskVariantRow(
                label = "${VoskModelManager.displayName(size)} (${VoskModelManager.sizeHint(language, size)})",
                isSelected = selectedSize == size,
                onSelect = { manager.setSelectedSize(language, size) },
                status = status,
                onDownload = { manager.download(language, size) },
                onCancel = { manager.cancel(language, size) },
                onDelete = { manager.delete(language, size) },
            )
        }
    }
}

@Composable
private fun VoskVariantRow(
    label: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    status: ModelStatus,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isSelected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall)
                Text(
                    when (status) {
                        is ModelStatus.NotDownloaded -> "Not downloaded"
                        is ModelStatus.Downloading -> "Downloading… ${status.percent}%"
                        is ModelStatus.Extracting -> "Extracting…"
                        is ModelStatus.Ready -> "Ready"
                        is ModelStatus.Failed -> "Failed: ${status.reason}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Button(
                onClick = {
                    when (status) {
                        is ModelStatus.NotDownloaded, is ModelStatus.Failed -> onDownload()
                        is ModelStatus.Downloading, is ModelStatus.Extracting -> onCancel()
                        is ModelStatus.Ready -> onDelete()
                    }
                },
            ) {
                Text(
                    when (status) {
                        is ModelStatus.NotDownloaded, is ModelStatus.Failed -> "Download"
                        is ModelStatus.Downloading, is ModelStatus.Extracting -> "Cancel"
                        is ModelStatus.Ready -> "Delete"
                    }
                )
            }
        }
        when (status) {
            is ModelStatus.Downloading -> LinearProgressIndicator(
                progress = { status.percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp, bottom = 4.dp),
            )
            is ModelStatus.Extracting -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp, bottom = 4.dp),
            )
            else -> {}
        }
    }
}

@Composable
private fun ModelStatusRow(
    label: String,
    status: ModelStatus,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (status) {
                        is ModelStatus.NotDownloaded -> "Not downloaded"
                        is ModelStatus.Downloading -> "Downloading… ${status.percent}%"
                        is ModelStatus.Extracting -> "Extracting…"
                        is ModelStatus.Ready -> "Ready"
                        is ModelStatus.Failed -> "Failed: ${status.reason}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = {
                    when (status) {
                        is ModelStatus.NotDownloaded, is ModelStatus.Failed -> onDownload()
                        is ModelStatus.Downloading, is ModelStatus.Extracting -> onCancel()
                        is ModelStatus.Ready -> onDelete()
                    }
                },
            ) {
                Text(
                    when (status) {
                        is ModelStatus.NotDownloaded, is ModelStatus.Failed -> "Download"
                        is ModelStatus.Downloading, is ModelStatus.Extracting -> "Cancel"
                        is ModelStatus.Ready -> "Delete"
                    }
                )
            }
        }
        when (status) {
            is ModelStatus.Downloading -> LinearProgressIndicator(
                progress = { status.percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            is ModelStatus.Extracting -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            else -> {}
        }
    }
}
