package com.relay.app.ui.components

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.app.ui.theme.EtatCouleurs
import com.relay.app.ui.theme.PlexMono
import com.relay.app.ui.theme.RelayTokens

/** Les trois niveaux de confiance, tels qu'affichés : toujours un texte et une icône. */
enum class Confiance(val libelle: String, val explication: String, val icone: ImageVector) {
    NON_VERIFIE(
        "Non vérifié",
        "Les messages sont chiffrés, mais rien ne prouve encore que cet appareil appartient à la personne qu'il prétend être.",
        Icones.Attention
    ),
    VERIFIE_RESEAU(
        "Vérifié par le réseau",
        "Sa clé est arrivée signée par lui, relayée par d'autres appareils. Bon niveau, mais on fait confiance au chemin.",
        Icones.Coche
    ),
    VERIFIE_PERSONNE(
        "Vérifié en personne",
        "Vous avez scanné son code face à face. Personne ne peut se faire passer pour lui.",
        Icones.Bouclier
    );

    val couleurs: EtatCouleurs
        @Composable get() = when (this) {
            NON_VERIFIE -> RelayTokens.etats.nonVerifie
            VERIFIE_RESEAU -> RelayTokens.etats.verifieReseau
            VERIFIE_PERSONNE -> RelayTokens.etats.verifiePersonne
        }

    companion object {
        /** Depuis le nom du TrustLevel (ORANGE, YELLOW, GREEN). */
        fun depuis(trustLevel: String?): Confiance = when (trustLevel) {
            "GREEN" -> VERIFIE_PERSONNE
            "YELLOW" -> VERIFIE_RESEAU
            else -> NON_VERIFIE
        }
    }
}

/** Pastille de niveau de confiance : fond, icône et texte. */
@Composable
fun BadgeConfiance(confiance: Confiance, modifier: Modifier = Modifier) {
    val c = confiance.couleurs
    Row(
        modifier = modifier
            .background(c.fond, RoundedCornerShape(50))
            .padding(start = 6.dp, end = 9.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(confiance.icone, contentDescription = null, tint = c.texte, modifier = Modifier.size(13.dp))
        Text(confiance.libelle, color = c.texte, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Avatar à initiales. Un appareil non vérifié a un contour pointillé et
 * son identifiant en police à chasse fixe, à la place d'un nom.
 */
@Composable
fun Avatar(
    texte: String,
    confiance: Confiance,
    modifier: Modifier = Modifier,
    taille: Dp = 44.dp,
    identifiant: Boolean = false,
) {
    val c = confiance.couleurs
    Box(
        modifier = modifier
            .size(taille)
            .background(c.fond, CircleShape)
            .then(if (identifiant) Modifier.border(BorderStroke(1.5.dp, c.trait), CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            texte,
            color = c.texte,
            fontWeight = if (identifiant) FontWeight.Medium else FontWeight.SemiBold,
            fontFamily = if (identifiant) PlexMono else MaterialTheme.typography.titleMedium.fontFamily,
            fontSize = if (identifiant) (taille.value * 0.27f).sp else (taille.value * 0.34f).sp,
        )
    }
}

/** Initiales d'un nom affiché, ou les 4 premiers caractères d'un identifiant. */
fun initiales(nom: String): String {
    val mots = nom.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        mots.size >= 2 -> "${mots[0].first()}${mots[1].first()}".uppercase()
        mots.size == 1 && mots[0].length > 4 && mots[0].all { it.isLetterOrDigit() } && mots[0].any { it.isDigit() } ->
            mots[0].takeLast(4).uppercase()
        mots.size == 1 -> mots[0].take(2).uppercase()
        else -> "?"
    }
}

/**
 * Les animations décoratives s'arrêtent si l'utilisateur a désactivé les
 * animations du système (Options d'accessibilité / développeur).
 */
@Composable
fun animationsActivees(): Boolean {
    val contexte = LocalContext.current
    return remember {
        Settings.Global.getFloat(contexte.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}
