package com.haloscreen.companion

import kotlinx.serialization.Serializable

@Serializable
data class Position(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
)

@Serializable
data class RadarContact(
    val id: String,
    val x: Float, // -1.0 .. 1.0, relative to player, radar-space
    val y: Float,
    val kind: String // "enemy" | "ally" | "objective"
)

@Serializable
data class GameState(
    val health: Float = 100f,
    val shields: Float = 100f,
    val ammo_current: Int = 0,
    val ammo_reserve: Int = 0,
    val weapon: String = "",
    val heading_deg: Float = 0f,
    val position: Position = Position(),
    val radar_contacts: List<RadarContact> = emptyList(),
    val objective: String = "",
    val timestamp: Double = 0.0
)

/** Connection lifecycle, surfaced to the UI so it can show status/errors. */
sealed class ConnectionStatus {
    data object Disconnected : ConnectionStatus()
    data object Connecting : ConnectionStatus()
    data object Connected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}
