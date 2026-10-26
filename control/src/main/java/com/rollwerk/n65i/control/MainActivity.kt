package com.rollwerk.n65i.control

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val model: ControlModel by viewModels()
    private val permissions get() = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) model.scan()
        else model.failure("Bluetooth permission is required to scan and connect")
    }
    private fun requestScan() {
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) model.scan()
        else permissionRequest.launch(permissions)
    }

    /** Wraps the view model so the "Scan" button triggers the Android permission request. */
    private val actions by lazy {
        object : ControlActions by model {
            override fun scan() = requestScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            N65iControlTheme {
                val s by model.state.collectAsState()
                val fw by model.firmware.collectAsState()
                val view = LocalView.current
                DisposableEffect(fw.dfuActive) {
                    view.keepScreenOn = fw.dfuActive // the screen must not sleep mid-flash
                    onDispose { view.keepScreenOn = false }
                }
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
                LaunchedEffect(s.device, s.busy) { if (s.device == null && !s.busy) requestScan() }
                ControlScreen(s, fw, now, actions)
            }
        }
    }
}
