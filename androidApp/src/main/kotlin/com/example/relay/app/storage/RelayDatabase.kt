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
    val lastSeenAt: Long
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

// ─── DAOs ──────────────────────────────────────────────────

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun save(message: MessageEntity)

    @Query("UPDATE messages SET delivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: String)
}

// ─── Database ──────────────────────────────────────────────

@Database(
    entities = [IdentityEntity::class, ContactEntity::class, MessageEntity::class, SignalRecordEntity::class, EnvoiEntity::class],
    version = 3,
    exportSchema = false
)
abstract class RelayDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun signalRecordDao(): SignalRecordDao
    abstract fun envoiDao(): EnvoiDao

    companion object {
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}