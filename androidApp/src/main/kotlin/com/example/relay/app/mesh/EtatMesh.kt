package com.relay.app.mesh

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * État vivant du réseau, partagé entre le service mesh et l'interface.
 *
 * Les deux tournent dans le même processus : l'interface lit directement
 * cet état, au lieu de dépendre de broadcasts envoyés seulement quand
 * quelque chose change (le radar restait vide à la réouverture).
 *
 * Sert aussi au diagnostic : sans câble ni adb sur le terrain, c'est le
 * seul moyen de savoir pourquoi deux téléphones ne se voient pas.
 */
object EtatMesh {

    data class PairVu(
        val shortId: String,
        val adresse: String,
        val rssi: Int,
        val vuA: Long,
    )

    /** null : pas encore connu (service pas démarré). */
    val bluetoothActif = MutableStateFlow<Boolean?>(null)
    val annonce = MutableStateFlow("pas démarrée")
    val scan = MutableStateFlow("pas démarré")
    val serveur = MutableStateFlow("pas démarré")
    val identifiant = MutableStateFlow("")

    private val _pairs = MutableStateFlow<List<PairVu>>(emptyList())
    val pairs: StateFlow<List<PairVu>> = _pairs

    /** Appareils vus avec l'UUID Relay, y compris ceux dont l'identifiant est illisible. */
    val annoncesRecues = MutableStateFlow(0)

    private val _journal = MutableStateFlow<List<String>>(emptyList())
    val journal: StateFlow<List<String>> = _journal

    private val heure = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    fun majPairs(liste: List<PairVu>) {
        _pairs.value = liste.sortedByDescending { it.rssi }
    }

    /** Ajoute une ligne au journal de diagnostic (200 dernières gardées). */
    fun noter(message: String) {
        Log.i("RelayMesh", message)
        _journal.update { (it + "${heure.format(Date())}  $message").takeLast(200) }
    }

    /** Traduction des codes d'échec d'annonce Bluetooth (AdvertiseCallback). */
    fun echecAnnonce(code: Int): String = when (code) {
        1 -> "données trop grandes"
        2 -> "trop d'annonces en cours sur le téléphone"
        3 -> "déjà démarrée"
        4 -> "erreur interne du Bluetooth"
        5 -> "fonction non prise en charge par ce téléphone"
        else -> "code $code"
    }

    /** Traduction des codes d'échec de scan (ScanCallback). */
    fun echecScan(code: Int): String = when (code) {
        1 -> "déjà démarré"
        2 -> "application non enregistrée (relancer le Bluetooth)"
        3 -> "erreur interne du Bluetooth"
        4 -> "fonction non prise en charge"
        5 -> "ressources matérielles insuffisantes"
        6 -> "scans trop fréquents, réessayer dans 30 s"
        else -> "code $code"
    }
}
