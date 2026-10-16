package com.relay.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.relay.R

/*
 * Système de design de Relay (prototype validé : « instrument de terrain »).
 *
 * Cible : ONG, protection civile, équipes de terrain. L'interface doit se lire
 * en plein soleil, d'un coup d'œil, par des personnes sous pression :
 *  - thème clair par défaut, sombre selon le réglage du téléphone ;
 *  - bleu nuit pour le sérieux, or Àtagbà réservé au signal et aux actions principales ;
 *  - aucune information portée par la couleur seule (toujours texte + icône) ;
 *  - IBM Plex Sans embarquée (pas de téléchargement : l'app marche hors ligne),
 *    IBM Plex Mono pour les identifiants et empreintes.
 */

/** Couleurs de marque. Dans les écrans, passer par MaterialTheme ou RelayTokens. */
object RelayPalette {
    val BleuNuit = Color(0xFF13294B)
    val BleuNuitProfond = Color(0xFF0B1A30)
    val BleuNuitClair = Color(0xFF2D4A75)
    val BleuTopo = Color(0xFF1F3A63)
    val BleuBrume = Color(0xFFDCE5F2)
    val BleuCiel = Color(0xFFA9C2E6)
    val BleuGris = Color(0xFF8EA6C8)
    val Or = Color(0xFFF2A32F)
    val OrProfond = Color(0xFF9A5408)
    val OrPale = Color(0xFFFCEBC9)
    val OrLumiere = Color(0xFFFDC65A)
    /** Message remis, sur fond bleu nuit. */
    val Remis = Color(0xFF9EDCA3)
    val Encre = Color(0xFF14181F)
    val Papier = Color(0xFFF5F7FA)
    val Blanc = Color(0xFFFFFFFF)
    val Gris = Color(0xFF5B6472)
    val GrisClair = Color(0xFFD5DAE1)
    val GrisFond = Color(0xFFEEF1F5)
    val Nuit = Color(0xFF0E131B)
    val NuitSurface = Color(0xFF171E29)
    val NuitBord = Color(0xFF2A3442)
    val AlerteFond = Color(0xFF150B10)
    val AlerteRouge = Color(0xFFB3261E)
    val AlerteCorail = Color(0xFFFF8A65)
}

private val Clair = lightColorScheme(
    primary = RelayPalette.BleuNuit,
    onPrimary = RelayPalette.Blanc,
    primaryContainer = RelayPalette.BleuBrume,
    onPrimaryContainer = RelayPalette.BleuNuitProfond,
    secondary = RelayPalette.OrProfond,
    onSecondary = RelayPalette.Blanc,
    secondaryContainer = RelayPalette.OrPale,
    onSecondaryContainer = Color(0xFF3A2000),
    tertiary = RelayPalette.Or,
    onTertiary = RelayPalette.Encre,
    background = RelayPalette.Papier,
    onBackground = RelayPalette.Encre,
    surface = RelayPalette.Blanc,
    onSurface = RelayPalette.Encre,
    surfaceVariant = RelayPalette.GrisFond,
    onSurfaceVariant = RelayPalette.Gris,
    outline = RelayPalette.GrisClair,
    outlineVariant = Color(0xFFE6E9EE),
    error = RelayPalette.AlerteRouge,
    onError = RelayPalette.Blanc,
)

private val Sombre = darkColorScheme(
    primary = RelayPalette.BleuCiel,
    onPrimary = RelayPalette.BleuNuitProfond,
    primaryContainer = RelayPalette.BleuNuitClair,
    onPrimaryContainer = RelayPalette.BleuBrume,
    secondary = RelayPalette.Or,
    onSecondary = RelayPalette.Encre,
    secondaryContainer = Color(0xFF3A2A10),
    onSecondaryContainer = RelayPalette.OrPale,
    tertiary = RelayPalette.Or,
    onTertiary = RelayPalette.Encre,
    background = RelayPalette.Nuit,
    onBackground = Color(0xFFE7EBF0),
    surface = RelayPalette.NuitSurface,
    onSurface = Color(0xFFE7EBF0),
    surfaceVariant = Color(0xFF1F2733),
    onSurfaceVariant = Color(0xFFA7B0BD),
    outline = RelayPalette.NuitBord,
    outlineVariant = Color(0xFF222B37),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

/** Une couleur d'état : un fond (container) et un texte lisible dessus. */
@Immutable
data class EtatCouleurs(val texte: Color, val fond: Color, val trait: Color)

@Immutable
data class RelayEtats(
    val nonVerifie: EtatCouleurs,
    val verifieReseau: EtatCouleurs,
    val verifiePersonne: EtatCouleurs,
    val isole: EtatCouleurs,
)

private val EtatsClairs = RelayEtats(
    nonVerifie = EtatCouleurs(texte = Color(0xFF8A3B00), fond = Color(0xFFFFE6D2), trait = Color(0xFFC25A14)),
    verifieReseau = EtatCouleurs(texte = Color(0xFF6B4E00), fond = Color(0xFFFFF0C2), trait = Color(0xFFE6D49A)),
    verifiePersonne = EtatCouleurs(texte = Color(0xFF1B5E20), fond = Color(0xFFDDF2DF), trait = Color(0xFF2E9E47)),
    isole = EtatCouleurs(texte = RelayPalette.Gris, fond = RelayPalette.GrisFond, trait = RelayPalette.GrisClair),
)

private val EtatsSombres = RelayEtats(
    nonVerifie = EtatCouleurs(texte = Color(0xFFFFB68A), fond = Color(0xFF3D2412), trait = Color(0xFFFFB68A)),
    verifieReseau = EtatCouleurs(texte = Color(0xFFF5D57A), fond = Color(0xFF362C0E), trait = Color(0xFFF5D57A)),
    verifiePersonne = EtatCouleurs(texte = Color(0xFF9EDCA3), fond = Color(0xFF16321A), trait = Color(0xFF6CC47E)),
    isole = EtatCouleurs(texte = Color(0xFFA7B0BD), fond = Color(0xFF1F2733), trait = RelayPalette.NuitBord),
)

val LocalRelayEtats = staticCompositionLocalOf { EtatsClairs }

val PlexSans = FontFamily(
    Font(R.font.plex_sans_regular, FontWeight.Normal),
    Font(R.font.plex_sans_medium, FontWeight.Medium),
    Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plex_sans_semibold, FontWeight.Bold),
)

/** Réservée aux identifiants : empreintes de clé, identifiants courts. */
val PlexMono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)

private val RelayTypographie = Typography(
    headlineMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp),
    titleLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 16.5.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = PlexSans, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = PlexSans, fontSize = 14.5.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = PlexSans, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, lineHeight = 14.sp),
)

private val RelayFormes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun RelayTheme(
    sombre: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRelayEtats provides if (sombre) EtatsSombres else EtatsClairs) {
        MaterialTheme(
            colorScheme = if (sombre) Sombre else Clair,
            typography = RelayTypographie,
            shapes = RelayFormes,
            content = content,
        )
    }
}

object RelayTokens {
    val etats: RelayEtats
        @Composable get() = LocalRelayEtats.current

    val margeEcran = 16.dp
    val espacement = 12.dp
    /** Cible tactile minimale (doigts, gants, stress). */
    val cibleTactile = 48.dp
}

// ---------------------------------------------------------------------------
// Ancien thème « terminal » : conservé le temps de migrer les écrans un par un.

@Deprecated("Utiliser RelayTheme et MaterialTheme.colorScheme")
object RelayColors {
    val Background    = Color(0xFF0A0A0A)
    val Surface       = Color(0xFF141414)
    val SurfaceHigh   = Color(0xFF1E1E1E)
    val Border        = Color(0xFF2A2A2A)
    val BorderAccent  = Color(0xFF333333)

    val Green         = Color(0xFF00C853)
    val GreenDim      = Color(0xFF1A3D2B)
    val Yellow        = Color(0xFFFFB800)
    val YellowDim     = Color(0xFF3D2E00)
    val Orange        = Color(0xFFFF6B00)
    val OrangeDim     = Color(0xFF3D1A00)
    val Red           = Color(0xFFFF3D3D)

    val TextPrimary   = Color(0xFFEEEEEE)
    val TextSecondary = Color(0xFF888888)
    val TextMuted     = Color(0xFF444444)
}

@Deprecated("Utiliser MaterialTheme.typography, et PlexMono pour les identifiants")
object RelayType {
    val Mono = FontFamily.Monospace
}

@Deprecated("Utiliser RelayTokens et MaterialTheme.typography")
object RelayDimens {
    val PaddingS  = 8.dp
    val PaddingM  = 16.dp
    val PaddingL  = 24.dp
    val RadiusS   = 4.dp
    val RadiusM   = 8.dp
    val RadiusL   = 16.dp

    val FontXS    = 10.sp
    val FontS     = 12.sp
    val FontM     = 14.sp
    val FontL     = 16.sp
    val FontTitle = 20.sp
}
