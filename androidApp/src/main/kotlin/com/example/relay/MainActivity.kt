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
import com.relay.app.mesh.Notifications
import com.relay.app.ui.*
import com.relay.app.ui.theme.RelayTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Conversation à ouvrir, demandée par une notification. */
    private val contactAOuvrir = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactAOuvrir.value = intent?.getStringExtra(Notifications.EXTRA_OUVRIR_CONTACT)

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
                    PorteAutorisations(onPret = ::startMeshService) {
                        RelayApp(viewModel, contactAOuvrir)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifications.EXTRA_OUVRIR_CONTACT)?.let { contactAOuvrir.value = it }
    }

    private fun startMeshService() {
        ContextCompat.startForegroundService(this, Intent(this, MeshService::class.java))
    }
}

/** Autorisations sans lesquelles le réseau ne peut pas démarrer. */
fun autorisationsReseau(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/**
 * Affiche l'accueil tant que les autorisations Bluetooth manquent ; démarre
 * le réseau dès qu'elles sont accordées. Les notifications sont demandées
 * en même temps mais restent facultatives ; la caméra, au moment du scan.
 */
@Composable
private fun PorteAutorisations(onPret: () -> Unit, contenu: @Composable () -> Unit) {
    val contexte = androidx.compose.ui.platform.LocalContext.current
    val activite = contexte as ComponentActivity
    fun accordees() = autorisationsReseau().all {
        ContextCompat.checkSelfPermission(contexte, it) == PackageManager.PERMISSION_GRANTED
    }
    var pret by remember { mutableStateOf(accordees()) }
    var demandeFaite by remember { mutableStateOf(false) }
    var refusDefinitif by remember { mutableStateOf(false) }

    val demande = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        pret = accordees()
        demandeFaite = true
        // Refusé et Android ne reposera plus la question : passer par les réglages
        refusDefinitif = !pret && autorisationsReseau().none { p -> activite.shouldShowRequestPermissionRationale(p) }
    }

    // Retour des réglages du téléphone : revérifier
    val cycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(cycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) pret = accordees()
        }
        cycle.lifecycle.addObserver(obs)
        onDispose { cycle.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(pret) { if (pret) onPret() }

    if (pret) {
        contenu()
    } else {
        AccueilScreen(refusDefinitif = refusDefinitif && demandeFaite) {
            val tout = autorisationsReseau().toMutableList()
            // Recommandée partout : certains fabricants (Infinix, Tecno, Xiaomi…)
            // ne livrent les appareils Bluetooth trouvés qu'avec la localisation
            if (Manifest.permission.ACCESS_FINE_LOCATION !in tout) {
                tout += Manifest.permission.ACCESS_FINE_LOCATION
                tout += Manifest.permission.ACCESS_COARSE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) tout += Manifest.permission.POST_NOTIFICATIONS
            demande.launch(tout.toTypedArray())
        }
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
    object Diagnostic : Screen()
    object Reglages : Screen()
}

@Composable
fun RelayApp(viewModel: MainViewModel, contactAOuvrir: MutableState<String?> = mutableStateOf(null)) {
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

    // Ouverture depuis une notification
    LaunchedEffect(contactAOuvrir.value) {
        contactAOuvrir.value?.let {
            onglet = Onglet.MESSAGES
            screen = Screen.Conversation(it)
            contactAOuvrir.value = null
        }
    }

    // Quelqu'un vient de scanner notre code : comparer les mots, où qu'on soit
    VerificationRecueDialogue(viewModel)

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
                    onVerifier = { versOnglet(Onglet.VERIFIER) },
                    onReglages = { screen = Screen.Reglages }
                )

                is Screen.Reglages -> ReglagesScreen(
                    viewModel = viewModel,
                    onBack = { screen = Screen.Reseau },
                    onDiagnostic = { screen = Screen.Diagnostic },
                    onContact = { screen = Screen.Fiche(it) }
                )

                is Screen.Diagnostic -> DiagnosticScreen(
                    viewModel = viewModel,
                    onBack = { screen = Screen.Reglages }
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
