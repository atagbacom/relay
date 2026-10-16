package com.relay.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icônes au trait, reprises du prototype (grille 24 × 24, trait arrondi).
 * Dessinées ici plutôt qu'avec material-icons-extended : quelques Ko au
 * lieu de plusieurs Mo dans l'APK. La couleur vient du `tint` de Icon().
 */
object Icones {

    private fun trait(nom: String, epaisseur: Float = 1.9f, vararg chemins: String): ImageVector =
        ImageVector.Builder(nom, 24.dp, 24.dp, 24f, 24f).apply {
            chemins.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = epaisseur,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun plein(nom: String, vararg chemins: String): ImageVector =
        ImageVector.Builder(nom, 24.dp, 24.dp, 24f, 24f).apply {
            chemins.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    val Bouclier = trait("bouclier", 2.1f,
        "M12 3l8 3v6c0 4.5-3.4 8.2-8 9-4.6-0.8-8-4.5-8-9V6z",
        "M8.5 12l2.5 2.5 4.5-5")
    val Coche = trait("coche", 2.4f, "M5 12.5l4.5 4.5L19 7.5")
    val DoubleCoche = trait("double_coche", 2.4f, "M3.5 12.5l4 4L15 9", "M10 16.5l1 1L20.5 8")
    val Attention = trait("attention", 2.1f,
        "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z",
        "M12 7.5v5.5", "M12 16.5v0.5")
    val Reseau = trait("reseau", 1.9f,
        "M12 5.5a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13z",
        "M12 2.5a9.5 9.5 0 0 1 9.5 9.5")
    val Messages = trait("messages", 1.9f, "M4 5h16v11H9l-5 4z")
    val CodeQr = trait("code_qr", 1.9f,
        "M3.5 3.5h6.5v6.5H3.5z", "M14 3.5h6.5v6.5H14z", "M3.5 14h6.5v6.5H3.5z",
        "M14 14h2.5v2.5H14z", "M18 18h2.5v2.5H18z", "M14 18.5v2", "M18.5 14h2")
    val Alerte = trait("alerte", 1.9f, "M12 3.5l9.5 16.5h-19z", "M12 10v4.5", "M12 17.5v0.5")
    val Retour = trait("retour", 2f, "M15 5l-7 7 7 7")
    val Envoyer = trait("envoyer", 2.2f, "M4 12l16-8-6 16-2.5-6.5z", "M11.5 13.5L20 4")
    val Cadenas = trait("cadenas", 1.9f,
        "M4.5 10.5h15v10h-15z", "M8 10.5V7.5a4 4 0 0 1 8 0v3")
    val Horloge = trait("horloge", 2.2f,
        "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17z", "M12 7.5v5l3 2")
    val Batterie = trait("batterie", 1.8f, "M2.5 7h17v10h-17z", "M22 10.5v3")
    val PointPlein = plein("point", "M12 9.5a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z")
}
