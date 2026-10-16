package com.relay.app.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un type d'alerte : les trois de base, plus ceux que l'utilisateur crée. */
data class TypeAlerte(val nom: String, val detail: String, val personnalise: Boolean = false)

/** Types d'alerte, enregistrés sur le téléphone. */
class TypesAlerte(context: Context) {
    private val prefs = context.getSharedPreferences("relay_reglages", Context.MODE_PRIVATE)

    fun tous(): List<TypeAlerte> = BASE + personnalises()

    fun ajouter(nom: String, detail: String) {
        val propre = nom.trim().take(30)
        if (propre.isEmpty() || tous().any { it.nom.equals(propre, ignoreCase = true) }) return
        enregistrer(personnalises() + TypeAlerte(propre, detail.trim().take(60), personnalise = true))
    }

    fun supprimer(nom: String) {
        enregistrer(personnalises().filterNot { it.nom == nom })
    }

    private fun personnalises(): List<TypeAlerte> = runCatching {
        val a = JSONArray(prefs.getString(CLE, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            TypeAlerte(o.getString("nom"), o.optString("detail"), personnalise = true)
        }
    }.getOrDefault(emptyList())

    private fun enregistrer(liste: List<TypeAlerte>) {
        val a = JSONArray()
        liste.forEach { a.put(JSONObject().put("nom", it.nom).put("detail", it.detail)) }
        prefs.edit().putString(CLE, a.toString()).apply()
    }

    companion object {
        private const val CLE = "types_alerte"
        val BASE = listOf(
            TypeAlerte("Besoin médical", "Blessé ou malade, besoin d'aide rapide"),
            TypeAlerte("Danger", "Prévenir les autres d'un risque"),
            TypeAlerte("Je suis en sécurité", "Rassurer ses proches et l'équipe"),
        )
    }
}
