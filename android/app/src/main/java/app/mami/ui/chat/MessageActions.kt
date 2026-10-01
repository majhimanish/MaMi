package app.mami.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind
import app.mami.data.db.MessageState
import app.mami.ui.theme.Mami

/** The quick reactions, in the order people reach for them. */
val QuickReactions = listOf("❤️", "😂", "😍", "😮", "😢", "🙏")

/** The full set behind the "+" button. */
private val MoreReactions = listOf(
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💖", "💗", "💓", "💞", "💕", "💘", "💝", "😘",
    "🥰", "😍", "🤩", "😊", "☺️", "🥹", "😂", "🤣", "😅", "😆", "😁", "😉", "😏", "😌", "😇", "🙂",
    "🤗", "🫠", "😮", "😯", "😲", "🥺", "😢", "😭", "😤", "😡", "🤯", "😳", "🙈", "🙊", "😴", "🤤",
    "👍", "👎", "👏", "🙌", "🙏", "💪", "👌", "✌️", "🤞", "🔥", "✨", "🎉", "💯", "⭐", "🌙", "☀️",
    "🌹", "🌸", "🍀", "🥟", "☕", "🍕", "🍰", "🎂", "🎁", "🏠", "🚗", "✈️", "📸", "🎶", "💤", "👀",
)

/** What can be done with a message, shown on long-press. */
class MessageMenu(
    val onReact: (String?) -> Unit,
    val onReply: () -> Unit,
    val onCopy: (() -> Unit)?,
    val onEdit: (() -> Unit)?,
    val onPin: (Boolean) -> Unit,
    val onStar: (Boolean) -> Unit,
    val onInfo: () -> Unit,
    val onSave: (() -> Unit)?,
    val onShare: (() -> Unit)?,
    val onUnsend: (() -> Unit)?,
    val onDelete: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(message: MessageEntity, partnerName: String, menu: MessageMenu, onDismiss: () -> Unit) {
    var moreEmoji by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun act(action: () -> Unit) {
        action()
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            Text(
                snippet(message, partnerName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(14.dp))
            // Reactions need the message to have reached them.
            if (!message.unsent && (!message.fromMe || message.state != MessageState.PENDING)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QuickReactions.forEach { emoji ->
                        ReactionButton(emoji, selected = message.myReaction == emoji) {
                            act { menu.onReact(if (message.myReaction == emoji) null else emoji) }
                        }
                    }
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable { moreEmoji = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "More reactions")
                    }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
            }
            Action(Icons.AutoMirrored.Filled.Reply, "Reply") { act(menu.onReply) }
            menu.onCopy?.let { copy -> Action(Icons.Filled.ContentCopy, "Copy text") { act(copy) } }
            menu.onEdit?.let { edit -> Action(Icons.Filled.Edit, "Edit") { act(edit) } }
            Action(Icons.Filled.PushPin, if (message.pinnedAtMs != null) "Unpin" else "Pin for both of you") {
                act { menu.onPin(message.pinnedAtMs == null) }
            }
            Action(if (message.starred) Icons.Filled.Star else Icons.Filled.StarBorder, if (message.starred) "Unstar" else "Star") {
                act { menu.onStar(!message.starred) }
            }
            menu.onSave?.let { save -> Action(Icons.Filled.Download, "Save to phone") { act(save) } }
            menu.onShare?.let { share -> Action(Icons.Filled.Share, "Share") { act(share) } }
            Action(Icons.Filled.Info, "Message journey") { act(menu.onInfo) }
            menu.onUnsend?.let { Action(Icons.Filled.DeleteForever, "Unsend for both of you", danger = true) { confirm = "unsend" } }
            Action(Icons.Filled.Delete, "Delete for me", danger = true) { confirm = "delete" }
        }
    }
    if (moreEmoji) {
        AlertDialog(
            onDismissRequest = { moreEmoji = false },
            confirmButton = { TextButton(onClick = { moreEmoji = false }) { Text("Close") } },
            title = { Text("React") },
            text = {
                LazyVerticalGrid(columns = GridCells.Fixed(6), modifier = Modifier.heightIn(max = 360.dp)) {
                    items(MoreReactions) { emoji ->
                        Box(
                            Modifier.padding(2.dp).size(44.dp).clip(CircleShape).clickable {
                                moreEmoji = false
                                act { menu.onReact(emoji) }
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, fontSize = 26.sp)
                        }
                    }
                }
            },
        )
    }
    when (confirm) {
        "unsend" -> ConfirmDialog(
            title = "Unsend this message?",
            text = "It disappears from $partnerName's phone too. They'll see that a message was unsent.",
            action = "Unsend",
            onConfirm = { act { menu.onUnsend?.invoke() } },
            onDismiss = { confirm = null },
        )
        "delete" -> ConfirmDialog(
            title = "Delete for you?",
            text = if (message.fromMe) "It stays on $partnerName's phone. To remove it for both of you, unsend it instead." else "It stays on $partnerName's phone.",
            action = "Delete",
            onConfirm = { act(menu.onDelete) },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(action, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReactionButton(emoji: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)).border(2.dp, Mami.colors.gradient.first(), CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 26.sp)
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** Whether a message can be edited: my own text (or caption), not unsent. */
fun canEdit(message: MessageEntity): Boolean =
    message.fromMe && !message.unsent && (message.kind == MessageKind.TEXT || (message.kind == MessageKind.MEDIA && message.body.isNotBlank()))
