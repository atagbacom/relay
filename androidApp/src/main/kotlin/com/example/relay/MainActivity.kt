package com.relay.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.relay.app.mesh.MeshService
import com.relay.app.ui.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissions = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startMeshService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            RelayApp(viewModel)
        }

        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) ==
                        PackageManager.PERMISSION_GRANTED
            }) {
            startMeshService()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun startMeshService() {
        startForegroundService(Intent(this, MeshService::class.java))
    }
}

// ── Navigation ────────────────────────────────────────────

sealed class Screen {
    object Contacts : Screen()
    data class Conversation(val contactId: String) : Screen()
    object Qr : Screen()
}

@Composable
fun RelayApp(viewModel: MainViewModel) {
    var screen by remember { mutableStateOf<Screen>(Screen.Contacts) }
    var qrReturnContact by remember { mutableStateOf<String?>(null) }

    when (val s = screen) {
        is Screen.Contacts -> ContactsScreen(
            viewModel = viewModel,
            onContactSelected = { screen = Screen.Conversation(it) },
            onQrScan = { screen = Screen.Qr }
        )

        is Screen.Conversation -> ConversationScreen(
            contactId = s.contactId,
            viewModel = viewModel,
            onBack = { screen = Screen.Contacts },
            onQrExchange = {
                qrReturnContact = s.contactId
                screen = Screen.Qr
            }
        )

        is Screen.Qr -> QrScreen(
            viewModel = viewModel,
            onContactAdded = {
                screen = qrReturnContact
                    ?.let { Screen.Conversation(it) }
                    ?: Screen.Contacts
                qrReturnContact = null
            },
            onBack = {
                screen = qrReturnContact
                    ?.let { Screen.Conversation(it) }
                    ?: Screen.Contacts
                qrReturnContact = null
            }
        )
    }
}