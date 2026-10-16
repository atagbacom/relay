package com.relay.app.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * Vérification en personne avec un seul scan.
 *
 * Quand A scanne le QR de B, le téléphone de A envoie à B, par le mesh, sa
 * propre clé, signée, avec le code à usage unique (nonce) lu dans le QR de B.
 * B sait ainsi que l'envoi vient de quelqu'un qui vient de scanner SON code.
 * Les deux personnes comparent ensuite les quatre mots à voix haute : si ce
 * sont les mêmes, B enregistre A au niveau « vérifié en personne ».
 */
object VerificationMutuelle {

    /** Codes à usage unique placés dans nos QR : valables 5 minutes, une seule fois. */
    object Nonces {
        private const val VALIDITE_MS = 5 * 60 * 1000L
        private val emis = LinkedHashMap<String, Long>()
        private val aleatoire = SecureRandom()

        @Synchronized
        fun emettre(maintenant: Long = System.currentTimeMillis()): String {
            val octets = ByteArray(12).also { aleatoire.nextBytes(it) }
            val n = Base64.getUrlEncoder().withoutPadding().encodeToString(octets)
            emis[n] = maintenant
            emis.entries.removeAll { maintenant - it.value > VALIDITE_MS }
            return n
        }

        /** Vrai une seule fois, pour un code émis par ce téléphone il y a moins de 5 minutes. */
        @Synchronized
        fun consommer(n: String, maintenant: Long = System.currentTimeMillis()): Boolean {
            val quand = emis.remove(n) ?: return false
            return maintenant - quand <= VALIDITE_MS
        }
    }

    data class Recue(val shortId: String, val cle: IdentityKey, val nom: String?)

    /** Contenu du paquet envoyé par celui qui a scanné. */
    fun contenu(identite: IdentityKeyPair, nonce: String, nom: String?): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            val pk = identite.publicKey.serialize()
            out.writeShort(pk.size); out.write(pk)
            val n = nonce.toByteArray(Charsets.UTF_8)
            out.writeByte(n.size); out.write(n)
            val nm = (nom ?: "").take(40).toByteArray(Charsets.UTF_8)
            out.writeByte(nm.size); out.write(nm)
        }
        return bos.toByteArray()
    }

    fun signer(identite: IdentityKeyPair, contenu: ByteArray): ByteArray =
        Curve.calculateSignature(identite.privateKey, contenu)

    /**
     * Vérifie un paquet reçu : signature par la clé qu'il contient, clé
     * correspondant à l'identifiant de l'expéditeur, code issu de notre QR.
     */
    fun verifier(
        senderId: String,
        contenu: ByteArray,
        signature: ByteArray,
        nonceValide: (String) -> Boolean,
    ): Recue? = runCatching {
        val inp = DataInputStream(ByteArrayInputStream(contenu))
        val pk = ByteArray(inp.readUnsignedShort()).also { inp.readFully(it) }
        val nonce = String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }, Charsets.UTF_8)
        val nom = String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }, Charsets.UTF_8)
        val cle = IdentityKey(pk, 0)
        val ok = Curve.verifySignature(cle.publicKey, contenu, signature) &&
            RelayIdentity.shortIdOf(cle) == senderId &&
            nonceValide(nonce)
        if (ok) Recue(senderId, cle, nom.ifBlank { null }) else null
    }.getOrNull()
}
