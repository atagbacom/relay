package com.relay.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.relay.app.ArrierePlan
import com.relay.app.Reglages

/**
 * Proposé une seule fois : laisser Relay tourner en arrière-plan. Sans ça,
 * l'économie de batterie de nombreux téléphones coupe le relais écran éteint.
 * Reste accessible ensuite dans les réglages.
 */
@Composable
fun ArrierePlanDialogue() {
    val contexte = LocalContext.current
    val reglages = remember { Reglages(contexte) }
    var visible by remember { mutableStateOf(!reglages.arrierePlanPropose && !ArrierePlan.estLibre(contexte)) }
    if (!visible) return
    val fermer = { reglages.arrierePlanPropose = true; visible = false }
    AlertDialog(
        onDismissRequest = fermer,
        title = { Text("Relayer écran éteint") },
        text = {
            Text(
                "Relay transmet les messages des autres même quand vous ne l'utilisez pas. " +
                    "Votre téléphone risque de le couper pour économiser la batterie : autorisez-le à rester actif."
            )
        },
        confirmButton = {
            TextButton(onClick = { fermer(); ArrierePlan.demanderLiberte(contexte) }) { Text("Autoriser") }
        },
        dismissButton = { TextButton(onClick = fermer) { Text("Plus tard") } }
    )
}
