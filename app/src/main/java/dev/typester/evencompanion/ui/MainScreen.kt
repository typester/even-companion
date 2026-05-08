package dev.typester.evencompanion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.typester.evencompanion.core.EvenCore
import dev.typester.evencompanion.service.CoreService

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

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
