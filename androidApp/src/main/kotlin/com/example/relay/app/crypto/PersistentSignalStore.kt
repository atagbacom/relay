package com.relay.app.crypto

import com.relay.app.storage.SignalRecordDao
import com.relay.app.storage.SignalRecordEntity
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/**
 * Le store Signal unique de l'appareil.
 *
 * Il garde tout en mémoire (comme InMemorySignalProtocolStore) et recopie
 * chaque session et chaque clé pré-signée dans Room : elles survivent au
 * redémarrage du téléphone, et le même store sert à publier les clés et à
 * déchiffrer.
 *
 * Les identités des correspondants ne sont pas recopiées ici : la confiance
 * est portée par la table des contacts (niveaux ORANGE, YELLOW, GREEN).
 *
 * À créer hors du thread principal : le constructeur lit la base.
 */
class PersistentSignalStore(
    identity: IdentityKeyPair,
    registrationId: Int,
    private val dao: SignalRecordDao
) : InMemorySignalProtocolStore(identity, registrationId) {

    init {
        dao.getAll().forEach { r ->
            when (r.kind) {
                SESSION -> super.storeSession(adresse(r.recordKey), SessionRecord(r.data))
                PREKEY -> super.storePreKey(r.recordKey.toInt(), PreKeyRecord(r.data))
                SIGNED_PREKEY -> super.storeSignedPreKey(r.recordKey.toInt(), SignedPreKeyRecord(r.data))
            }
        }
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        super.storeSession(address, record)
        dao.save(SignalRecordEntity(SESSION, cle(address), record.serialize()))
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        super.deleteSession(address)
        dao.delete(SESSION, cle(address))
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        super.storePreKey(preKeyId, record)
        dao.save(SignalRecordEntity(PREKEY, preKeyId.toString(), record.serialize()))
    }

    override fun removePreKey(preKeyId: Int) {
        super.removePreKey(preKeyId)
        dao.delete(PREKEY, preKeyId.toString())
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        super.storeSignedPreKey(signedPreKeyId, record)
        dao.save(SignalRecordEntity(SIGNED_PREKEY, signedPreKeyId.toString(), record.serialize()))
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        super.removeSignedPreKey(signedPreKeyId)
        dao.delete(SIGNED_PREKEY, signedPreKeyId.toString())
    }

    private companion object {
        const val SESSION = "session"
        const val PREKEY = "prekey"
        const val SIGNED_PREKEY = "signed_prekey"

        fun cle(a: SignalProtocolAddress) = "${a.name}:${a.deviceId}"

        fun adresse(cle: String): SignalProtocolAddress {
            val i = cle.lastIndexOf(':')
            return SignalProtocolAddress(cle.substring(0, i), cle.substring(i + 1).toInt())
        }
    }
}
