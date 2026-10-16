package com.relay.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.relay.app.ui.components.Icones
import com.relay.app.ui.theme.RelayPalette

/**
 * Premier lancement : pourquoi Relay a besoin de ces autorisations, en mots
 * simples. Sans le Bluetooth, le réseau ne peut pas démarrer.
 *
 * @param refusDefinitif l'utilisateur a refusé et Android ne reposera plus
 * la question : il faut passer par les réglages du téléphone.
 */
@Composable
fun AccueilScreen(refusDefinitif: Boolean, onAutoriser: () -> Unit) {
    val contexte = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(RelayPalette.BleuNuitProfond)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp)
    ) {
        LogoRelay(Modifier.size(56.dp))
        Text(
            "Bienvenue sur Relay",
            style = MaterialTheme.typography.headlineMedium,
            color = RelayPalette.Blanc,
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            "Vos messages passent de téléphone en téléphone, sans internet ni réseau mobile. Pour cela, Relay a besoin de quelques autorisations.",
            style = MaterialTheme.typography.bodyLarge,
            color = RelayPalette.BleuCiel,
            modifier = Modifier.padding(top = 10.dp, bottom = 24.dp)
        )

        Raison(
            Icones.Reseau, "Appareils à proximité (Bluetooth)",
            "Pour trouver les téléphones Relay autour de vous et relayer les messages. Indispensable : sans elle, le réseau ne démarre pas.",
            indispensable = true
        )
        Raison(
            Icones.Messages, "Notifications",
            "Pour vous prévenir d'un message ou d'une alerte, même quand Relay est fermé. Le contenu n'apparaît pas sur l'écran verrouillé.",
            indispensable = false
        )
        Raison(
            Icones.CodeQr, "Caméra, plus tard",
            "Demandée seulement au moment de vérifier quelqu'un en personne, pour scanner son code.",
            indispensable = false
        )

        Text(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                "Relay n'utilise pas votre position et n'envoie rien à aucun serveur."
            else
                "Sur cette version d'Android, le Bluetooth exige aussi l'autorisation de localisation. Relay ne lit pas votre position et n'envoie rien à aucun serveur.",
            style = MaterialTheme.typography.bodySmall,
            color = RelayPalette.BleuGris,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        if (refusDefinitif) {
            Text(
                "L'autorisation Bluetooth a été refusée. Ouvrez les réglages de Relay, puis « Autorisations » et « Appareils à proximité ».",
                style = MaterialTheme.typography.bodyMedium,
                color = RelayPalette.Blanc,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RelayPalette.AlerteRouge.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
            Button(
                onClick = {
                    contexte.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${contexte.packageName}"))
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = RelayPalette.Or, contentColor = RelayPalette.Encre),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(52.dp)
            ) { Text("Ouvrir les réglages", style = MaterialTheme.typography.labelLarge) }
        } else {
            Button(
                onClick = onAutoriser,
                colors = ButtonDefaults.buttonColors(containerColor = RelayPalette.Or, contentColor = RelayPalette.Encre),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Autoriser et commencer", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun Raison(icone: ImageVector, titre: String, texte: String, indispensable: Boolean) {
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(42.dp).background(RelayPalette.BleuCiel.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icone, contentDescription = null, tint = RelayPalette.Or, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(titre, style = MaterialTheme.typography.titleMedium, color = RelayPalette.Blanc)
                if (indispensable) {
                    Text(
                        "INDISPENSABLE", style = MaterialTheme.typography.labelSmall, color = RelayPalette.Encre,
                        modifier = Modifier.background(RelayPalette.Or, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(texte, style = MaterialTheme.typography.bodyMedium, color = RelayPalette.BleuCiel, textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = 3.dp))
        }
    }
}
