package app.mami.ui.together

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.data.SharedLocation
import app.mami.data.db.MessageEntity
import app.mami.sync.MamiBackend
import app.mami.ui.Format
import app.mami.ui.theme.Mami
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Metres between two points on Earth. */
fun distanceM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.first - a.first)
    val dLng = Math.toRadians(b.second - a.second)
    val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLng / 2).let { it * it }
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

/** Compass bearing from [a] to [b], in degrees (0 is north). */
fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val lat1 = Math.toRadians(a.first)
    val lat2 = Math.toRadians(b.first)
    val dLng = Math.toRadians(b.second - a.second)
    val y = sin(dLng) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

fun distanceText(metres: Double): String = when {
    metres < 50 -> "right next to you"
    metres < 1000 -> "${(metres / 10).roundToInt() * 10} m away"
    metres < 100_000 -> "%.1f km away".format(metres / 1000)
    else -> "${(metres / 1000).roundToInt()} km away"
}

/** "Walking", "Driving · 42 km/h" from the speed, or nothing when still. */
fun movingText(speedMps: Float?): String? = when {
    speedMps == null || speedMps < 0.6f -> null
    speedMps < 2.5f -> "Walking"
    speedMps < 7f -> "Moving · ${(speedMps * 3.6f).roundToInt()} km/h"
    else -> "Driving · ${(speedMps * 3.6f).roundToInt()} km/h"
}

/** Opens a maps app at the point, or OpenStreetMap in the browser if there's none. */
fun openInMaps(context: Context, lat: Double, lng: Double, label: String) {
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})"))
    try {
        context.startActivity(geo)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lng#map=17/$lat/$lng")))
        }
    }
}

/** Live location in the chat: a little map tile, until when, and View (theirs) or Stop (mine). */
@Composable
fun LiveLocationCard(message: MessageEntity, partnerName: String, now: Long, onOpen: () -> Unit) {
    val until = message.untilMs ?: 0
    val live = message.endedAtMs == null && until > now && !message.unsent
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = if (message.fromMe) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            onClick = onOpen,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 1.dp,
            modifier = Modifier.width(260.dp),
        ) {
            Column {
                MapTile(live, Modifier.fillMaxWidth().height(96.dp))
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (message.fromMe) "You're sharing live location" else "$partnerName's live location",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            when {
                                live -> "Until ${Format.time(until)}"
                                message.endedAtMs != null -> "Stopped at ${Format.time(message.endedAtMs)}"
                                else -> "Ended at ${Format.time(until)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (live) Mami.colors.good else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (live) {
                        Text(
                            if (message.fromMe) "Manage" else "View",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** A stylised street map with a pin, drawn (no map service sees anything). */
@Composable
private fun MapTile(live: Boolean, modifier: Modifier) {
    val land = if (Mami.colors.isDark) Color(0xFF26303A) else Color(0xFFE8F0E3)
    val road = if (Mami.colors.isDark) Color(0xFF3A4652) else Color.White
    val water = if (Mami.colors.isDark) Color(0xFF1D3B52) else Color(0xFFBFE0F5)
    val pin = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val pulse by rememberInfiniteTransition(label = "pin").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "pulse")
    Box(modifier.clipToBounds().background(land), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(water, radius = size.height * 0.9f, center = Offset(size.width * 0.05f, size.height * 1.1f))
            val w = size.height * 0.11f
            drawLine(road, Offset(0f, size.height * 0.35f), Offset(size.width, size.height * 0.55f), strokeWidth = w)
            drawLine(road, Offset(size.width * 0.62f, 0f), Offset(size.width * 0.48f, size.height), strokeWidth = w)
            drawLine(road, Offset(size.width * 0.2f, 0f), Offset(size.width * 0.9f, size.height), strokeWidth = w * 0.6f)
            if (live) drawCircle(pin.copy(alpha = 0.3f * (1 - pulse)), radius = 10.dp.toPx() + 22.dp.toPx() * pulse, center = Offset(size.width / 2, size.height / 2 + 6.dp.toPx()))
        }
        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = pin, modifier = Modifier.size(34.dp).padding(bottom = 6.dp))
    }
}

private val durations = listOf("15 min" to 15 * 60_000L, "1 hour" to 60 * 60_000L, "8 hours" to 8 * 60 * 60_000L)

/**
 * The partner's live location (how far, which way, moving or not, open in
 * Maps) and my own sharing: start it for a while, or stop it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSheet(backend: MamiBackend, partnerName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val theirs by backend.partnerLocation.collectAsStateWithLifecycle()
    val myUntil by backend.myLocationUntil.collectAsStateWithLifecycle()
    val paused by backend.sharingPausedUntil.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            value = System.currentTimeMillis()
        }
    }
    val me by produceState<Pair<Double, Double>?>(null, theirs?.atMs) { value = backend.myLocation() }
    var wanted by remember { mutableLongStateOf(0L) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            backend.shareLocation(wanted)
        } else {
            Toast.makeText(context, "MaMi needs location access to share where you are.", Toast.LENGTH_LONG).show()
        }
    }
    fun share(duration: Long) {
        wanted = duration
        if (locationAllowed(context)) {
            backend.shareLocation(duration)
        } else {
            permission.launch(
                buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray(),
            )
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            LocationView(theirs, me, partnerName, now, onOpenMaps = { location -> openInMaps(context, location.lat, location.lng, partnerName) })
            Spacer(Modifier.height(20.dp))
            Text("Your location", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            val until = myUntil?.takeIf { it > now }
            when {
                (paused ?: 0) > now -> Text(
                    "Sharing is paused, so your location stays private. Resume sharing on the Together page to share it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                until != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MyLocation, contentDescription = null, tint = Mami.colors.good)
                    Text(
                        "$partnerName can see you until ${Format.time(until)}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    OutlinedButton(onClick = backend::stopSharingLocation) { Text("Stop") }
                }
                else -> {
                    Text(
                        "Let $partnerName follow along on your way home. It stops by itself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        durations.forEach { (label, ms) -> FilterChip(selected = false, onClick = { share(ms) }, label = { Text(label) }) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "🔒 End-to-end encrypted. Only $partnerName's phone can read it; MaMi's server never can.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where the partner is, relative to me: a radar with you in the middle. */
@Composable
fun LocationView(theirs: SharedLocation?, me: Pair<Double, Double>?, partnerName: String, now: Long, onOpenMaps: (SharedLocation) -> Unit) {
    val live = theirs?.live(now) == true
    Text(
        if (live) "$partnerName's live location" else "$partnerName isn't sharing their location",
        style = MaterialTheme.typography.headlineSmall,
    )
    if (theirs == null || !live) {
        Spacer(Modifier.height(4.dp))
        Text(
            if (theirs != null) "Last shared ${Format.relative(theirs.atMs, now)}." else "When $partnerName shares it, you'll see how far apart you are here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val distance = me?.let { distanceM(it, theirs.lat to theirs.lng) }
    val direction = me?.let { bearing(it, theirs.lat to theirs.lng) }
    Spacer(Modifier.height(12.dp))
    Radar(distance, direction, partnerName.take(1).uppercase(), Modifier.fillMaxWidth().aspectRatio(1.6f))
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(distance?.let(::distanceText)?.replaceFirstChar { it.uppercase() } ?: "Live now", style = MaterialTheme.typography.titleLarge)
            val moving = movingText(theirs.speedMps)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (moving != null) {
                    Icon(
                        if (moving.startsWith("Driving")) Icons.Filled.DirectionsCar else Icons.AutoMirrored.Filled.DirectionsWalk,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(end = 2.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    listOfNotNull(moving, "updated ${Format.relative(theirs.atMs, now)}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Sharing until ${Format.time(theirs.untilMs)}", style = MaterialTheme.typography.bodySmall, color = Mami.colors.good)
        }
        Button(onClick = { onOpenMaps(theirs) }) {
            Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Maps")
        }
    }
    if (me == null) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Allow MaMi to use your location to see how far apart you are.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** You in the middle, the partner as a dot in their direction; rings at 100 m, 1 km, 10 km, 100 km. */
@Composable
private fun Radar(distance: Double?, direction: Double?, initial: String, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outlineVariant
    val partnerColor = Mami.colors.gradient.last()
    val sweep by rememberInfiniteTransition(label = "radar").animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    Box(modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val max = size.height * 0.44f
            for (i in 1..4) {
                drawCircle(ring, radius = max * i / 4, center = center, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
            }
            drawCircle(primary.copy(alpha = 0.18f * (1 - sweep)), radius = max * sweep, center = center)
            if (distance != null && direction != null) {
                drawCircle(Color.White, radius = 9.dp.toPx(), center = center)
                drawCircle(primary, radius = 6.dp.toPx(), center = center)
                // Log scale: 100 m is the first ring, 100 km the last.
                val fraction = ((ln(distance.coerceIn(20.0, 200_000.0) / 10.0) / ln(10.0)) / 4.0).toFloat().coerceIn(0.12f, 1f)
                val angle = Math.toRadians(direction - 90)
                val point = Offset(center.x + (max * fraction * cos(angle)).toFloat(), center.y + (max * fraction * sin(angle)).toFloat())
                drawLine(partnerColor.copy(alpha = 0.5f), center, point, strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                drawCircle(partnerColor.copy(alpha = 0.25f), radius = 18.dp.toPx(), center = point)
                drawCircle(Color.White, radius = 12.dp.toPx(), center = point)
                drawCircle(partnerColor, radius = 10.dp.toPx(), center = point)
            }
        }
        Text("N", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
        if (distance != null) {
            Text("You", style = MaterialTheme.typography.labelSmall, color = primary, modifier = Modifier.align(Alignment.Center).padding(top = 34.dp))
        } else {
            Box(Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape).background(partnerColor), contentAlignment = Alignment.Center) {
                Text(initial, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

fun locationAllowed(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
