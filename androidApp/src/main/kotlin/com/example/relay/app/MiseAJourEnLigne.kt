package com.relay.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Mise à jour par internet, quand il y en a : la dernière version publiée
 * sur GitHub Releases. La vérification est une petite requête, une fois par
 * jour au plus ; le téléchargement (≈30 Mo) n'a lieu qu'à la demande de
 * l'utilisateur, pour ménager son forfait. L'APK est vérifié par son
 * empreinte SHA-256 publiée, puis Android contrôle la signature.
 *
 * Vie privée : github.com voit l'adresse IP du téléphone, rien d'autre.
 */
object MiseAJourEnLigne {

    private const val DEPOT = "atagbacom/relay"

    data class Disponible(val version: String, val url: String, val nomFichier: String, val taille: Long, val sha256: String)

    /** Vrai si [candidate] (0.4.0) est plus récente que [actuelle] (0.3.2). */
    fun plusRecente(candidate: String, actuelle: String): Boolean {
        fun parties(v: String) = v.removePrefix("v").substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val a = parties(candidate); val b = parties(actuelle)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** L'APK adapté au téléphone : son architecture, sinon l'universel. */
    fun choisirFichier(noms: List<String>, abis: List<String>): String? {
        val parArchi = abis.firstNotNullOfOrNull { abi -> noms.firstOrNull { it.endsWith("-$abi.apk") } }
        return parArchi ?: noms.firstOrNull { it.endsWith(".apk") && !it.contains("-arm") }
    }

    /** L'empreinte d'un fichier dans un SHA256SUMS (« empreinte  nom »). */
    fun empreinteDans(sommes: String, nomFichier: String): String? =
        sommes.lineSequence().map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == nomFichier }?.get(0)?.lowercase()

    fun internetDisponible(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** La dernière version publiée si elle est plus récente que celle installée, sinon null. */
    suspend fun verifier(context: Context): Disponible? = withContext(Dispatchers.IO) {
        val versions = JSONArray(lire("https://api.github.com/repos/$DEPOT/releases?per_page=5"))
        val derniere = (0 until versions.length()).map { versions.getJSONObject(it) }
            // Seules les versions publiées (tags vX.Y.Z), pas les APK de test essai-pr-N
            .firstOrNull { !it.optBoolean("draft") && it.getString("tag_name").matches(Regex("v\\d+\\.\\d+\\.\\d+.*")) }
            ?: return@withContext null
        val version = derniere.getString("tag_name").removePrefix("v")
        if (!plusRecente(version, VersionRelay.nom(context))) return@withContext null
        val fichiers = derniere.getJSONArray("assets").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val nom = choisirFichier(fichiers.map { it.getString("name") }, Build.SUPPORTED_ABIS.toList()) ?: return@withContext null
        val apk = fichiers.first { it.getString("name") == nom }
        val sommes = fichiers.firstOrNull { it.getString("name") == "SHA256SUMS.txt" } ?: return@withContext null
        val sha = empreinteDans(lire(sommes.getString("browser_download_url")), nom) ?: return@withContext null
        Disponible(version, apk.getString("browser_download_url"), nom, apk.getLong("size"), sha)
    }

    /** Télécharge et vérifie l'empreinte. Null si le fichier ne correspond pas. */
    suspend fun telecharger(context: Context, d: Disponible, progres: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        val dossier = File(context.cacheDir, "maj").apply { mkdirs() }
        dossier.listFiles()?.forEach { it.delete() }
        val fichier = File(dossier, d.nomFichier)
        val condensat = MessageDigest.getInstance("SHA-256")
        val connexion = ouvrir(d.url)
        try {
            connexion.inputStream.use { entree ->
                fichier.outputStream().use { sortie ->
                    val tampon = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = entree.read(tampon)
                        if (n < 0) break
                        sortie.write(tampon, 0, n)
                        condensat.update(tampon, 0, n)
                        total += n
                        if (d.taille > 0) progres((total.toFloat() / d.taille).coerceAtMost(1f))
                    }
                }
            }
        } finally {
            connexion.disconnect()
        }
        val obtenu = condensat.digest().joinToString("") { "%02x".format(it) }
        if (obtenu != d.sha256) { fichier.delete(); null } else fichier
    }

    /** Ouvre l'installeur d'Android ; la première fois, l'autorisation d'installer. */
    fun installer(context: Context, fichier: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.partage", fichier)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun ouvrir(adresse: String): HttpURLConnection =
        (URL(adresse).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Relay-Android")
        }

    private fun lire(adresse: String): String {
        val c = ouvrir(adresse)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
