package com.relay.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.app.ui.components.Icones
import com.relay.app.ui.theme.PlexMono

/** Affiché au lancement qui suit un plantage : la cause, et de quoi l'envoyer. */
@Composable
fun RapportPlantageScreen(rapport: String, onContinuer: () -> Unit) {
    val contexte = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(20.dp)
    ) {
        Icon(Icones.Alerte, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
        Text("Relay s'est arrêté", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Désolé. Voici la cause enregistrée. Envoyez-la à l'équipe pour qu'elle corrige le problème : elle ne contient aucun message ni aucune clé.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
        )
        Text(
            rapport,
            fontFamily = PlexMono,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        )
        Button(
            onClick = {
                val envoi = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Relay — rapport de plantage")
                    putExtra(Intent.EXTRA_TEXT, rapport)
                }
                contexte.startActivity(Intent.createChooser(envoi, "Envoyer le rapport"))
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(52.dp)
        ) { Text("Partager le rapport", style = MaterialTheme.typography.labelLarge) }
        OutlinedButton(
            onClick = onContinuer,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(52.dp)
        ) { Text("Continuer", style = MaterialTheme.typography.labelLarge) }
    }
}
