package com.rescuemesh.ai.evidence

import androidx.sqlite.db.SupportSQLiteDatabase
import com.rescuemesh.ai.data.database.RescueMeshDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** SQL contract regression only; this does not open SQLite or validate a Room upgrade. */
class MissionMigrationContractTest {
    @Test
    fun migrationFromVersionOneCreatesMissionColumnsRequiredByRoom() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            check(method.name == "execSQL") { "Unexpected database operation: ${method.name}" }
            statements.add(args!![0] as String)
            null
        } as SupportSQLiteDatabase

        RescueMeshDatabase.MIGRATION_1_2.migrate(database)

        val createMissions = statements.single {
            it.contains("CREATE TABLE IF NOT EXISTS missions", ignoreCase = true)
        }
        val columns = createMissions.substringAfter('(').substringBeforeLast(')')
            .split(',')
            .associate { definition ->
                val parts = definition.trim().split(Regex("\\s+"), limit = 2)
                parts[0] to parts[1]
            }
        assertEquals(
            "An upgraded missions table must match MissionEntity, including description",
            mapOf(
                "missionId" to "TEXT PRIMARY KEY NOT NULL",
                "name" to "TEXT NOT NULL",
                "pairingCode" to "TEXT NOT NULL",
                "description" to "TEXT NOT NULL",
                "isActive" to "INTEGER NOT NULL",
                "createdAt" to "INTEGER NOT NULL"
            ),
            columns
        )
    }
}
