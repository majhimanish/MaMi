package app.mami.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mami.data.db.MessageEntity
import app.mami.sync.MamiBackend
import app.mami.ui.Format
import app.mami.ui.rememberBase64Image
import kotlinx.coroutines.delay

/** The top bar while searching: back, the search field, clear. */
@Composable
fun SearchTopBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search messages, captions, files…") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                trailingIcon = if (query.isNotEmpty()) {
                    @Composable { IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") } }
                } else {
                    null
                },
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            Spacer(Modifier.size(8.dp))
        }
    }
}

/** Matching messages, newest first, with the words highlighted. */
@Composable
fun SearchResults(backend: MamiBackend, query: String, partnerName: String, now: Long, onOpen: (MessageEntity) -> Unit, modifier: Modifier = Modifier) {
    var results by remember { mutableStateOf<List<MessageEntity>?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(220)
        results = backend.search(query)
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        val found = results
        when {
            found == null -> Hint("🔎", "Find any message, caption, file name or link.\nSearch happens only on this phone.")
            found.isEmpty() -> Hint("🙈", "Nothing matches “$query”.")
            else -> LazyColumn {
                item {
                    Text(
                        if (found.size == 1) "1 message" else "${found.size} messages",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
                items(found, key = { it.id }) { message ->
                    ResultRow(message, query, partnerName, now) { onOpen(message) }
                    HorizontalDivider(Modifier.padding(start = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun Hint(emoji: String, text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 48.sp)
        Spacer(Modifier.height(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ResultRow(message: MessageEntity, query: String, partnerName: String, now: Long, onClick: () -> Unit) {
    val thumb = rememberBase64Image(message.mediaThumb?.takeIf { !message.viewOnce })
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (message.fromMe) "You" else partnerName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(Format.day(message.sortAtMs, now) + " · " + Format.time(message.sentAtMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                highlight(snippet(message, partnerName), query),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (thumb != null) {
            Spacer(Modifier.size(12.dp))
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Bold, tinted matches of [query] inside [text], ignoring case. */
@Composable
fun highlight(text: String, query: String): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    return remember(text, query, color) {
        val needle = query.trim()
        if (needle.isEmpty()) return@remember AnnotatedString(text)
        buildAnnotatedString {
            var from = 0
            while (from < text.length) {
                val at = text.indexOf(needle, from, ignoreCase = true)
                if (at < 0) {
                    append(text.substring(from))
                    break
                }
                append(text.substring(from, at))
                withStyle(SpanStyle(background = color, fontWeight = FontWeight.Bold)) { append(text.substring(at, at + needle.length)) }
                from = at + needle.length
            }
        }
    }
}
