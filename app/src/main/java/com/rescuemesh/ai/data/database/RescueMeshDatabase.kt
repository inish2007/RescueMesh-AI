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
        MetricsEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class RescueMeshDatabase : RoomDatabase() {

    abstract fun observationDao(): ObservationDao
    abstract fun mapDao(): MapDao
    abstract fun peerDao(): PeerDao
    abstract fun outboxDao(): OutboxDao
    abstract fun metricsDao(): MetricsDao

    companion object {
        @Volatile
        private var INSTANCE: RescueMeshDatabase? = null

        fun getInstance(context: Context): RescueMeshDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RescueMeshDatabase::class.java,
                    "rescuemesh_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
