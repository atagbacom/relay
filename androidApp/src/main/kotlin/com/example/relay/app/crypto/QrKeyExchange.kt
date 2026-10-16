package com.relay.app.crypto

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.signal.libsignal.protocol.IdentityKeyPair
import org.json.JSONObject
import android.util.Base64

/**
 * Échange de clé publique par QR code — niveau GREEN.
 *
 * Le QR code contient :
 * - Le shortId de l'utilisateur
 * - Sa clé publique Ed25519 encodée en Base64
 * - Un timestamp pour détecter les QR expirés
 *
 * Quand Alice scanne le QR de Bob :
 * 1. Elle extrait sa clé publique
 * 2. Elle la vérifie (format valide)
 * 3. Elle enregistre Bob en niveau GREEN
 * 4. Bob fait pareil en scannant le QR d'Alice
 *
 * Résultat : confiance maximale, échange direct,
 * usurpation cryptographiquement impossible.
 */
object QrKeyExchange {

    /**
     * Génère le contenu JSON du QR code.
     */
    fun generatePayload(identity: IdentityKeyPair, shortId: String): String {
        val pubKeyBytes = identity.publicKey.serialize()
        return JSONObject().apply {
            put("v", 1)  // version du format
            put("id", shortId)
            put("pk", Base64.encodeToString(pubKeyBytes, Base64.NO_WRAP))
            put("ts", System.currentTimeMillis())
        }.toString()
    }

    /**
     * Génère un Bitmap QR code à partir du payload.
     *
     * @param size Taille en pixels — 512 pour l'écran, 256 pour miniature
     */
    fun generateBitmap(payload: String, size: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = writer.encode(payload, BarcodeFormat.QR_CODE, size, size, hints)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(
                    x, y,
                    if (matrix[x, y]) Color.BLACK else Color.WHITE
                )
            }
        }
        return bitmap
    }

    /**
     * Parse et valide un QR scanné.
     * Retourne null si le format est invalide ou expiré.
     */
    fun parsePayload(json: String): QrContact? {
        return try {
            val obj = JSONObject(json)

            // Vérifie la version
            if (obj.getInt("v") != 1) return null

            val shortId = obj.getString("id")
            val pkBytes = Base64.decode(obj.getString("pk"), Base64.NO_WRAP)
            val timestamp = obj.getLong("ts")

            // QR expiré après 5 minutes — évite le replay de vieux QR
            val age = System.currentTimeMillis() - timestamp
            if (age > 5 * 60 * 1000) return null

            // Valide le format de la clé publique
            val pubKey = org.signal.libsignal.protocol.IdentityKey(pkBytes, 0)

            // La clé doit correspondre à l'identifiant affiché, sinon on
            // enregistrerait la clé de n'importe qui sous le nom d'un autre
            if (RelayIdentity.shortIdOf(pubKey) != shortId) return null

            QrContact(shortId = shortId, publicKey = pubKey)
        } catch (e: Exception) {
            null  // Format invalide — on ignore silencieusement
        }
    }

    data class QrContact(
        val shortId: String,
        val publicKey: org.signal.libsignal.protocol.IdentityKey
    )
}