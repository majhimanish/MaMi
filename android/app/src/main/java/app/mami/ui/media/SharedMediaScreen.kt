package app.mami.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mami.core.extractLinks
import app.mami.data.db.MediaType
import app.mami.data.db.MessageEntity
import app.mami.data.db.MessageKind
import app.mami.sync.Notifications
import app.mami.ui.Files
import app.mami.ui.Format
import app.mami.ui.UiController
import app.mami.ui.chat.Badge
import app.mami.ui.chat.FileContent
import app.mami.ui.chat.MediaImage
import app.mami.ui.chat.VoiceContent
import app.mami.ui.rememberBase64Image
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** The five kinds of things two people share. */
enum class Shelf(val title: String, val emoji: String, val empty: String) {
    PHOTOS("Photos", "📷", "Photos you send each other show up here."),
    VIDEOS("Videos", "🎥", "Videos you send each other show up here."),
    VOICE("Voice", "🎤", "Hold the mic in the chat to send a voice message."),
    LINKS("Links", "🔗", "Links from your chat collect here, with their previews."),
    FILES("Files", "📄", "Documents you share (PDFs, tickets, plans) show up here."),
}

/** A link found in a message. */
private data class SharedLink(val message: MessageEntity, val url: String)

/**
 * Everything the two of you have shared, by kind and by month: photos and
 * videos in a grid, voice messages to play, links with previews, files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedMediaScreen(ui: UiController, partnerName: String, onBack: () -> Unit, onOpen: (MessageEntity) -> Unit, onShowInChat: (String) -> Unit) {
    val backend = ui.backend
    val messages by backend.messages.collectAsStateWithLifecycle(initialValue = emptyList())
    val newest = remember(messages) { messages.filter { !it.unsent }.asReversed() }
    val photos = remember(newest) { newest.filter { it.isMedia && it.mediaKind == MediaType.PHOTO && !it.viewOnce } }
    val videos = remember(newest) { newest.filter { it.isMedia && it.mediaKind == MediaType.VIDEO && !it.viewOnce } }
    val voice = remember(newest) { newest.filter { it.isMedia && it.mediaKind == MediaType.VOICE } }
    val files = remember(newest) { newest.filter { it.isMedia && it.mediaKind == MediaType.FILE } }
    val links = remember(newest) {
        newest.filter { it.kind == MessageKind.TEXT || it.isMedia }.flatMap { m ->
            val found = extractLinks(m.body)
            (if (m.linkUrl != null && found.none { it == m.linkUrl }) listOf(m.linkUrl!!) + found else found).map { SharedLink(m, it) }
        }
    }
    val counts = mapOf(Shelf.PHOTOS to photos.size, Shelf.VIDEOS to videos.size, Shelf.VOICE to voice.size, Shelf.LINKS to links.size, Shelf.FILES to files.size)
    val pager = rememberPagerState { Shelf.entries.size }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Shared with $partnerName", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${photos.size + videos.size} photos & videos · ${voice.size} voice · ${links.size} links · ${files.size} files",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                )
                PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 12.dp) {
                    Shelf.entries.forEachIndexed { index, shelf ->
                        Tab(
                            selected = pager.currentPage == index,
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            text = { Text("${shelf.title} ${counts[shelf] ?: 0}") },
                        )
                    }
                }
            }
        },
    ) { padding ->
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().padding(padding)) { page ->
            when (Shelf.entries[page]) {
                Shelf.PHOTOS -> Grid(photos, Shelf.PHOTOS, ui, onOpen)
                Shelf.VIDEOS -> Grid(videos, Shelf.VIDEOS, ui, onOpen)
                Shelf.VOICE -> Listing(voice, Shelf.VOICE) { message ->
                    Column(Modifier.fillMaxWidth().clickable { onShowInChat(message.id) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Who(message, partnerName)
                        VoiceContent(message, backend.fileFor(message), null, mine = false, contentColor = MaterialTheme.colorScheme.onSurface, onRetry = { backend.retryTransfer(message.id) })
                    }
                }
                Shelf.LINKS -> if (links.isEmpty()) {
                    Empty(Shelf.LINKS)
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        monthHeaders(links.map { it.message }) { index -> LinkRow(links[index], partnerName, onClick = { Files.openUrl(context, links[index].url) }, onLongClick = { onShowInChat(links[index].message.id) }) }
                    }
                }
                Shelf.FILES -> Listing(files, Shelf.FILES) { message ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Who(message, partnerName)
                        FileContent(
                            message, backend.fileFor(message), null, MaterialTheme.colorScheme.onSurface,
                            onOpen = { backend.fileFor(message)?.let { Files.open(context, it, message.mediaMime) } },
                            onRetry = { backend.retryTransfer(message.id) },
                        )
                    }
                }
            }
        }
    }
}

private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

private fun month(ms: Long): String = monthFormat.format(Date(ms))

@Composable
private fun Grid(items: List<MessageEntity>, shelf: Shelf, ui: UiController, onOpen: (MessageEntity) -> Unit) {
    if (items.isEmpty()) {
        Empty(shelf)
        return
    }
    val groups = remember(items) { items.groupBy { month(it.sortAtMs) }.toList() }
    LazyVerticalGrid(columns = GridCells.Adaptive(110.dp), contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        groups.forEach { (label, monthItems) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-$label") { MonthHeader(label) }
            items(monthItems, key = { it.id }) { message ->
                val file = ui.backend.fileFor(message)
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable { if (file != null) onOpen(message) else ui.backend.retryTransfer(message.id) }) {
                    MediaImage(message, file, Modifier.fillMaxSize(), blurPreview = file == null)
                    if (message.mediaKind == MediaType.VIDEO) {
                        Box(Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
                        }
                        message.mediaDurationMs?.let { Badge(Notifications.duration(it), Modifier.align(Alignment.BottomEnd).padding(4.dp)) }
                    }
                    if (message.starred) Text("★", color = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp))
                }
            }
        }
    }
}

@Composable
private fun Listing(items: List<MessageEntity>, shelf: Shelf, row: @Composable (MessageEntity) -> Unit) {
    if (items.isEmpty()) {
        Empty(shelf)
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        monthHeaders(items) { index -> row(items[index]) }
    }
}

/** Items with a month header whenever the month changes. */
private fun LazyListScope.monthHeaders(items: List<MessageEntity>, row: @Composable (Int) -> Unit) {
    items.forEachIndexed { index, message ->
        val label = month(message.sortAtMs)
        if (index == 0 || month(items[index - 1].sortAtMs) != label) {
            item(key = "h-$label-$index") { MonthHeader(label) }
        }
        item(key = "${message.id}-$index") { row(index) }
    }
}

@Composable
private fun MonthHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Who(message: MessageEntity, partnerName: String) {
    Text(
        (if (message.fromMe) "You" else partnerName) + " · " + Format.preciseDateTime(message.sentAtMs),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkRow(link: SharedLink, partnerName: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val message = link.message
    val preview = message.linkUrl == link.url
    val image = rememberBase64Image(if (preview) message.linkImage else null)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                (if (preview) message.linkTitle else null) ?: link.url.substringAfter("://").substringBefore('/'),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(link.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (message.fromMe) "You" else partnerName) + " · " + Format.preciseDateTime(message.sentAtMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(Modifier.padding(start = 92.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun Empty(shelf: Shelf) {
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(shelf.emoji, fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text("No ${shelf.title.lowercase()} yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(shelf.empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.width(1.dp))
    }
}
