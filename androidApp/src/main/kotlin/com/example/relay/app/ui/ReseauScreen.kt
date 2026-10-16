package com.relay.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.app.ui.components.*
import com.relay.app.ui.theme.RelayPalette

/**
 * Écran d'accueil : le radar des appareils joignables, puis les conversations.
 */
@Composable
fun ReseauScreen(
    viewModel: MainViewModel,
    onOuvrirConversation: (String) -> Unit,
    onVerifier: () -> Unit,
    onReglages: () -> Unit = {},
) {
    val contacts by viewModel.contacts.collectAsState(emptyList())
    val peers by viewModel.pairsVus.collectAsState()
    val batterie by viewModel.batteryLevel.collectAsState()
    val mode by viewModel.batteryMode.collectAsState()
    var selection by remember { mutableStateOf<String?>(null) }

    val noeuds = remember(peers, contacts) {
        peers.map { pair ->
            val id = pair.shortId
            val contact = contacts.firstOrNull { it.shortId == id }
            val confiance = Confiance.depuis(contact?.trustLevel)
            val nom = contact?.displayName?.takeIf { it != id }
            NoeudRadar(
                id = id,
                texte = nom?.let { initiales(it) } ?: id.takeLast(4).uppercase(),
                description = "${nom ?: "Appareil ${id.takeLast(4).uppercase()}"}, ${procheDepuisSignal(pair.rssi)}, ${confiance.libelle.lowercase()}",
                confiance = confiance,
                direct = true,
                identifiant = nom == null,
                rssi = pair.rssi,
            )
        }
    }
    val choisi = noeuds.firstOrNull { it.id == selection }

    // Le panneau des conversations remonte à l'ouverture
    val montee = remember { Animatable(40f) }
    val anime = animationsActivees()
    LaunchedEffect(Unit) { if (anime) montee.animateTo(0f, tween(700)) else montee.snapTo(0f) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ── Haut bleu nuit : identité, batterie, radar ─────────
        Box(
            Modifier
                .fillMaxWidth()
                .background(RelayPalette.BleuNuitProfond)
        ) {
            LignesTopographiques(Modifier.matchParentSize())
            Column(Modifier.statusBarsPadding().padding(bottom = 36.dp)) {
                EnTete(batterie, mode, onReglages)
                RadarReseau(
                    noeuds = noeuds,
                    selection = selection,
                    onSelection = { selection = it },
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .size(288.dp)
                        .align(Alignment.CenterHorizontally)
                )
                AnimatedContent(
                    targetState = choisi,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "statut",
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp, start = 24.dp, end = 24.dp)
                ) { n ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when {
                                n != null -> n.description.substringBefore(",")
                                noeuds.isEmpty() -> "Aucun appareil à portée"
                                noeuds.size == 1 -> "1 appareil à portée"
                                else -> "${noeuds.size} appareils à portée"
                            },
                            color = RelayPalette.Blanc,
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                n != null -> "${n.rssi?.let { procheDepuisSignal(it).replaceFirstChar { c -> c.uppercase() } } ?: "À portée"} · ${n.confiance.libelle.lowercase()}"
                                noeuds.isEmpty() -> "Le relais reste actif : les messages partiront dès qu'un appareil passera."
                                else -> "Touchez un appareil pour lui écrire · distance indicative, d'après le signal"
                            },
                            color = RelayPalette.BleuCiel,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        if (n != null) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { onOuvrirConversation(n.id) },
                                colors = ButtonDefaults.buttonColors(containerColor = RelayPalette.Or, contentColor = RelayPalette.Encre),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("Écrire", style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
            }
        }

        // ── Conversations ──────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = (-26).dp)
                .graphicsLayer { translationY = montee.value.dp.toPx() }
                .alpha(1f - montee.value / 40f),
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            color = MaterialTheme.colorScheme.background,
            shadowElevation = 8.dp
        ) {
            Column {
                Box(
                    Modifier
                        .padding(top = 10.dp)
                        .size(width = 40.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                        .align(Alignment.CenterHorizontally)
                )
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text("Conversations", style = MaterialTheme.typography.titleMedium)
                    Text("Chiffrées de bout en bout", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (contacts.isEmpty()) {
                    EtatVide(onVerifier)
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(contacts, key = { it.shortId }) { c ->
                            LigneConversation(c, onClick = { onOuvrirConversation(c.shortId) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnTete(batterie: Int, mode: String, onReglages: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogoRelay(Modifier.size(30.dp))
            Column {
                Text("Relay", color = RelayPalette.Blanc, fontWeight = FontWeight.SemiBold, fontSize = 21.sp)
                Text("par Àtagbà", color = RelayPalette.BleuGris, style = MaterialTheme.typography.labelSmall)
            }
        }
        val libelleMode = when (mode) { "CRITICAL" -> "critique"; "LOW" -> "économie"; else -> "normal" }
        val critique = mode == "CRITICAL"
        Row(
            Modifier
                .background(
                    if (critique) RelayPalette.AlerteRouge.copy(alpha = 0.25f) else RelayPalette.BleuCiel.copy(alpha = 0.1f),
                    RoundedCornerShape(50)
                )
                .padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(Icones.Batterie, contentDescription = null, tint = RelayPalette.BleuBrume, modifier = Modifier.size(18.dp))
            Text("$batterie % · $libelleMode", color = RelayPalette.BleuBrume, style = MaterialTheme.typography.labelMedium)
        }
        IconButton(onClick = onReglages) {
            Icon(Icones.Reglages, contentDescription = "Réglages et diagnostic", tint = RelayPalette.BleuBrume)
        }
    }
}

/** Le logo : un point or et deux ondes. */
@Composable
fun LogoRelay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val u = size.minDimension / 30f
        drawCircle(RelayPalette.Or, radius = 3.4f * u, center = c)
        drawCircle(RelayPalette.Or.copy(alpha = 0.7f), radius = 8.5f * u, center = c, style = Stroke(1.6f * u))
        drawCircle(RelayPalette.Or.copy(alpha = 0.35f), radius = 13.5f * u, center = c, style = Stroke(1.2f * u))
    }
}

/** Lignes de niveau en fond, discrètes, rappel de la carte de terrain. */
@Composable
private fun LignesTopographiques(modifier: Modifier) {
    Canvas(modifier.alpha(0.5f)) {
        val w = size.width
        val h = size.height
        listOf(0.24f, 0.32f, 0.8f, 0.88f).forEachIndexed { i, y ->
            val p = Path().apply {
                moveTo(-20f, h * y)
                cubicTo(w * 0.2f, h * (y - 0.06f), w * 0.35f, h * (y + 0.06f), w * 0.55f, h * y)
                cubicTo(w * 0.75f, h * (y - 0.07f), w * 0.9f, h * (y + (if (i % 2 == 0) -0.02f else 0.03f)), w + 20f, h * (y - 0.03f))
            }
            drawPath(p, RelayPalette.BleuTopo, style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun EtatVide(onVerifier: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icones.Messages, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
        Text("Aucune conversation", style = MaterialTheme.typography.titleMedium)
        Text(
            "Les appareils à portée apparaissent sur le radar. Pour une confiance maximale, vérifiez vos contacts en personne.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        OutlinedButton(onClick = onVerifier, shape = RoundedCornerShape(14.dp), modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icones.CodeQr, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Vérifier en personne", style = MaterialTheme.typography.labelLarge)
        }
    }
}
