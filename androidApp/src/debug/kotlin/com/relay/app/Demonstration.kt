package com.relay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import com.relay.app.crypto.RelayIdentity
import com.relay.app.crypto.TrustLevel
import com.relay.app.mesh.EtatMesh
import com.relay.app.model.RelayContact
import com.relay.app.repository.RelayRepository
import com.relay.app.storage.MessageEntity
import com.relay.app.ui.MainViewModel
import com.relay.app.ui.theme.RelayTheme

/**
 * APK de test seulement : remplit Relay d'un petit réseau d'exemple et ouvre
 * l'écran demandé, pour les captures d'écran automatiques de la CI.
 *
 *   adb shell am start -n com.atagba.relay/com.relay.app.Demonstration --es ecran reseau
 *
 * Écrans : reseau, messages, conversation, fiche, verifier, urgence, reglages.
 * Aucune donnée réelle : contacts et messages inventés, clés générées.
 */
class Demonstration : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val ecran = intent.getStringExtra("ecran") ?: "reseau"
        setContent {
            RelayTheme {
                var koffi by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) { koffi = remplir() }
                val k = koffi ?: return@RelayTheme
                RelayApp(
                    viewModel,
                    ecranInitial = when (ecran) {
                        "messages" -> Screen.Messages
                        "conversation" -> Screen.Conversation(k)
                        "fiche" -> Screen.Fiche(k)
                        "verifier" -> Screen.Qr
                        "urgence" -> Screen.Urgence
                        "reglages" -> Screen.Reglages
                        else -> Screen.Reseau
                    }
                )
            }
        }
    }

    /** Contacts, messages et appareils à portée d'exemple. Retourne l'identifiant de Koffi. */
    private suspend fun remplir(): String {
        // Une seule fois : chaque écran photographié relance l'activité
        val prefs = getSharedPreferences("demonstration", MODE_PRIVATE)
        prefs.getString("koffi", null)?.let { pairsAPortee(it, prefs); return it }
        val reglages = Reglages(this)
        reglages.monNom = "Ama"
        reglages.arrierePlanPropose = true
        val repo = RelayRepository(this)
        repo.getOrCreateIdentity()
        val maintenant = System.currentTimeMillis()
        val minute = 60_000L

        fun contact(nom: String?, confiance: TrustLevel): RelayContact {
            val cle = RelayIdentity.generate().publicKey
            return RelayContact(RelayIdentity.shortIdOf(cle), cle, confiance, nomAnnonce = nom)
        }
        val koffi = contact("Koffi", TrustLevel.GREEN)
        val aicha = contact("Aïcha", TrustLevel.YELLOW)
        val inconnu = contact(null, TrustLevel.ORANGE)
        listOf(koffi, aicha, inconnu).forEach { repo.saveContact(it) }

        var n = 0
        suspend fun message(avec: RelayContact, sens: String, texte: String, ilYa: Long, remis: Boolean = true) =
            repo.saveMessage(
                MessageEntity("demo-${n++}", avec.shortId, sens, texte, avec.trustLevel.name, maintenant - ilYa * minute, remis)
            )
        message(koffi, "IN", "Le pont de Godomey est fermé, on passe par Calavi.", 42)
        message(koffi, "OUT", "Bien reçu. On se retrouve au marché à 16 h ?", 40)
        message(koffi, "IN", "Oui. Le réseau est coupé ici depuis ce matin.", 38)
        message(koffi, "OUT", "Relay passe quand même : je t'envoie la liste des familles.", 12)
        message(koffi, "OUT", "Familles du quartier B : 14 personnes, dont 3 enfants.", 2, remis = false)
        message(aicha, "IN", "ALERTE · Je suis en sécurité", 95)
        message(aicha, "OUT", "Merci, bien noté.", 90)

        prefs.edit().putString("koffi", koffi.shortId).putString("aicha", aicha.shortId)
            .putString("inconnu", inconnu.shortId).commit()
        pairsAPortee(koffi.shortId, prefs)
        return koffi.shortId
    }

    /** Appareils à portée et état du réseau (en mémoire : à refaire à chaque lancement). */
    private fun pairsAPortee(koffi: String, prefs: android.content.SharedPreferences) {
        val maintenant = System.currentTimeMillis()
        EtatMesh.majPairs(listOfNotNull(
            EtatMesh.PairVu(koffi, "AA:00:00:00:00:01", -58, maintenant),
            prefs.getString("aicha", null)?.let { EtatMesh.PairVu(it, "AA:00:00:00:00:02", -71, maintenant) },
            prefs.getString("inconnu", null)?.let { EtatMesh.PairVu(it, "AA:00:00:00:00:03", -84, maintenant) },
        ))
        EtatMesh.batterie.value = 76
        EtatMesh.bluetoothActif.value = true
        EtatMesh.annonce.value = "active"
        EtatMesh.scan.value = "actif"
    }
}
