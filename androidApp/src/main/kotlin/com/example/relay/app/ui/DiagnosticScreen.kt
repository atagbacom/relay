package com.relay.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.relay.app.mesh.EtatMesh
import com.relay.app.mesh.MeshService
import com.relay.app.ui.components.Icones
import com.relay.app.ui.theme.PlexMono
import kotlinx.coroutines.delay

/**
 * Diagnostic du réseau : pourquoi deux téléphones ne se voient pas.
 * Tout ce qu'on lirait avec adb, lisible et partageable depuis le téléphone.
 */
@Composable
fun DiagnosticScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val contexte = LocalContext.current
    val identifiantQr by viewModel.shortId.collectAsState()
    val empreinte by viewModel.empreinte.collectAsState()
    val identifiantService by EtatMesh.identifiant.collectAsState()
    val bluetooth by EtatMesh.bluetoothActif.collectAsState()
    val annonce by EtatMesh.annonce.collectAsState()
    val scan by EtatMesh.scan.collectAsState()
    val serveur by EtatMesh.serveur.collectAsState()
    val recues by EtatMesh.annoncesRecues.collectAsState()
    val pairs by EtatMesh.pairs.collectAsState()
    val journal by EtatMesh.journal.collectAsState()
    var maintenant by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); maintenant = System.currentTimeMillis() } }

    val autorisations = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add("Bluetooth : recherche" to Manifest.permission.BLUETOOTH_SCAN)
            add("Bluetooth : annonce" to Manifest.permission.BLUETOOTH_ADVERTISE)
            add("Bluetooth : connexion" to Manifest.permission.BLUETOOTH_CONNECT)
        }
        add("Localisation" to Manifest.permission.ACCESS_FINE_LOCATION)
        add("Caméra (scan des codes)" to Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add("Notifications" to Manifest.permission.POST_NOTIFICATIONS)
        }
    }.map { (nom, p) -> nom to (ContextCompat.checkSelfPermission(contexte, p) == PackageManager.PERMISSION_GRANTED) }

    val serviceDemarre = identifiantService.isNotEmpty()
    val localisationActivee = runCatching {
        val lm = contexte.getSystemService(android.location.LocationManager::class.java)
        androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
    }.getOrDefault(false)
    val memeIdentite = !serviceDemarre || identifiantQr == identifiantService

    fun rapport(): String = buildString {
        appendLine("Relay — diagnostic réseau")
        appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Identifiant (QR) : $identifiantQr · (service) : $identifiantService")
        appendLine("Bluetooth : ${bluetooth ?: "inconnu"} · annonce : $annonce · scan : $scan · serveur : $serveur")
        appendLine("Annonces Relay reçues : $recues · appareils vus : ${pairs.size} · localisation : ${if (localisationActivee) "activée" else "désactivée"}")
        autorisations.forEach { (n, ok) -> appendLine("Autorisation $n : ${if (ok) "accordée" else "REFUSÉE"}") }
        appendLine()
        journal.forEach { appendLine(it) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icones.Retour, contentDescription = "Retour", tint = MaterialTheme.colorScheme.primary)
            }
            Text("Diagnostic du réseau", style = MaterialTheme.typography.titleLarge)
        }

        Section("Ce téléphone") {
            Ligne("Identifiant", identifiantQr.ifEmpty { "…" }, mono = true)
            Ligne("Empreinte", empreinte.ifEmpty { "…" }, mono = true)
            if (!memeIdentite) {
                Alerte("L'écran et le service n'ont pas la même identité ($identifiantQr / $identifiantService). Redémarrez le téléphone ; si ça persiste, partagez ce diagnostic.")
            }
        }

        Section("Bluetooth et service") {
            Etat("Service mesh", if (serviceDemarre) "démarré" else "pas démarré", serviceDemarre)
            Etat("Bluetooth", when (bluetooth) { true -> "allumé"; false -> "éteint"; null -> "inconnu" }, bluetooth == true)
            Etat("Annonce (se faire voir)", annonce, annonce == "active")
            Etat("Scan (voir les autres)", scan, scan == "actif")
            Etat("Serveur (recevoir des messages)", serveur, serveur == "actif")
            Etat("Annonces Relay reçues", recues.toString(), recues > 0)
            Etat("Localisation du téléphone", if (localisationActivee) "activée" else "désactivée", localisationActivee)
            if (!localisationActivee) {
                Text(
                    "Sur certains téléphones (Infinix, Tecno, Xiaomi…), la recherche Bluetooth ne trouve rien tant que la localisation est désactivée.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val i = Intent(contexte, MeshService::class.java).setAction(MeshService.ACTION_RELANCER)
                        runCatching { ContextCompat.startForegroundService(contexte, i) }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Relancer le réseau") }
                OutlinedButton(
                    onClick = {
                        contexte.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${contexte.packageName}"))
                        )
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Autorisations") }
            }
        }

        Section("Autorisations") {
            autorisations.forEach { (nom, ok) -> Etat(nom, if (ok) "accordée" else "refusée", ok) }
        }

        Section("Appareils vus (${pairs.size})") {
            if (pairs.isEmpty()) {
                Text(
                    if (recues > 0) "Des annonces Relay arrivent, mais sans identifiant lisible."
                    else "Aucun appareil Relay détecté. Vérifiez que l'autre téléphone a l'annonce « active ».",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            pairs.forEach { p ->
                Ligne(p.shortId, "${p.rssi} dBm · vu il y a ${((maintenant - p.vuA) / 1000).coerceAtLeast(0)} s", mono = true)
            }
        }

        Section("Journal") {
            Text(
                journal.takeLast(60).reversed().joinToString("\n").ifEmpty { "Vide : le service n'a encore rien noté." },
                fontFamily = PlexMono, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(10.dp)
            )
            OutlinedButton(
                onClick = {
                    val envoi = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Relay — diagnostic réseau")
                        putExtra(Intent.EXTRA_TEXT, rapport())
                    }
                    contexte.startActivity(Intent.createChooser(envoi, "Partager le diagnostic"))
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp)
            ) { Text("Partager le diagnostic") }
        }
    }
}

@Composable
private fun Section(titre: String, contenu: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(titre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
            contenu()
        }
    }
}

@Composable
private fun Ligne(nom: String, valeur: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(nom, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = if (mono) PlexMono else null)
        Text(valeur, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) PlexMono else null)
    }
}

@Composable
private fun Etat(nom: String, valeur: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(if (ok) Color(0xFF2E9E47) else Color(0xFFC25A14), CircleShape))
        Text(nom, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp).weight(1f))
        Text(
            valeur, style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun Alerte(texte: String) {
    Text(
        texte, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp)).padding(10.dp)
    )
}
