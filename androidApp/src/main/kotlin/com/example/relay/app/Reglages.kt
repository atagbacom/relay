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

    /** Chercher les mises à jour sur internet quand il y en a (github.com voit l'adresse IP). */
    var majEnLigne: Boolean
        get() = prefs.getBoolean("maj_en_ligne", true)
        set(v) = prefs.edit().putBoolean("maj_en_ligne", v).apply()

    /** Dernière vérification en ligne, pour n'en faire qu'une par jour. */
    var derniereVerifMaj: Long
        get() = prefs.getLong("derniere_verif_maj", 0L)
        set(v) = prefs.edit().putLong("derniere_verif_maj", v).apply()

    /** Version déjà signalée par notification, pour ne pas la répéter. */
    var majSignalee: String
        get() = prefs.getString("maj_signalee", "").orEmpty()
        set(v) = prefs.edit().putString("maj_signalee", v).apply()

    /** La demande « relais en arrière-plan » a déjà été montrée une fois. */
    var arrierePlanPropose: Boolean
        get() = prefs.getBoolean("arriere_plan_propose", false)
        set(v) = prefs.edit().putBoolean("arriere_plan_propose", v).apply()
}
