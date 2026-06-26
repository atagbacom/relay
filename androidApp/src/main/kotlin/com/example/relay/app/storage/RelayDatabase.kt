package com.relay.app.storage

import androidx.room.*
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

// ─── DAOs ──────────────────────────────────────────────────

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identity WHERE id = 0")
    suspend fun get(): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(identity: IdentityEntity)
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
    entities = [IdentityEntity::class, ContactEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RelayDatabase : RoomDatabase() {
    abstract fun identityDao(): IdentityDao
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: RelayDatabase? = null

        fun getInstance(context: android.content.Context): RelayDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    RelayDatabase::class.java,
                    "relay.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}