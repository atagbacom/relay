package com.relay.app.repository

import android.content.Context
import android.util.Base64
import com.relay.app.crypto.*
import com.relay.app.model.RelayContact
import com.relay.app.storage.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress

/**
 * Source de vérité unique.
 * L'UI et le MeshService passent tous les deux par ici.
 * Jamais d'accès direct à la base depuis ailleurs.
 */
class RelayRepository(context: Context) {

    private val db = RelayDatabase.getInstance(context)
    private val identityDao = db.identityDao()
    private val contactDao = db.contactDao()
    private val messageDao = db.messageDao()

    // ─── Identité ────────────────────────────────────────────

    /**
     * Charge l'identité locale depuis Room.
     * Si elle n'existe pas encore, en génère une nouvelle et la persiste.
     */
    suspend fun getOrCreateIdentity(): IdentityKeyPair {
        val existing = identityDao.get()
        if (existing != null) {
            return RelayIdentity.deserialize(existing.serializedKeyPair)
        }

        val newIdentity = RelayIdentity.generate()
        identityDao.save(
            IdentityEntity(
                serializedKeyPair = RelayIdentity.serialize(newIdentity),
                shortId = RelayIdentity.shortId(newIdentity)
            )
        )
        return newIdentity
    }

    suspend fun getShortId(): String {
        return identityDao.get()?.shortId ?: "unknown"
    }

    // ─── Contacts ────────────────────────────────────────────

    fun observeContacts(): Flow<List<RelayContact>> {
        return contactDao.observeAll().map { entities ->
            entities.map { it.toModel() }
        }
    }

    suspend fun getContact(shortId: String): RelayContact? {
        return contactDao.get(shortId)?.toModel()
    }

    suspend fun saveContact(contact: RelayContact) {
        contactDao.save(contact.toEntity())
    }

    suspend fun upgradeTrust(shortId: String, level: TrustLevel) {
        val existing = contactDao.get(shortId) ?: return
        val current = TrustLevel.valueOf(existing.trustLevel)
        val upgraded = current.upgradeTo(level)
        if (upgraded != current) {
            contactDao.upgradeTrust(shortId, upgraded.name)
        }
    }

    // ─── Messages ────────────────────────────────────────────

    fun observeConversation(contactShortId: String): Flow<List<MessageEntity>> {
        return messageDao.observeConversation(contactShortId)
    }

    suspend fun saveMessage(message: MessageEntity) {
        messageDao.save(message)
        contactDao.updateLastSeen(message.contactShortId, message.timestamp)
    }

    suspend fun markDelivered(messageId: String) {
        messageDao.markDelivered(messageId)
    }

    // ─── Sessions crypto ─────────────────────────────────────

    /**
     * Le store Signal de l'appareil, persistant dans Room.
     * À appeler hors du thread principal : il lit la base à sa création.
     */
    fun signalStore(identity: IdentityKeyPair, registrationId: Int): PersistentSignalStore =
        PersistentSignalStore(identity, registrationId, db.signalRecordDao())

    // ─── Conversions ─────────────────────────────────────────

    private fun ContactEntity.toModel(): RelayContact {
        return RelayContact(
            shortId = shortId,
            publicKey = IdentityKey(publicKeyBytes, 0),
            trustLevel = TrustLevel.valueOf(trustLevel),
            alias = alias,
            firstSeenAt = firstSeenAt,
            lastSeenAt = lastSeenAt
        )
    }

    private fun RelayContact.toEntity(): ContactEntity {
        return ContactEntity(
            shortId = shortId,
            publicKeyBytes = publicKey.serialize(),
            trustLevel = trustLevel.name,
            alias = alias,
            firstSeenAt = firstSeenAt,
            lastSeenAt = lastSeenAt
        )
    }
}