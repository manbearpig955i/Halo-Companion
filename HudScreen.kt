package com.haloscreen.companion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ---------- Palette (matches the final dark-theme concept) ----------
private val Void = Color(0xFF000000)
private val PanelBg = Color(0xFF020705)
private val LineColor = Color(0xFF122120)
private val Teal = Color(0xFF3FDDB2)
private val Amber = Color(0xFFEBA323)
private val Red = Color(0xFFEA4A3A)
private val Blue = Color(0xFF4E9AD9)
private val TextBright = Color(0xFFE4EEEA)
private val TextDim = Color(0xFF5C726C)
private val Mono = FontFamily.Monospace

// ---------- Weapon roster: magazine size + accent color per weapon ----------
private data class WeaponSpec(val magSize: Int, val color: Color)
private val WEAPON_SPECS = mapOf(
    "MA5B ASSAULT RIFLE" to WeaponSpec(32, Teal),
    "M6D MAGNUM" to WeaponSpec(12, Amber),
    "SRS99 SNIPER RIFLE" to WeaponSpec(4, Blue),
    "M90 SHOTGUN" to WeaponSpec(6, Red)
)
private val DEFAULT_WEAPON_SPEC = WeaponSpec(32, Teal)
private fun weaponSpecFor(name: String) = WEAPON_SPECS[name.uppercase()] ?: DEFAULT_WEAPON_SPEC

// ---------- Geometry constants (mirror the HTML concept's angles/gaps) ----------
private const val SHIELD_GAP = 34f
private const val SHIELD_THETA_START = -100f // near top of the circle (0deg = due east)
private const val SHIELD_THETA_END = -6f     // near right of the circle

private const val AMMO_GAP = 26f
private const val AMMO_THETA_START = 18f
private const val AMMO_THETA_END = 82f

private const val DIST_GAP = 16f
private const val DIST_THETA_START = 100f
private const val DIST_THETA_END = 162f
private const val DIST_TICK_COUNT = 5

private fun circlePoint(cx: Float, cy: Float, r: Float, thetaDeg: Float): Offset {
    val t = Math.toRadians(thetaDeg.toDouble())
    return Offset(cx + r * cos(t).toFloat(), cy + r * sin(t).toFloat())
}

/** Snapshot of the radar's real on-screen circle plus the stage size, refreshed on layout. */
private data class RadarGeometry(val cx: Float, val cy: Float, val radius: Float, val stageW: Float, val stageH: Float)

@Composable
fun HudScreen(viewModel: GameStateViewModel) {
    val state by viewModel.gameState.collectAsState()
    val status by viewModel.status.collectAsState()

    var serverAddress by remember { mutableStateOf("192.168.1.100:8765") }
    var geometry by remember { mutableStateOf<RadarGeometry?>(null) }
    var selectedGrenade by remember { mutableStateOf("frag") }

    // Distance-to-nearest-contact is not yet part of the real telemetry schema, so this is
    // simulated locally as a placeholder until the PC side can supply a real value.
    var distanceMeters by remember { mutableStateOf(84) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(900)
            distanceMeters = (distanceMeters + Random.nextInt(-6, 7)).coerceAtLeast(4)
        }
    }

    val density = LocalDensity.current
    fun Float.pxToDp(): Dp = with(density) { this@pxToDp.toDp() }

    val weaponSpec = weaponSpecFor(state.weapon.ifBlank { "MA5B ASSAULT RIFLE" })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Void)
    ) {
        // ---- Connection strip: unobtrusive, sits at the very top ----
        ConnectionStrip(
            status = status,
            serverAddress = serverAddress,
            onAddressChange = { serverAddress = it },
            onConnect = { viewModel.connect("ws://$serverAddress") },
            onDisconnect = { viewModel.disconnect() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        )

        // ---- Radar + overlay layer (shield wedge / ammo arc / distance meter all key off the radar's real size) ----
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.64f)
                    .aspectRatio(1f)
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInParent()
                        val size = coords.size
                        geometry = RadarGeometry(
                            cx = pos.x + size.width / 2f,
                            cy = pos.y + size.height / 2f,
                            radius = size.width / 2f,
                            stageW = 0f, // filled in by the overlay Canvas's own size each draw
                            stageH = 0f
                        )
                    }
            ) {
                RadarView(heading = state.heading_deg, contacts = state.radar_contacts, modifier = Modifier.fillMaxSize())
            }

            // Overlay canvas draws on top of the radar: shield wedge, ammo arc, distance meter
            Canvas(modifier = Modifier.fillMaxSize()) {
                val geo = geometry ?: return@Canvas
                val cx = geo.cx; val cy = geo.cy; val r = geo.radius
                val stageW = size.width

                // ---- Shield wedge: fixed dim outline + a liquid-style fill rising bottom-to-top ----
                val shieldR = r + SHIELD_GAP
                val pTop = circlePoint(cx, cy, shieldR, SHIELD_THETA_START)
                val pRight = circlePoint(cx, cy, shieldR, SHIELD_THETA_END)
                val wedgePath = Path().apply {
                    moveTo(pTop.x, 0f)
                    lineTo(stageW, 0f)
                    lineTo(stageW, pRight.y)
                    lineTo(pRight.x, pRight.y)
                    arcTo(
                        rect = Rect(cx - shieldR, cy - shieldR, cx + shieldR, cy + shieldR),
                        startAngleDegrees = SHIELD_THETA_END,
                        sweepAngleDegrees = SHIELD_THETA_START - SHIELD_THETA_END,
                        forceMoveTo = false
                    )
                    close()
                }
                drawPath(wedgePath, color = Teal.copy(alpha = 0.07f))
                drawPath(wedgePath, color = Teal.copy(alpha = 0.18f), style = Stroke(width = 1.5f))

                val shieldPct = (state.shields / 100f).coerceIn(0f, 1f)
                val wedgeBottom = pRight.y
                val fillHeight = wedgeBottom * shieldPct
                val shieldColor = when {
                    state.shields <= 25f -> Red
                    state.shields <= 50f -> Amber
                    else -> Teal
                }
                clipPath(wedgePath) {
                    drawRect(
                        color = shieldColor,
                        topLeft = Offset(0f, wedgeBottom - fillHeight),
                        size = Size(stageW, fillHeight)
                    )
                }

                // ---- Ammo arc: small curved band hugging the bottom-right of the circle, drains with the mag ----
                val ammoR = r + AMMO_GAP
                val ammoSweepTotal = AMMO_THETA_END - AMMO_THETA_START
                drawArc(
                    color = Teal.copy(alpha = 0.16f),
                    startAngle = AMMO_THETA_START,
                    sweepAngle = ammoSweepTotal,
                    useCenter = false,
                    topLeft = Offset(cx - ammoR, cy - ammoR),
                    size = Size(ammoR * 2, ammoR * 2),
                    style = Stroke(width = 14f)
                )
                val ammoPct = (state.ammo_current.toFloat() / weaponSpec.magSize).coerceIn(0f, 1f)
                val ammoColor = when {
                    ammoPct <= 0.15f -> Red
                    ammoPct <= 0.35f -> Amber
                    else -> weaponSpec.color
                }
                drawArc(
                    color = ammoColor,
                    startAngle = AMMO_THETA_START,
                    sweepAngle = ammoSweepTotal * ammoPct,
                    useCenter = false,
                    topLeft = Offset(cx - ammoR, cy - ammoR),
                    size = Size(ammoR * 2, ammoR * 2),
                    style = Stroke(width = 14f)
                )

                // ---- Distance meter: thin arc + tick marks along the bottom-left edge of the circle ----
                val distR = r + DIST_GAP
                drawArc(
                    color = Teal.copy(alpha = 0.4f),
                    startAngle = DIST_THETA_START,
                    sweepAngle = DIST_THETA_END - DIST_THETA_START,
                    useCenter = false,
                    topLeft = Offset(cx - distR, cy - distR),
                    size = Size(distR * 2, distR * 2),
                    style = Stroke(width = 2f)
                )
                for (i in 0..DIST_TICK_COUNT) {
                    val theta = DIST_THETA_START + (DIST_THETA_END - DIST_THETA_START) * i / DIST_TICK_COUNT
                    val inner = circlePoint(cx, cy, distR - 6f, theta)
                    val outer = circlePoint(cx, cy, distR + 6f, theta)
                    drawLine(color = Teal.copy(alpha = 0.55f), start = inner, end = outer, strokeWidth = 2f)
                }
            }
        }

        // ---- Health pips: top-left, width reaches to where the shield wedge begins ----
        val healthRowWidthPx = geometry?.let { geo ->
            val shieldR = geo.radius + SHIELD_GAP
            val pTopX = circlePoint(geo.cx, geo.cy, shieldR, SHIELD_THETA_START).x
            (pTopX - 26f - 14f).coerceAtLeast(0f)
        }
        Column(modifier = Modifier.align(Alignment.TopStart).padding(top = 40.dp, start = 26.dp)) {
            HealthRow(health = state.health, widthPx = healthRowWidthPx?.pxToDp())
            Spacer(modifier = Modifier.height(4.dp))
            Text("HEALTH", color = TextDim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Text(
            "SHIELD",
            color = TextDim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 2.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 26.dp)
        )

        // ---- Clock: left-middle of the screen ----
        var clockText by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            while (true) {
                val now = java.util.Calendar.getInstance()
                val h = now.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                val m = now.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
                clockText = "$h:$m"
                delay(15000)
            }
        }
        Text(
            clockText, color = TextDim, fontFamily = Mono, fontSize = 13.sp, letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 26.dp)
        )

        // ---- Ammo readout: bare text, no box, tucked in the corner ----
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp)
        ) {
            Text(state.weapon.ifBlank { "\u2014" }, color = TextDim, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${state.ammo_current}", color = TextBright, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text("/ ${state.ammo_reserve}", color = TextDim, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }

        // ---- Bullet tally: one pip per round in the current weapon's magazine ----
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 128.dp, bottom = 18.dp, start = 82.dp)
                .fillMaxWidth()
                .height(26.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            repeat(weaponSpec.magSize) { i ->
                val lit = i < state.ammo_current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(if (lit) 1f else 0.55f)
                        .align(Alignment.Bottom)
                        .background(if (lit) weaponSpec.color else LineColor)
                )
            }
        }

        // ---- Distance label: positioned near the distance meter arc ----
        geometry?.let { geo ->
            val distR = geo.radius + DIST_GAP + 22f
            val mid = circlePoint(geo.cx, geo.cy, distR, (DIST_THETA_START + DIST_THETA_END) / 2f)
            Text(
                buildString { append(distanceMeters); append(" M") },
                color = TextDim, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp,
                modifier = Modifier.offset(x = mid.x.pxToDp() - 20.dp, y = mid.y.pxToDp() - 8.dp)
            )
        }

        // ---- Grenade selector: two tappable buttons, bottom-left ----
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 26.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GrenadeButton(
                color = Color(0xFF8FE05A),
                selected = selectedGrenade == "frag",
                shape = GrenadeShape.Round,
                onClick = { selectedGrenade = "frag" }
            )
            GrenadeButton(
                color = Color(0xFF7B6BFF),
                selected = selectedGrenade == "plasma",
                shape = GrenadeShape.Diamond,
                onClick = { selectedGrenade = "plasma" }
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 26.dp, bottom = 122.dp)
        ) {
            Text("GRENADE", color = TextDim, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 2.sp)
            Text(
                if (selectedGrenade == "frag") "FRAG" else "PLASMA",
                color = TextBright, fontFamily = Mono, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
            )
        }

        // ---- Objective ----
        Text(
            "\u203a ${state.objective.ifBlank { "\u2014" }}",
            color = TextBright, fontFamily = Mono, fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 82.dp, bottom = 52.dp)
        )
    }
}

@Composable
private fun HealthRow(health: Float, widthPx: Dp?) {
    val segCount = 6
    val color = when {
        health <= 25f -> Red
        health <= 50f -> Amber
        else -> Teal
    }
    Row(
        modifier = (widthPx?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth(0.4f))
            .height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(segCount) { i ->
            val lit = health > i * (100f / segCount)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (lit) color else PanelBg)
            )
        }
    }
}

private enum class GrenadeShape { Round, Diamond }

@Composable
private fun GrenadeButton(color: Color, selected: Boolean, shape: GrenadeShape, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(PanelBg)
            .border(width = 2.dp, color = if (selected) color else LineColor, shape = CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val iconMod = Modifier.size(14.dp).background(
            if (selected) color else color.copy(alpha = 0.35f),
            if (shape == GrenadeShape.Round) CircleShape else androidx.compose.foundation.shape.CutCornerShape(50)
        )
        Box(modifier = iconMod)
    }
}

@Composable
private fun ConnectionStrip(
    status: ConnectionStatus,
    serverAddress: String,
    onAddressChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Kept minimal and unobtrusive since the rest of the screen is the actual HUD.
    val connected = status is ConnectionStatus.Connected

    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (label, color) = when (status) {
            is ConnectionStatus.Connected -> "LINKED" to Teal
            is ConnectionStatus.Connecting -> "CONNECTING" to Amber
            is ConnectionStatus.Error -> "ERROR" to Red
            ConnectionStatus.Disconnected -> "OFFLINE" to TextDim
        }

        val infinite = rememberInfiniteTransition(label = "pulse")
        val pulseAlpha by infinite.animateFloat(
            initialValue = 1f, targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "pulseAlpha"
        )
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color.copy(alpha = if (connected) pulseAlpha else 1f), CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = color, fontFamily = Mono, fontSize = 9.sp, letterSpacing = 1.sp)

        Spacer(modifier = Modifier.weight(1f))

        if (!connected) {
            androidx.compose.material3.OutlinedTextField(
                value = serverAddress,
                onValueChange = onAddressChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 11.sp, color = TextBright),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.width(150.dp).height(40.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = if (connected) "DISCONNECT" else "CONNECT",
            color = if (connected) Red else Teal,
            fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp,
            modifier = Modifier.clickable { if (connected) onDisconnect() else onConnect() }
        )
    }
}

@Composable
private fun RadarView(heading: Float, contacts: List<RadarContact>, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "sweep")
    val sweepAngle by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "sweepAngle"
    )

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF041008), Color(0xFF020805), Color(0xFF000000)),
                center = center, radius = radius
            ),
            radius = radius, center = center
        )
        drawCircle(color = Teal.copy(alpha = 0.16f), radius = radius, center = center, style = Stroke(1f))
        drawCircle(color = Teal.copy(alpha = 0.16f), radius = radius * 0.66f, center = center, style = Stroke(1f))
        drawCircle(color = Teal.copy(alpha = 0.16f), radius = radius * 0.33f, center = center, style = Stroke(1f))
        drawLine(Teal.copy(alpha = 0.14f), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1f)
        drawLine(Teal.copy(alpha = 0.14f), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1f)

        rotate(degrees = sweepAngle, pivot = center) {
            drawArc(
                color = Teal.copy(alpha = 0.22f),
                startAngle = -90f, sweepAngle = 50f, useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2)
            )
        }

        val headingRad = Math.toRadians(heading.toDouble() - 90.0)
        drawLine(
            color = Teal, start = center,
            end = Offset(center.x + (radius * cos(headingRad)).toFloat(), center.y + (radius * sin(headingRad)).toFloat()),
            strokeWidth = 3f
        )

        contacts.forEach { c ->
            val color = when (c.kind) {
                "enemy" -> Red
                "ally" -> Blue
                "objective" -> Amber
                else -> TextBright
            }
            drawCircle(color = color, radius = 7f, center = Offset(center.x + c.x * radius * 0.9f, center.y + c.y * radius * 0.9f))
        }
    }
}
