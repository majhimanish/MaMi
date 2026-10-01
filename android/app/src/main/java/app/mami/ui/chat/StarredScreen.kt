package app.mami.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.data.db.MessageEntity
import app.mami.ui.Format
import app.mami.ui.UiController
import app.mami.ui.rememberBase64Image

/** Messages either of you starred, to find them again. Tap one to see it in the chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(ui: UiController, partnerName: String, onBack: () -> Unit, onShowInChat: (String) -> Unit) {
    val messages by ui.backend.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val starred = remember(messages) { messages.filter { it.starred && !it.unsent }.asReversed() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Starred messages") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (starred.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(40.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("⭐", fontSize = 56.sp)
                Spacer(Modifier.height(12.dp))
                Text("Nothing starred yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Hold a message and tap Star to keep the sweet ones close.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(starred, key = { it.id }) { message -> StarredRow(message, partnerName) { onShowInChat(message.id) } }
            }
        }
    }
}

@Composable
private fun StarredRow(message: MessageEntity, partnerName: String, onClick: () -> Unit) {
    val thumb = rememberBase64Image(message.mediaThumb?.takeIf { !message.viewOnce })
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                (if (message.fromMe) "You" else partnerName) + " · " + Format.preciseDateTime(message.sentAtMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(snippet(message, partnerName), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (thumb != null) {
            Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
        }
    }
    HorizontalDivider(Modifier.padding(start = 54.dp))
}
