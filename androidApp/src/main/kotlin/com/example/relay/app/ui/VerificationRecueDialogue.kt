package com.relay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.relay.app.mesh.EtatMesh
import com.relay.app.ui.components.Icones
import com.relay.app.ui.theme.RelayPalette

/**
 * S'ouvre où qu'on soit dans l'application quand quelqu'un vient de scanner
 * notre code : on compare les mots à voix haute avant de lui faire confiance.
 */
@Composable
fun VerificationRecueDialogue(viewModel: MainViewModel) {
    val recue by EtatMesh.verificationRecue.collectAsState()
    val r = recue ?: return
    val mots = viewModel.motsDeSecurite(r.cle)
    val nom = r.nom ?: "Appareil ${r.shortId.takeLast(4).uppercase()}"
    AlertDialog(
        onDismissRequest = { },
        icon = { Icon(Icones.Bouclier, contentDescription = null, tint = RelayPalette.OrProfond) },
        title = { Text("$nom vient de vous vérifier", textAlign = TextAlign.Center) },
        text = {
            Column {
                Text(
                    "Lisez ces mots à voix haute ensemble. Ils doivent être exactement les mêmes sur son téléphone.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                mots.chunked(2).forEach { paire ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        paire.forEach { mot ->
                            Text(
                                mot, style = MaterialTheme.typography.titleMedium, color = RelayPalette.Blanc,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f).background(RelayPalette.BleuNuit, RoundedCornerShape(10.dp)).padding(vertical = 9.dp)
                            )
                        }
                    }
                }
                Text(
                    "Si un seul mot diffère, refusez : quelqu'un d'autre a peut-être photographié votre code.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.confirmerVerification(r) }) { Text("Oui, ce sont les mêmes") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.refuserVerification() }) { Text("Non, refuser") }
        }
    )
}
