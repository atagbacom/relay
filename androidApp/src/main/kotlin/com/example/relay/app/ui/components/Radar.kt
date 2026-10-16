package com.relay.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.app.ui.theme.PlexMono
import com.relay.app.ui.theme.PlexSans
import com.relay.app.ui.theme.RelayPalette
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Un appareil sur le radar. */
data class NoeudRadar(
    val id: String,
    val texte: String,
    val description: String,
    val confiance: Confiance,
    /** À portée Bluetooth directe ; sinon joignable par un relais. */
    val direct: Boolean,
    /** Identifiant d'appareil (police à chasse fixe) plutôt que des initiales. */
    val identifiant: Boolean = false,
    /** Pour un appareil relayé : l'appareil par lequel il est joignable. */
    val viaId: String? = null,
)

/**
 * Radar des appareils joignables, sur fond bleu nuit.
 * Ondes or émises par ce téléphone, balayage, liens vers les appareils à
 * portée (plein) et relayés (pointillés), paquet lumineux qui suit un chemin.
 */
@Composable
fun RadarReseau(
    noeuds: List<NoeudRadar>,
    selection: String?,
    onSelection: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anime = animationsActivees()
    val transition = rememberInfiniteTransition(label = "radar")
    val onde by if (anime) transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "onde"
    ) else remember { mutableFloatStateOf(0.45f) }
    val balayage by if (anime) transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "balayage"
    ) else remember { mutableFloatStateOf(-1f) }
    val trajet by if (anime) transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing)), label = "trajet"
    ) else remember { mutableFloatStateOf(-1f) }
    val flux by if (anime) transition.animateFloat(
        0f, -24f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "flux"
    ) else remember { mutableFloatStateOf(0f) }
    val souffle by if (anime) transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "souffle"
    ) else remember { mutableFloatStateOf(1f) }

    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val tailleDp = minOf(maxWidth, maxHeight)
        val positions = remember(noeuds) { positionner(noeuds) }

        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            val ciel = RelayPalette.BleuCiel
            val or = RelayPalette.Or

            // Cercles de portée
            listOf(1f to 0.14f, 0.667f to 0.12f, 0.333f to 0.10f).forEach { (k, a) ->
                drawCircle(ciel.copy(alpha = a), radius = r * k, center = centre, style = Stroke(1.dp.toPx()))
            }

            // Balayage
            if (balayage >= 0f) {
                rotate(balayage, centre) {
                    drawCircle(
                        Brush.sweepGradient(
                            0f to Color.Transparent,
                            0.125f to or.copy(alpha = 0.16f),
                            0.128f to Color.Transparent,
                            1f to Color.Transparent,
                            center = centre
                        ),
                        radius = r, center = centre
                    )
                }
            }

            // Ondes émises par ce téléphone
            repeat(3) { i ->
                val p = (onde + i / 3f) % 1f
                drawCircle(
                    or.copy(alpha = (0.95f * (1f - p)).coerceIn(0f, 1f)),
                    radius = r * (0.12f + 0.88f * p), center = centre,
                    style = Stroke(1.5.dp.toPx())
                )
            }

            fun point(id: String?): Offset =
                positions[id]?.let { Offset(centre.x + it.x * r, centre.y + it.y * r) } ?: centre

            // Liens
            noeuds.forEach { n ->
                if (n.direct) {
                    drawLine(or.copy(alpha = 0.75f), centre, point(n.id), strokeWidth = 1.5.dp.toPx())
                } else {
                    drawLine(
                        ciel.copy(alpha = 0.7f), point(n.viaId), point(n.id),
                        strokeWidth = 1.3.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()), flux.dp.toPx())
                    )
                }
            }

            // Paquet en transit sur le premier chemin relayé (ou direct)
            val chemin = noeuds.firstOrNull { !it.direct }?.let { listOf(centre, point(it.viaId), point(it.id)) }
                ?: noeuds.firstOrNull()?.let { listOf(centre, point(it.id)) }
            if (chemin != null && trajet >= 0f) {
                val pos = surLigne(chemin, trajet)
                val visible = when { trajet < 0.08f -> trajet / 0.08f; trajet > 0.92f -> (1f - trajet) / 0.08f; else -> 1f }
                drawCircle(or.copy(alpha = 0.35f * visible), radius = 9.dp.toPx(), center = pos)
                drawCircle(or.copy(alpha = visible), radius = 4.dp.toPx(), center = pos)
            }

            // Ce téléphone
            drawCircle(or.copy(alpha = 0.55f * (1f - souffle)), radius = (11f + 12f * souffle).dp.toPx(), center = centre)
            drawCircle(RelayPalette.BleuNuitProfond, radius = 14.dp.toPx(), center = centre)
            drawCircle(or, radius = 11.dp.toPx(), center = centre)
        }

        // Appareils : vrais boutons, accessibles au lecteur d'écran
        noeuds.forEachIndexed { index, n ->
            val pos = positions[n.id] ?: return@forEachIndexed
            val apparition = remember(n.id) { Animatable(if (anime) 0f else 1f) }
            LaunchedEffect(n.id) {
                if (anime) {
                    delay(200L + index * 250L)
                    apparition.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
                }
            }
            val choisi = selection == n.id
            val c = n.confiance.couleurs
            val tailleNoeud = if (n.direct) 38.dp else 32.dp
            val demi = tailleDp / 2
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            ((demi + demi * pos.x) - 24.dp).roundToPx(),
                            ((demi + demi * pos.y) - 24.dp).roundToPx()
                        )
                    }
                    .size(48.dp)
                    .scale(apparition.value * if (choisi) 1.12f else 1f)
                    .semantics {
                        contentDescription = n.description
                        selected = choisi
                    }
                    .clickable(role = Role.Button) { onSelection(if (choisi) null else n.id) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(tailleNoeud)
                        .then(if (choisi) Modifier.border(2.dp, RelayPalette.Or, CircleShape) else Modifier)
                        .background(if (n.direct) c.fond else RelayPalette.BleuNuit, CircleShape)
                        .then(if (!n.direct) Modifier.border(1.5.dp, c.trait.copy(alpha = 0.85f), CircleShape) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        n.texte,
                        color = if (n.direct) c.texte else RelayPalette.BleuBrume,
                        fontFamily = if (n.identifiant) PlexMono else PlexSans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (n.identifiant) 9.5.sp else 12.5.sp,
                    )
                }
            }
        }
    }
}

/** Position stable de chaque appareil, en coordonnées -1..1 autour du centre. */
private fun positionner(noeuds: List<NoeudRadar>): Map<String, Offset> {
    val res = mutableMapOf<String, Offset>()
    noeuds.filter { it.direct }.forEach { n ->
        res[n.id] = polaire(angleDe(n.id), 0.6f)
    }
    noeuds.filter { !it.direct }.forEach { n ->
        // Un appareil relayé se place plus loin, du côté de son relais
        val base = n.viaId?.let { v -> noeuds.firstOrNull { it.id == v } }?.let { angleDe(it.id) } ?: angleDe(n.id)
        val decalage = ((n.id.hashCode() and 0xFF) / 255f - 0.5f) * 0.9f
        res[n.id] = polaire(base + decalage, 0.88f)
    }
    return res
}

private fun angleDe(id: String): Float = ((id.hashCode() and 0x7FFFFFFF) % 360) * (Math.PI.toFloat() / 180f)

private fun polaire(angle: Float, rayon: Float) = Offset(cos(angle) * rayon, sin(angle) * rayon)

/** Point à la fraction [t] (0..1) d'une ligne brisée. */
private fun surLigne(points: List<Offset>, t: Float): Offset {
    val longueurs = points.zipWithNext { a, b -> (b - a).let { sqrt(it.x * it.x + it.y * it.y) } }
    val total = longueurs.sum().takeIf { it > 0f } ?: return points.first()
    var reste = t.coerceIn(0f, 1f) * total
    points.zipWithNext().forEachIndexed { i, (a, b) ->
        if (reste <= longueurs[i]) return a + (b - a) * (reste / longueurs[i])
        reste -= longueurs[i]
    }
    return points.last()
}
