package com.relay.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import com.relay.app.mesh.EtatMesh
import com.relay.app.ui.components.Icones
import com.relay.app.ui.components.LigneConversation
import com.relay.app.ui.theme.PlexMono

/** Réglages : son nom, ses contacts, le réseau, les notifications, à propos. */
@Composable
fun ReglagesScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onDiagnostic: () -> Unit,
    onContact: (String) -> Unit,
) {
    val contexte = LocalContext.current
    val monNom by viewModel.monNom.collectAsState()
    val identifiant by viewModel.shortId.collectAsState()
    val empreinte by viewModel.empreinte.collectAsState()
    val contacts by viewModel.contacts.collectAsState(emptyList())
    val bluetooth by EtatMesh.bluetoothActif.collectAsState()
    val annonce by EtatMesh.annonce.collectAsState()
    val scan by EtatMesh.scan.collectAsState()
    val pairs by EtatMesh.pairs.collectAsState()
    var nom by remember(monNom) { mutableStateOf(monNom) }
    val version = remember {
        runCatching { contexte.packageManager.getPackageInfo(contexte.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    val reseauOk = bluetooth == true && annonce == "active" && scan == "actif"

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
            Text("Réglages", style = MaterialTheme.typography.titleLarge)
        }

        Bloc("Mon profil") {
            OutlinedTextField(
                value = nom,
                onValueChange = { nom = it.take(40) },
                singleLine = true,
                label = { Text("Mon nom") },
                supportingText = { Text("Transmis à la personne qui scanne votre code. Rien d'autre ne quitte le téléphone.") },
                modifier = Modifier.fillMaxWidth()
            )
            if (nom.trim() != monNom) {
                Button(
                    onClick = { viewModel.changerMonNom(nom) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) { Text("Enregistrer") }
            }
            Info("Identifiant", identifiant)
            Info("Empreinte de la clé", empreinte)
        }

        Bloc("Contacts (${contacts.size})") {
            if (contacts.isEmpty()) {
                Text(
                    "Aucun contact. Vérifiez quelqu'un en personne ou écrivez à un appareil du radar.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            contacts.forEach { c ->
                LigneConversation(c, onClick = { onContact(c.shortId) })
            }
            if (contacts.isNotEmpty()) {
                Text(
                    "Touchez un contact pour le renommer ou le vérifier en personne.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Bloc("Réseau") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).background(if (reseauOk) Color(0xFF2E9E47) else Color(0xFFC25A14), CircleShape))
                Text(
                    when {
                        bluetooth == false -> "Bluetooth éteint : allumez-le pour relayer"
                        reseauOk -> "Réseau actif · ${pairs.size} appareil${if (pairs.size > 1) "s" else ""} à portée"
                        else -> "Réseau incomplet : voir le diagnostic"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            OutlinedButton(
                onClick = onDiagnostic,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp)
            ) { Text("Diagnostic détaillé") }
        }

        Bloc("Batterie") {
            val choix by viewModel.choixBatterie.collectAsState()
            val niveau by viewModel.batteryLevel.collectAsState()
            val mode by viewModel.batteryMode.collectAsState()
            Text(
                "Batterie à $niveau % · mode ${when (mode) { "CRITICAL" -> "économie maximale"; "LOW" -> "économie"; else -> "normal" }}",
                style = MaterialTheme.typography.bodyLarge
            )
            listOf(
                Triple("AUTO", "Automatique", "Économie à 30 %, économie maximale à 15 %"),
                Triple("ECONOMIE", "Économie", "Scan et annonce plus espacés : appareils découverts un peu moins vite"),
                Triple("ECONOMIE_MAX", "Économie maximale", "Pour durer des jours : relais limité, découverte lente"),
            ).forEach { (code, titre, detail) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(
                            selected = choix == code,
                            onClick = { viewModel.choisirBatterie(code) },
                            role = androidx.compose.ui.semantics.Role.RadioButton
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(selected = choix == code, onClick = null)
                    Column {
                        Text(titre, style = MaterialTheme.typography.bodyLarge)
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Bloc("Relais en arrière-plan") {
            // Relu régulièrement : l'utilisateur revient des réglages du téléphone
            val libre by produceState(com.relay.app.ArrierePlan.estLibre(contexte)) {
                while (true) { value = com.relay.app.ArrierePlan.estLibre(contexte); kotlinx.coroutines.delay(1_500) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).background(if (libre) Color(0xFF2E9E47) else Color(0xFFC25A14), CircleShape))
                Text(
                    if (libre) "Relay peut relayer écran éteint" else "L'économie de batterie peut couper Relay",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            if (!libre) {
                Button(
                    onClick = { com.relay.app.ArrierePlan.demanderLiberte(contexte) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp)
                ) { Text("Autoriser l'arrière-plan") }
            }
            Text(
                "Sur certains téléphones (Infinix, Tecno, Xiaomi…), activez aussi « Démarrage automatique » dans la fiche de l'application.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
            OutlinedButton(
                onClick = { com.relay.app.ArrierePlan.ouvrirFicheApplication(contexte) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(48.dp)
            ) { Text("Ouvrir la fiche de Relay") }
        }

        Bloc("Notifications") {
            Text(
                "Messages et alertes ont chacun leur canal : vous pouvez régler le son et l'affichage séparément.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    contexte.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, contexte.packageName)
                    )
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp)
            ) { Text("Régler les notifications") }
        }

        Bloc("À propos") {
            Info("Version", version)
            Text(
                "Relay est une messagerie sans internet, chiffrée de bout en bout avec le protocole Signal. Conçue par Àtagbà, à Cotonou.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp)
            )
            Text(
                "Code ouvert : github.com/atagbacom/relay",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clickable {
                        contexte.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/atagbacom/relay")))
                    }
                    .padding(vertical = 6.dp)
            )
            Text(
                "Composants : libsignal (AGPLv3), IBM Plex (SIL OFL 1.1), ZXing (Apache 2.0), ML Kit de Google (lecture des codes QR), AndroidX et Jetpack Compose (Apache 2.0).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Bloc(titre: String, contenu: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(titre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            contenu()
        }
    }
}

@Composable
private fun Info(nom: String, valeur: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(nom, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(valeur.ifEmpty { "…" }, style = MaterialTheme.typography.bodyMedium, fontFamily = PlexMono)
    }
}
