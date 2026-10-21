package com.relay.app

import android.content.Context

/** Préférences de l'utilisateur, enregistrées sur le téléphone. */
class Reglages(context: Context) {
    private val prefs = context.getSharedPreferences("relay_reglages", Context.MODE_PRIVATE)

    /**
     * Le nom que l'on choisit : transmis dans notre QR et dans la
     * vérification, pour que la personne qui nous scanne nous enregistre
     * directement sous ce nom. Vide : on reste « Appareil XXXX ».
     */
    var monNom: String
        get() = prefs.getString("mon_nom", "").orEmpty()
        set(v) = prefs.edit().putString("mon_nom", v.trim().take(40)).apply()

    /** Économie de batterie choisie : AUTO, ECONOMIE ou ECONOMIE_MAX. */
    var modeBatterie: String
        get() = prefs.getString("mode_batterie", "AUTO") ?: "AUTO"
        set(v) = prefs.edit().putString("mode_batterie", v).apply()

    /** La demande « relais en arrière-plan » a déjà été montrée une fois. */
    var arrierePlanPropose: Boolean
        get() = prefs.getBoolean("arriere_plan_propose", false)
        set(v) = prefs.edit().putBoolean("arriere_plan_propose", v).apply()
}
