package com.relay.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.relay.app.ui.components.BarreNavigation
import com.relay.app.ui.components.Onglet
import androidx.core.content.ContextCompat
import com.relay.app.mesh.MeshService
import com.relay.app.ui.*
import com.relay.app.ui.theme.RelayTheme

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

        enableEdgeToEdge()
        val fichierPlantage = RelayApplication.rapport(application)
        val dernierPlantage = fichierPlantage.takeIf { it.exists() }?.readText()
        setContent {
            RelayTheme {
                var rapport by remember { mutableStateOf(dernierPlantage) }
                val r = rapport
                if (r != null) {
                    RapportPlantageScreen(r, onContinuer = { fichierPlantage.delete(); rapport = null })
                } else {
                    RelayApp(viewModel)
                }
            }
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
    object Reseau : Screen()
    object Messages : Screen()
    data class Conversation(val contactId: String) : Screen()
    data class Fiche(val contactId: String) : Screen()
    object Qr : Screen()
    object Alerte : Screen()
}

@Composable
fun RelayApp(viewModel: MainViewModel) {
    var screen by remember { mutableStateOf<Screen>(Screen.Reseau) }
    var onglet by remember { mutableStateOf(Onglet.RESEAU) }
    var qrReturnContact by remember { mutableStateOf<String?>(null) }

    fun versOnglet(o: Onglet) {
        onglet = o
        screen = when (o) {
            Onglet.RESEAU -> Screen.Reseau
            Onglet.MESSAGES -> Screen.Messages
            Onglet.VERIFIER -> Screen.Qr
            Onglet.ALERTE -> Screen.Alerte
        }
    }
    val retourQr = {
        screen = qrReturnContact?.let { Screen.Conversation(it) }
            ?: if (onglet == Onglet.MESSAGES) Screen.Messages else Screen.Reseau
        if (onglet == Onglet.VERIFIER) onglet = Onglet.RESEAU
        qrReturnContact = null
    }

    val avecBarre = screen is Screen.Reseau || screen is Screen.Messages
    Scaffold(
        bottomBar = { if (avecBarre) BarreNavigation(onglet, ::versOnglet) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { marges ->
        Box(Modifier.padding(marges)) {
            when (val s = screen) {
                is Screen.Reseau -> ReseauScreen(
                    viewModel = viewModel,
                    onOuvrirConversation = { screen = Screen.Conversation(it) },
                    onVerifier = { versOnglet(Onglet.VERIFIER) }
                )

                is Screen.Messages -> MessagesScreen(
                    viewModel = viewModel,
                    onOuvrirConversation = { screen = Screen.Conversation(it) }
                )

                is Screen.Conversation -> ConversationScreen(
                    contactId = s.contactId,
                    viewModel = viewModel,
                    onBack = { screen = if (onglet == Onglet.MESSAGES) Screen.Messages else Screen.Reseau },
                    onQrExchange = {
                        qrReturnContact = s.contactId
                        screen = Screen.Qr
                    },
                    onVoirFiche = { screen = Screen.Fiche(s.contactId) }
                )

                is Screen.Fiche -> FicheContactScreen(
                    contactId = s.contactId,
                    viewModel = viewModel,
                    onBack = { screen = Screen.Conversation(s.contactId) },
                    onEcrire = { screen = Screen.Conversation(s.contactId) },
                    onVerifier = {
                        qrReturnContact = s.contactId
                        screen = Screen.Qr
                    }
                )

                is Screen.Alerte -> AlerteScreen(
                    viewModel = viewModel,
                    onBack = { versOnglet(Onglet.RESEAU) }
                )

                is Screen.Qr -> QrScreen(
                    viewModel = viewModel,
                    onContactAdded = { id ->
                        qrReturnContact = null
                        if (onglet == Onglet.VERIFIER) onglet = Onglet.MESSAGES
                        screen = Screen.Conversation(id)
                    },
                    onBack = { retourQr() }
                )
            }
        }
    }
}
