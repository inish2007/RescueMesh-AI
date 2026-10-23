package com.rescuemesh.ai.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.File

/**
 * Core data unit: a single field observation event.
 *
 * Every observation must originate from an actual device, real-world map source,
 * or identifiable human report. Never seed this table with fabricated data.
 *
 * Append-only: observations are never deleted or modified after creation.
 * Conflicting evidence is preserved, not overwritten.
 */
@Entity(tableName = "observations")
data class ObservationEntity(
    @PrimaryKey val eventId: String,                 // UUID, globally unique
    val missionId: String,                           // FK to missions
    val authorDeviceId: String,                      // Device that created this
    val authorSequence: Long,                        // Monotonic per-device counter
    val eventType: String,                           // ROUTE_STATUS, HAZARD, NOTE, PHOTO
    val title: String,
    val description: String = "",
    val reportedStatus: String? = null,              // OPEN, BLOCKED, HAZARD
    val sourceType: String = "HUMAN_OBSERVATION",    // HUMAN_OBSERVATION, DEVICE_SENSOR
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,                     // GPS accuracy in meters
    val mapEdgeId: String? = null,                   // Optional link to map edge
    val mapNodeId: String? = null,                   // Optional link to map node
    val evidencePath: String? = null,                // Local photo file path
    val evidenceHash: String? = null,                // Cryptographic SHA-256 hex digest of evidence file
    val verificationStatus: String = "UNVERIFIED",   // UNVERIFIED, VERIFIED, CONTESTED
    val observedAtDevice: Long = System.currentTimeMillis(), // Timestamp from authoring device
    val receivedAt: Long = System.currentTimeMillis(), // When this device received it
    val isLocal: Boolean = true,                     // Created on this device?
    val relayHopsRemaining: Int = 3                  // Max further relay hops
)

/**
 * Convenience extension properties for Gate 2 photo evidence workflow.
 * Defined outside the Room entity to avoid database schema migrations.
 */
val ObservationEntity.evidenceSha256: String? get() = evidenceHash
val ObservationEntity.evidenceFilePath: String? get() = evidencePath
val ObservationEntity.evidenceSizeBytes: Long get() = evidencePath?.let { File(it).takeIf { f -> f.exists() }?.length() } ?: 0L
val ObservationEntity.evidenceMimeType: String get() = "image/jpeg"
val ObservationEntity.hasEvidence: Boolean get() = !evidenceHash.isNullOrBlank()

