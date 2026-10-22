package com.relay.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.app.ui.components.Icones
import com.relay.app.ui.theme.RelayPalette
import kotlinx.coroutines.launch

/**
 * Mode urgence : un seul geste, maintenir le bouton 2 secondes, et l'alerte
 * part à tous les contacts. Le maintien évite les envois par erreur (poche,
 * main qui tremble) ; un lecteur d'écran l'envoie par une action directe.
 */
@Composable
fun UrgenceScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val types by viewModel.types.collectAsState()
    val contacts by viewModel.contacts.collectAsState(emptyList())
    var type by remember { mutableStateOf("Danger") }
    var suivi by remember { mutableStateOf<SuiviAlerte?>(null) }
    val progres = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val vibreur = LocalHapticFeedback.current
    val possible = contacts.isNotEmpty() && suivi == null

    val envoyer = {
        if (possible) {
            vibreur.performHapticFeedback(HapticFeedbackType.LongPress)
            suivi = viewModel.diffuserAlerte(type)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RelayPalette.BleuNuitProfond)
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icones.Retour, contentDescription = "Retour", tint = RelayPalette.Or)
            }
            Text("Urgence", style = MaterialTheme.typography.titleLarge, color = RelayPalette.Blanc)
        }

        Spacer(Modifier.height(12.dp))
        // Type d'alerte : « Danger » par défaut, changeable d'un toucher
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            types.take(3).forEach { t ->
                val choisi = t.nom == type
                FilterChip(
                    selected = choisi,
                    onClick = { if (suivi == null) type = t.nom },
                    // Sur deux lignes : « Besoin médical », « Je suis en sécurité » étaient coupés
                    label = {
                        Text(
                            t.nom, maxLines = 2, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth()
                        )
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = RelayPalette.Or,
                        selectedLabelColor = RelayPalette.Encre,
                        labelColor = RelayPalette.BleuCiel
                    )
                )
            }
        }

        Spacer(Modifier.weight(1f))

        val s = suivi
        if (s == null) {
            Box(
                Modifier
                    .size(240.dp)
                    .clip(CircleShape)
                    .background(if (possible) RelayPalette.Or else RelayPalette.BleuNuitClair)
                    .semantics {
                        contentDescription = "Maintenir pour envoyer l'alerte $type à tous vos contacts"
                        onClick(label = "Envoyer l'alerte") { envoyer(); possible }
                    }
                    .pointerInput(type, possible) {
                        detectTapGestures(onPress = {
                            if (!possible) return@detectTapGestures
                            val maintien = scope.launch {
                                progres.snapTo(0f)
                                progres.animateTo(1f, tween(2_000, easing = LinearEasing))
                                envoyer()
                            }
                            tryAwaitRelease()
                            if (progres.value < 1f) {
                                maintien.cancel()
                                scope.launch { progres.animateTo(0f, tween(200)) }
                            }
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                    drawArc(
                        color = RelayPalette.BleuNuitProfond,
                        startAngle = -90f, sweepAngle = 360f * progres.value, useCenter = false,
                        style = Stroke(10.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(type, color = RelayPalette.Encre, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text("Maintenir 2 s", color = RelayPalette.Encre, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                if (contacts.isEmpty()) "Aucun contact : l'alerte n'aurait personne à prévenir. Ajoutez d'abord un contact."
                else "L'alerte part, chiffrée, à vos ${contacts.size} contact${if (contacts.size > 1) "s" else ""}, de téléphone en téléphone.",
                color = RelayPalette.BleuCiel, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium
            )
        } else {
            val remises by viewModel.remiseAlerte(s).collectAsState(emptyMap())
            val recus = remises.values.count { it }
            Box(
                Modifier.size(160.dp).clip(CircleShape).background(RelayPalette.Remis),
                contentAlignment = Alignment.Center
            ) { Text("Envoyée", color = RelayPalette.Encre, fontSize = 24.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(20.dp))
            Text(
                "« $type » remise à $recus contact${if (recus > 1) "s" else ""} sur ${s.destinataires.size}",
                color = RelayPalette.Blanc, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center
            )
            Text(
                "Les autres la recevront dès qu'un téléphone pourra la leur porter.",
                color = RelayPalette.BleuCiel, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
            Button(
                onClick = onBack,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RelayPalette.Or, contentColor = RelayPalette.Encre),
                modifier = Modifier.padding(top = 24.dp).heightIn(min = 48.dp)
            ) { Text("Terminé") }
        }
        Spacer(Modifier.weight(1f))
    }
}
