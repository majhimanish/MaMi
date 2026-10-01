package app.mami.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.core.NetworkKind
import app.mami.core.NudgeKind
import app.mami.core.RingerMode
import app.mami.data.ConnectionState
import app.mami.ui.AppController
import app.mami.ui.Destination
import app.mami.ui.Overlay
import app.mami.ui.chat.localTime
import app.mami.ui.chat.partnerLocalHourMinute
import app.mami.ui.theme.DarkMode
import app.mami.ui.theme.Palette
import kotlin.math.roundToInt

/** A small draggable bug that opens the playground. Debug builds only. */
@Composable
fun PlaygroundButton(onClick: () -> Unit) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .padding(end = 6.dp)
                .size(46.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offset += drag
                    }
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text("🐞", fontSize = 22.sp)
        }
    }
}

/** Jump anywhere and make the pretend partner do anything. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Playground(controller: AppController, onDismiss: () -> Unit) {
    val demo = controller.demo
    val statusPair by demo.partnerStatus.collectAsStateWithLifecycle()
    val presence by demo.presence.collectAsStateWithLifecycle()
    val secure by demo.secure.collectAsStateWithLifecycle()
    val connection by demo.connection.collectAsStateWithLifecycle()
    val partner by demo.partner.collectAsStateWithLifecycle()
    val status = statusPair?.first
    var autoReply by remember { mutableStateOf(demo.autoReply) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("🐞 Playground", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Debug builds only. The demo partner lives on this phone; nothing touches the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Backend") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to "Real server", true to "Demo partner").forEachIndexed { index, (demoOn, label) ->
                        SegmentedButton(
                            selected = controller.demoMode == demoOn,
                            onClick = { controller.setDemo(demoOn) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label) }
                    }
                }
            }

            Section("Jump to any screen") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Destination.entries.forEach { destination ->
                        AssistChip(
                            onClick = {
                                controller.goTo(destination)
                                onDismiss()
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }

            if (controller.demoMode && partner != null) {
                Section("Partner right now") {
                    SwitchRow("Online (app open)", presence?.online == true) { demo.setPartnerOnline(it) }
                    SwitchRow("Replies by themselves", autoReply) {
                        autoReply = it
                        demo.autoReply = it
                    }
                    val battery = status?.batteryPercent ?: 76
                    Text("Battery: $battery%", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = battery.toFloat(),
                        onValueChange = { v -> demo.updatePartnerStatus { it.copy(batteryPercent = v.roundToInt()) } },
                        valueRange = 0f..100f,
                    )
                    SwitchRow("Charging", status?.charging == true) { on -> demo.updatePartnerStatus { it.copy(charging = on) } }
                    Choice(
                        "Connection",
                        listOf(NetworkKind.WIFI to "Wi-Fi", NetworkKind.CELLULAR to "Mobile", NetworkKind.OFFLINE to "Offline"),
                        status?.network,
                    ) { kind -> demo.updatePartnerStatus { it.copy(network = kind) } }
                    val signal = status?.signalLevel ?: 3
                    Text("Signal: $signal of 4", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = signal.toFloat(),
                        onValueChange = { v -> demo.updatePartnerStatus { it.copy(signalLevel = v.roundToInt()) } },
                        valueRange = 0f..4f,
                        steps = 3,
                    )
                    Choice(
                        "Sound",
                        listOf(RingerMode.NORMAL to "Ring", RingerMode.VIBRATE to "Vibrate", RingerMode.SILENT to "Silent"),
                        status?.ringer,
                    ) { mode -> demo.updatePartnerStatus { it.copy(ringer = mode) } }
                    SwitchRow("Do Not Disturb", status?.doNotDisturb == true) { on -> demo.updatePartnerStatus { it.copy(doNotDisturb = on) } }
                    val offsetHours = (status?.utcOffsetMinutes ?: 345) / 60f
                    val there = partnerLocalHourMinute(status, System.currentTimeMillis())?.let { localTime(it.first, it.second) } ?: "—"
                    Text("Their time zone: UTC${if (offsetHours >= 0) "+" else ""}${"%.1f".format(offsetHours)} · $there there", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = offsetHours,
                        onValueChange = { v -> demo.updatePartnerStatus { it.copy(utcOffsetMinutes = (v * 2).roundToInt() * 30) } },
                        valueRange = -12f..14f,
                    )
                    Text("Their status", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to "None", "🚗" to "Driving", "😴" to "Sleeping", "💼" to "At work", "🏋️" to "At the gym").forEach { (emoji, label) ->
                            FilterChip(
                                selected = status?.quickStatus?.label == label || (emoji == null && status?.quickStatus == null),
                                onClick = { demo.setPartnerQuickStatus(emoji, if (emoji == null) null else label) },
                                label = { Text(if (emoji == null) label else "$emoji $label") },
                            )
                        }
                    }
                }

                Section("Make something happen") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("✍️ Partner types") { demo.partnerTypes() }
                        Action("💬 Message me") { demo.receiveText() }
                        Action("🤗 Hug") { demo.receiveNudge(NudgeKind.HUG) }
                        Action("😘 Kiss") { demo.receiveNudge(NudgeKind.KISS) }
                        Action("💗 Thinking of you") { demo.receiveNudge(NudgeKind.THINKING_OF_YOU) }
                        Action("🔋 Battery alert") { demo.receiveBatteryAlert() }
                        Action("💀 Phone died") { demo.simulatePhoneDied() }
                        Action("🌙 Night there") { demo.simulateNight() }
                        Action("🕓 Last seen 2 h ago") { demo.setPartnerLastSeen(120) }
                        Action("🔑 New phone (key change)") { demo.simulateKeyChange() }
                        Action(if (secure) "🔓 Lose encryption" else "🔒 Restore encryption") { demo.setSecure(!secure) }
                        Action(if (connection == ConnectionState.Connected) "📴 My phone offline" else "📶 My phone online") {
                            demo.setOnline(connection != ConnectionState.Connected)
                        }
                        Action("🧹 Clear chat") { demo.clearChat() }
                    }
                }

                Section("Maya shares something") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("🌅 Photo") { demo.receivePhoto() }
                        Action("🙈 View-once photo") { demo.receivePhoto(viewOnce = true) }
                        Action("🎤 Voice message") { demo.receiveVoice() }
                        Action("🎥 Video") { demo.receiveVideo() }
                        Action("📄 PDF") { demo.receiveDocument() }
                        Action("🔗 Link") { demo.receiveLink() }
                    }
                }

                Section("Maya does something to a message") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("❤️ Reacts to mine") { demo.partnerReacts() }
                        Action("😂 Laughs at mine") { demo.partnerReacts(emoji = "😂") }
                        Action("✏️ Edits hers") { demo.partnerEdits() }
                        Action("💨 Unsends hers") { demo.partnerUnsends() }
                        Action("📌 Pins hers") { demo.partnerPins() }
                    }
                }

                Section("Open a screen") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("🖼️ Media, links & files") {
                            controller.goTo(Destination.Chat)
                            controller.overlay = Overlay.Media
                            onDismiss()
                        }
                        Action("⭐ Starred") {
                            controller.goTo(Destination.Chat)
                            controller.overlay = Overlay.Starred
                            onDismiss()
                        }
                    }
                }
            }

            Section("Look") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Palette.entries.forEach { palette ->
                        FilterChip(
                            selected = controller.appearance.palette == palette,
                            onClick = { controller.changeAppearance(controller.appearance.copy(palette = palette)) },
                            label = { Text(palette.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DarkMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = controller.appearance.darkMode == mode,
                            onClick = { controller.changeAppearance(controller.appearance.copy(darkMode = mode)) },
                            shape = SegmentedButtonDefaults.itemShape(index, DarkMode.entries.size),
                        ) { Text(mode.label) }
                    }
                }
            }

            Section("Start over") {
                Action("↺ Fresh install (demo)") {
                    controller.goTo(Destination.Welcome)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, text) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(text) }
        }
    }
    Spacer(Modifier.width(4.dp))
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) })
}
