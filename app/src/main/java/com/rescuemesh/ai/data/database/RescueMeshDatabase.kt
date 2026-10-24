package com.rescuemesh.ai.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.rescuemesh.ai.data.dao.*
import com.rescuemesh.ai.data.entity.*

@Database(
    entities = [
        MissionEntity::class,
        ObservationEntity::class,
        MapNodeEntity::class,
        MapEdgeEntity::class,
        PeerEntity::class,
        OutboxEntity::class,
        DeliveryReceiptEntity::class,
        MetricsEventEntity::class,
        ChatMessageEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class RescueMeshDatabase : RoomDatabase() {

    abstract fun observationDao(): ObservationDao
    abstract fun mapDao(): MapDao
    abstract fun peerDao(): PeerDao
    abstract fun outboxDao(): OutboxDao
    abstract fun metricsDao(): MetricsDao
    abstract fun missionDao(): MissionDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: RescueMeshDatabase? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS missions (
                        missionId TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        pairingCode TEXT NOT NULL,
                        description TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE observations ADD COLUMN evidenceHash TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE outbox ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) {
                    // In case retryCount is already present in certain schemas
                }
                db.execSQL("ALTER TABLE outbox ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        messageId TEXT NOT NULL,
                        missionId TEXT NOT NULL,
                        senderId TEXT NOT NULL,
                        senderName TEXT NOT NULL,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        ackPeerCount INTEGER NOT NULL,
                        isLocal INTEGER NOT NULL,
                        senderRole TEXT NOT NULL,
                        linkedEventId TEXT,
                        authorSequence INTEGER NOT NULL,
                        relayHopsRemaining INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_chat_messages_messageId ON chat_messages(messageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_missionId ON chat_messages(missionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp ON chat_messages(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_status ON chat_messages(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_linkedEventId ON chat_messages(linkedEventId)")
            }
        }

        fun getInstance(context: Context): RescueMeshDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RescueMeshDatabase::class.java,
                    "rescuemesh_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
