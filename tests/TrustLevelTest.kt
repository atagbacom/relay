package com.relay.app.crypto

import com.relay.app.model.PacketType
import com.relay.app.model.RelayContact
import org.junit.Assert.*
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress

class TrustLevelTest {

    @Test
    fun `on monte le niveau jamais on descend`() {
        val level = TrustLevel.ORANGE
        assertEquals(TrustLevel.YELLOW, level.upgradeTo(TrustLevel.YELLOW))
        assertEquals(TrustLevel.GREEN, level.upgradeTo(TrustLevel.GREEN))
        // Tentative de descente — reste au niveau actuel
        assertEquals(TrustLevel.GREEN, TrustLevel.GREEN.upgradeTo(TrustLevel.ORANGE))
        assertEquals(TrustLevel.GREEN, TrustLevel.GREEN.upgradeTo(TrustLevel.YELLOW))
    }
}

class PreKeyGossipTest {

    @Test
    fun `bundle signé est vérifiable par les pairs`() {
        // Bob génère son identité et son bundle
        val bobIdentity = RelayIdentity.generate()
        val bobSession = RelaySession(bobIdentity, 1)
        val bobGossip = PreKeyGossip(bobIdentity)
        val bobBundle = bobSession.createPreKeyBundle()
        val bobShortId = RelayIdentity.shortId(bobIdentity)

        // Bob crée un paquet gossip
        val gossipPacket = bobGossip.createGossipPacket(bobShortId, bobBundle)

        // Alice reçoit le paquet et vérifie la signature
        val aliceGossip = PreKeyGossip(RelayIdentity.generate())
        val extractedBundle = aliceGossip.verifyAndExtract(gossipPacket)

        assertNotNull("Le bundle doit être extrait avec succès", extractedBundle)
        assertEquals(PacketType.PREKEY_GOSSIP, gossipPacket.type)
    }

    @Test
    fun `bundle falsifié est rejeté`() {
        val bobIdentity = RelayIdentity.generate()
        val bobSession = RelaySession(bobIdentity, 1)
        val bobGossip = PreKeyGossip(bobIdentity)
        val bobBundle = bobSession.createPreKeyBundle()

        val gossipPacket = bobGossip.createGossipPacket(
            RelayIdentity.shortId(bobIdentity), bobBundle
        )

        // Un attaquant modifie le payload après la signature
        val tamperedPacket = gossipPacket.copy(
            payload = "TAMPERED".toByteArray()
        )

        val aliceGossip = PreKeyGossip(RelayIdentity.generate())
        val result = aliceGossip.verifyAndExtract(tamperedPacket)

        // Doit être rejeté — signature invalide sur payload falsifié
        assertNull("Le bundle falsifié doit être rejeté", result)
    }
}

class DeferredSessionTest {

    @Test
    fun `paquet différé livré quand destinataire réapparaît`() {
        val aliceIdentity = RelayIdentity.generate()
        val aliceDeferred = DeferredSession(aliceIdentity)
        val aliceShortId = RelayIdentity.shortId(aliceIdentity)

        val bobIdentity = RelayIdentity.generate()
        val bobShortId = RelayIdentity.shortId(bobIdentity)

        // Alice envoie un message à Bob absent
        val message = "Contacte-moi dès que tu peux"
        val packet = aliceDeferred.createDeferredPacket(
            aliceShortId, bobShortId, message
        )

        // Bob réapparaît et reçoit le paquet
        val bobDeferred = DeferredSession(bobIdentity)
        val recovered = bobDeferred.verifyDeferredPacket(
            packet, aliceIdentity.publicKey
        )

        assertEquals(message, recovered)
    }

    @Test
    fun `nonce à usage unique bloque le replay`() {
        val aliceIdentity = RelayIdentity.generate()
        val aliceDeferred = DeferredSession(aliceIdentity)
        val bobIdentity = RelayIdentity.generate()
        val bobDeferred = DeferredSession(bobIdentity)

        val packet = aliceDeferred.createDeferredPacket(
            RelayIdentity.shortId(aliceIdentity),
            RelayIdentity.shortId(bobIdentity),
            "message unique"
        )

        // Première livraison — réussit
        val first = bobDeferred.verifyDeferredPacket(packet, aliceIdentity.publicKey)
        assertNotNull(first)

        // Deuxième livraison du même paquet — rejetée
        val second = bobDeferred.verifyDeferredPacket(packet, aliceIdentity.publicKey)
        assertNull("Le replay doit être bloqué par le nonce", second)
    }

    @Test
    fun `paquet expiré est rejeté`() {
        val aliceIdentity = RelayIdentity.generate()
        val aliceDeferred = DeferredSession(aliceIdentity)
        val bobIdentity = RelayIdentity.generate()
        val bobDeferred = DeferredSession(bobIdentity)

        // Crée un paquet déjà expiré
        val packet = aliceDeferred.createDeferredPacket(
            RelayIdentity.shortId(aliceIdentity),
            RelayIdentity.shortId(bobIdentity),
            "message expiré"
        ).copy(expiresAt = System.currentTimeMillis() - 1000)

        val result = bobDeferred.verifyDeferredPacket(packet, aliceIdentity.publicKey)
        assertNull("Le paquet expiré doit être rejeté", result)
    }
}