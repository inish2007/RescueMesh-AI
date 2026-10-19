package com.rescuemesh.ai.model

import java.util.UUID

/**
 * Mission configuration — identifies the current operation.
 * All devices must join the same mission to exchange observations.
 */
data class MissionConfig(
    val missionId: String = UUID.randomUUID().toString().take(8),
    val name: String = "Field Mission",
    val description: String = ""
)
