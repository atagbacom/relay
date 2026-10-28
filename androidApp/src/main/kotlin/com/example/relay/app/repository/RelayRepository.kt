package com.relay.app.repository

import android.content.Context
import android.util.Base64
import com.relay.app.crypto.*
import com.relay.app.model.RelayContact
import com.relay.app.storage.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress

/**
 * Source de vérité unique.
 * L'UI et le MeshService passent tous les deux par ici.
 * Jamais d'accès direct à la base depuis ailleurs.
 */
class RelayRepository(context: Context) {

    private companion object {
        /** Commun à toutes les instances (écran et service) du processus. */
        val verrouIdentite = Mutex()
    }

    private val db = RelayDatabase.getInstance(context)
    private val identityDao = db.identityDao()
    private val contactDao = db.contactDao()
    private val messageDao = db.messageDao()
    val envois = db.envoiDao()
    val relais = db.relaisDao()

    // ─── Identité ────────────────────────────────────────────

    /**
     * Charge l'identité locale depuis Room.
     * Si elle n'existe pas encore, en génère une nouvelle et la persiste.
     */
    suspend fun getOrCreateIdentity(): IdentityKeyPair = verrouIdentite.withLock {
        // L'écran et le service la demandent en même temps au premier
        // lancement : avant, chacun en créait une et la seconde écrasait la
        // première (QR et annonce Bluetooth avec deux identités différentes)
        identityDao.get()?.let { return@withLock RelayIdentity.deserialize(it.serializedKeyPair) }
        val nouvelle = RelayIdentity.generate()
        identityDao.insertIfAbsent(
            IdentityEntity(
                serializedKeyPair = RelayIdentity.serialize(nouvelle),
                shortId = RelayIdentity.shortId(nouvelle)
            )
        )
        RelayIdentity.deserialize(identityDao.get()!!.serializedKeyPair)
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

    /**
     * Supprime un contact et tout ce qui le concerne : messages, envois en
     * attente, session de chiffrement. Sert surtout à retirer l'ancienne
     * identité de quelqu'un qui a réinstallé Relay.
     */
    suspend fun supprimerContact(shortId: String) {
        messageDao.supprimerPour(shortId)
        oublierAppareil(shortId)
    }

    /**
     * Même personne, nouvelle clé (réinstallation, nouveau téléphone) :
     * l'historique de l'ancien contact passe au nouveau, le nom donné à la
     * main aussi s'il n'en a pas. La confiance ne se transmet pas : la
     * nouvelle clé reste à vérifier.
     */
    suspend fun regrouper(ancien: String, nouveau: String) {
        if (ancien == nouveau) return
        val a = contactDao.get(ancien) ?: return
        val n = contactDao.get(nouveau) ?: return
        messageDao.reassigner(ancien, nouveau)
        if (n.alias == null && a.alias != null) contactDao.save(n.copy(alias = a.alias))
        oublierAppareil(ancien)
    }

    private suspend fun oublierAppareil(shortId: String) {
        envois.supprimerPour(shortId)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.signalRecordDao().delete("session", "$shortId:1")
        }
        contactDao.supprimer(shortId)
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

    fun observeDerniers(): Flow<List<MessageEntity>> = messageDao.observeDerniers()

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

    suspend fun nonRemis(contactShortId: String): List<MessageEntity> = messageDao.nonRemis(contactShortId)

    suspend fun contactsAvecNonRemis(): List<String> = messageDao.contactsAvecNonRemis()

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
            lastSeenAt = lastSeenAt,
            nomAnnonce = nomAnnonce
        )
    }

    private fun RelayContact.toEntity(): ContactEntity {
        return ContactEntity(
            shortId = shortId,
            publicKeyBytes = publicKey.serialize(),
            trustLevel = trustLevel.name,
            alias = alias,
            firstSeenAt = firstSeenAt,
            lastSeenAt = lastSeenAt,
            nomAnnonce = nomAnnonce
        )
    }
}