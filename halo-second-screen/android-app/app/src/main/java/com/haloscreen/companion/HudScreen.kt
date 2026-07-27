package com.haloscreen.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HudScreen(viewModel: GameStateViewModel) {
    val state by viewModel.gameState.collectAsState()
    val status by viewModel.status.collectAsState()

    var serverAddress by remember { mutableStateOf("192.168.1.100:8765") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            ConnectionBar(
                status = status,
                serverAddress = serverAddress,
                onAddressChange = { serverAddress = it },
                onConnect = { viewModel.connect("ws://$serverAddress") },
                onDisconnect = { viewModel.disconnect() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    StatusBar(label = "SHIELD", value = state.shields, color = Color(0xFF33B5FF))
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusBar(label = "HEALTH", value = state.health, color = Color(0xFF4CD964))
                    Spacer(modifier = Modifier.height(16.dp))
                    AmmoCounter(current = state.ammo_current, reserve = state.ammo_reserve, weapon = state.weapon)
                    Spacer(modifier = Modifier.height(16.dp))
                    ObjectiveText(state.objective)
                }

                Spacer(modifier = Modifier.width(16.dp))

                RadarView(
                    heading = state.heading_deg,
                    contacts = state.radar_contacts,
                    modifier = Modifier.size(180.dp)
                )
            }
        }
    }
}

@Composable
private fun ConnectionBar(
    status: ConnectionStatus,
    serverAddress: String,
    onAddressChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val (label, color) = when (status) {
        is ConnectionStatus.Connected -> "CONNECTED" to Color(0xFF4CD964)
        is ConnectionStatus.Connecting -> "CONNECTING..." to Color(0xFFFFC107)
        is ConnectionStatus.Error -> "ERROR: ${status.message}" to Color(0xFFFF3B30)
        ConnectionStatus.Disconnected -> "DISCONNECTED" to Color.Gray
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = serverAddress,
                onValueChange = onAddressChange,
                label = { Text("PC address:port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (status is ConnectionStatus.Connected || status is ConnectionStatus.Connecting) {
                Button(onClick = onDisconnect) { Text("Disconnect") }
            } else {
                Button(onClick = onConnect) { Text("Connect") }
            }
        }
        Text(text = label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusBar(label: String, value: Float, color: Color) {
    Column {
        Text(text = "$label  ${value.toInt()}", color = Color.White, fontSize = 14.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(Color.DarkGray, RoundedCornerShape(4.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (value / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(color, RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
private fun AmmoCounter(current: Int, reserve: Int, weapon: String) {
    Column {
        Text(text = weapon.ifBlank { "—" }, color = Color.LightGray, fontSize = 12.sp)
        Text(
            text = "$current / $reserve",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ObjectiveText(objective: String) {
    Column {
        Text(text = "OBJECTIVE", color = Color.Gray, fontSize = 11.sp)
        Text(text = objective.ifBlank { "—" }, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun RadarView(heading: Float, contacts: List<RadarContact>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color(0xFF0A1A0A))) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = Color(0xFF1F5C1F), radius = radius, center = center, style = Stroke(width = 2f))
        drawCircle(color = Color(0xFF1F5C1F), radius = radius * 0.5f, center = center, style = Stroke(width = 1f))

        // Player heading indicator (triangle-ish tick at top, rotated by heading)
        val headingRad = Math.toRadians(heading.toDouble() - 90.0)
        val tickEnd = Offset(
            center.x + (radius * cos(headingRad)).toFloat(),
            center.y + (radius * sin(headingRad)).toFloat()
        )
        drawLine(color = Color(0xFF4CD964), start = center, end = tickEnd, strokeWidth = 3f)

        contacts.forEach { contact ->
            val px = center.x + contact.x * radius
            val py = center.y + contact.y * radius
            val color = when (contact.kind) {
                "enemy" -> Color(0xFFFF3B30)
                "ally" -> Color(0xFF33B5FF)
                "objective" -> Color(0xFFFFC107)
                else -> Color.White
            }
            drawCircle(color = color, radius = 6f, center = Offset(px, py))
        }
    }
}
