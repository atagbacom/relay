package com.relay.app.storage

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ─── Entités ───────────────────────────────────────────────

@Entity(tableName = "identity")
data class IdentityEntity(
    @PrimaryKey val id: Int = 0,  // Une seule ligne — notre identité
    val serializedKeyPair: ByteArray,
    val shortId: String
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val shortId: String,
    val publicKeyBytes: ByteArray,
    val trustLevel: String,       // "GREEN" | "YELLOW" | "ORANGE"
    val alias: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** Nom que le contact s'est donné, reçu signé avec ses clés (v5). */
    val nomAnnonce: String? = null,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val contactShortId: String,   // Avec qui
    val direction: String,        // "OUT" | "IN"
    val plaintext: String,        // Déchiffré — stocké localement seulement
    val trustLevelAtSend: String,
    val timestamp: Long,
    val delivered: Boolean = false
)

/**
 * État du protocole Signal (sessions, clés pré-signées), sérialisé.
 * kind : "session" | "prekey" | "signed_prekey" ; recordKey : identifiant dans ce type.
 */
@Entity(tableName = "signal_records", primaryKeys = ["kind", "recordKey"])
data class SignalRecordEntity(
    val kind: String,
    val recordKey: String,
    val data: ByteArray
)

/**
 * Un message envoyé, pas encore accusé : le paquet chiffré est gardé tel quel
 * et renvoyé tant que l'accusé n'arrive pas (le chiffrement ne peut pas être
 * refait, le ratchet a avancé).
 */
@Entity(tableName = "envois")
data class EnvoiEntity(
    @PrimaryKey val id: String,
    val destinataire: String,
    val paquet: ByteArray,
    val creeA: Long,
    val tentatives: Int = 0,
    val dernierEssai: Long = 0,
)

/**
 * Paquet d'un autre appareil gardé pour son destinataire (relais) : survit à
 * l'arrêt de l'application ou du Bluetooth, jusqu'à livraison ou expiration.
 */
@Entity(tableName = "relais")
data class RelaisEntity(
    @PrimaryKey val id: String,
    val paquet: ByteArray,
    val expireA: Long,
)

// ─── DAOs ──────────────────────────────────────────────────

@Dao
interface RelaisDao {
    @Query("SELECT * FROM relais WHERE expireA > :maintenant")
    suspend fun valides(maintenant: Long): List<RelaisEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun garder(r: RelaisEntity)

    @Query("DELETE FROM relais WHERE id = :id")
    suspend fun retirer(id: String)

    @Query("DELETE FROM relais WHERE expireA <= :maintenant")
    suspend fun purger(maintenant: Long)
}

@Dao
interface EnvoiDao {
    @Query("SELECT * FROM envois ORDER BY creeA")
    suspend fun tous(): List<EnvoiEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enregistrer(envoi: EnvoiEntity)

    @Query("UPDATE envois SET tentatives = tentatives + 1, dernierEssai = :quand WHERE id = :id")
    suspend fun noterEssai(id: String, quand: Long)

    @Query("DELETE FROM envois WHERE id = :id")
    suspend fun supprimer(id: String)

    @Query("DELETE FROM envois WHERE creeA < :avant")
    suspend fun purger(avant: Long)

    @Query("SELECT * FROM envois WHERE destinataire = :destinataire")
    suspend fun pour(destinataire: String): List<EnvoiEntity>

    @Query("DELETE FROM envois WHERE destinataire = :destinataire")
    suspend fun supprimerPour(destinataire: String)
}

/** Appels synchrones : le protocole Signal les fait depuis Dispatchers.IO. */
@Dao
interface SignalRecordDao {
    @Query("SELECT * FROM signal_records")
    fun getAll(): List<SignalRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(record: SignalRecordEntity)

    @Query("DELETE FROM signal_records WHERE kind = :kind AND recordKey = :recordKey")
    fun delete(kind: String, recordKey: String)
}

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identity WHERE id = 0")
    suspend fun get(): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(identity: IdentityEntity)

    /** N'écrase jamais une identité existante. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(identity: IdentityEntity): Long
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE shortId = :shortId")
    suspend fun get(shortId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(contact: ContactEntity)

    @Query("UPDATE contacts SET lastSeenAt = :ts WHERE shortId = :shortId")
    suspend fun updateLastSeen(shortId: String, ts: Long)

    @Query("UPDATE contacts SET trustLevel = :level WHERE shortId = :shortId")
    suspend fun upgradeTrust(shortId: String, level: String)

    @Query("DELETE FROM contacts WHERE shortId = :shortId")
    suspend fun supprimer(shortId: String)
}

@Dao
interface MessageDao {
    @Query("""
        SELECT * FROM messages 
        WHERE contactShortId = :contactShortId 
        ORDER BY timestamp ASC
    """)
    fun observeConversation(contactShortId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<MessageEntity?>

    /** Le dernier message de chaque conversation, pour l'aperçu dans la liste. */
    @Query("""
        SELECT m.* FROM messages m
        JOIN (SELECT contactShortId, MAX(timestamp) AS t FROM messages GROUP BY contactShortId) d
          ON m.contactShortId = d.contactShortId AND m.timestamp = d.t
    """)
    fun observeDerniers(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun save(message: MessageEntity)

    @Query("UPDATE messages SET delivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: String)

    /** Regroupement de deux contacts : l'historique de l'ancien passe au nouveau. */
    @Query("UPDATE messages SET contactShortId = :nouveau WHERE contactShortId = :ancien")
    suspend fun reassigner(ancien: String, nouveau: String)

    @Query("DELETE FROM messages WHERE contactShortId = :contactShortId")
    suspend fun supprimerPour(contactShortId: String)

    /** Contacts qui ont des messages envoyés pas encore accusés. */
    @Query("SELECT DISTINCT contactShortId FROM messages WHERE direction = 'OUT' AND delivered = 0")
    suspend fun contactsAvecNonRemis(): List<String>

    /** Messages envoyés à ce contact et pas encore accusés, du plus ancien au plus récent. */
    @Query("SELECT * FROM messages WHERE contactShortId = :contactShortId AND direction = 'OUT' AND delivered = 0 ORDER BY timestamp ASC")
    suspend fun nonRemis(contactShortId: String): List<MessageEntity>
}

// ─── Database ──────────────────────────────────────────────

@Database(
    entities = [IdentityEntity::class, ContactEntity::class, MessageEntity::class, SignalRecordEntity::class, EnvoiEntity::class, RelaisEntity::class],
    version = 6,
    exportSchema = false
)
abstract class RelayDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun signalRecordDao(): SignalRecordDao
    abstract fun envoiDao(): EnvoiDao
    abstract fun relaisDao(): RelaisDao

    companion object {
        /**
         * v6 : identifiant de 64 bits tiré d'un hachage de la clé (issue #12).
         * Chaque identifiant est recalculé depuis la clé déjà stockée : contacts,
         * messages, sessions Signal et notre propre identité. Les paquets déjà
         * chiffrés (envois, copies relayées) portent les anciens identifiants :
         * ils sont jetés, les messages non remis seront rechiffrés.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val renommage = mutableMapOf<String, String>()
                db.query("SELECT shortId, publicKeyBytes FROM contacts").use { c ->
                    while (c.moveToNext()) renommage[c.getString(0)] = com.relay.app.crypto.RelayIdentity.idDeCle(c.getBlob(1))
                }
                renommage.forEach { (ancien, nouveau) ->
                    db.execSQL("UPDATE contacts SET shortId = ? WHERE shortId = ?", arrayOf(nouveau, ancien))
                    db.execSQL("UPDATE messages SET contactShortId = ? WHERE contactShortId = ?", arrayOf(nouveau, ancien))
                    db.execSQL(
                        "UPDATE signal_records SET recordKey = ? WHERE kind = 'session' AND recordKey = ?",
                        arrayOf("$nouveau:1", "$ancien:1")
                    )
                }
                db.query("SELECT serializedKeyPair FROM identity WHERE id = 0").use { c ->
                    if (c.moveToFirst()) {
                        val id = com.relay.app.crypto.RelayIdentity.shortId(org.signal.libsignal.protocol.IdentityKeyPair(c.getBlob(0)))
                        db.execSQL("UPDATE identity SET shortId = ? WHERE id = 0", arrayOf(id))
                    }
                }
                db.execSQL("DELETE FROM envois")
                db.execSQL("DELETE FROM relais")
            }
        }

        /** v5 : nom annoncé par le contact, distinct du nom donné à la main. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `nomAnnonce` TEXT")
                // Jusqu'ici le nom reçu par QR était rangé comme un nom donné à la
                // main : il devient le nom annoncé, que le contact pourra mettre à jour
                db.execSQL("UPDATE `contacts` SET `nomAnnonce` = `alias`, `alias` = NULL WHERE `alias` IS NOT NULL")
            }
        }

        /** v4 : table relais (paquets d'autres appareils gardés pour leur destinataire). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `relais` (" +
                        "`id` TEXT NOT NULL, `paquet` BLOB NOT NULL, `expireA` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** v3 : table envois (messages non accusés, renvoyés automatiquement). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `envois` (" +
                        "`id` TEXT NOT NULL, `destinataire` TEXT NOT NULL, `paquet` BLOB NOT NULL, " +
                        "`creeA` INTEGER NOT NULL, `tentatives` INTEGER NOT NULL, `dernierEssai` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** v2 : table signal_records (état Signal persistant). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `signal_records` (" +
                        "`kind` TEXT NOT NULL, `recordKey` TEXT NOT NULL, `data` BLOB NOT NULL, " +
                        "PRIMARY KEY(`kind`, `recordKey`))"
                )
            }
        }

        @Volatile private var INSTANCE: RelayDatabase? = null

        fun getInstance(context: android.content.Context): RelayDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    RelayDatabase::class.java,
                    "relay.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { INSTANCE = it }
            }
        }
    }
}