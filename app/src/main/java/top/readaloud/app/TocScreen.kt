package top.readaloud.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **目录页** —— 独立的一页，正文页那根横向章节条搬家到这里了。
 *
 * ## 为什么要有这一页（Leo 提的，原话：应该有个叫"目录"的 tab）
 *
 * 之前章节是「正在听」页顶部的一根**横向**条子，只能左右滑、一眼看不到全貌，
 * 而且书一多章节就挤成一条细缝。别的听书软件都是：目录单独一页、竖着列、
 * 点一下直接跳过去并回到正文 —— 这才符合"我要找第三章"的直觉。
 *
 * ## 这一页有两个分段
 *
 * · **章节**：竖排全书目录，显示完整标题，正在读的那一章高亮
 * · **书签**：自己打的记号，点一下回到那句话
 *
 * 分段而不是再开两个底部页签，是因为**章节和书签都是"当前这本书"的**，
 * 放在一起才符合直觉（换本书两个都跟着换）。
 *
 * ## 状态归属
 *
 * ⚠️ 和播放页一样：这一页**只显示 + 抛回调**，不自己动播放器和存储。
 *    播放位置的家底在 PlaybackService，书签的持久化在 BookmarkStore，
 *    界面只读一份内存状态（由上层在数据变化时刷新）。
 */
@Composable
fun TocScreen(
    fileName: String?,
    chapters: List<Chapter>,
    currentIndex: Int,
    bookmarks: List<Bookmark>,
    hasBook: Boolean,
    onOpenSentence: (Int) -> Unit,
    onAddBookmark: () -> Unit,
    onRemoveBookmark: (Int) -> Unit
) {
    // 0=章节 1=书签。用 remember 而不是参数传进来 —— 切页时不该被重置
    var seg by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("目录", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = fileName ?: "还没有打开书",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Spacer(Modifier.height(12.dp))

        if (!hasBook) {
            Text(
                text = "先在书架里挑一本书打开，这里才有它的目录和书签。",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
            return@Column
        }

        // ── 分段切换（章节 / 书签）────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SegTab("章节 ${chapters.size}", seg == 0) { seg = 0 }
            SegTab("书签 ${bookmarks.size}", seg == 1) { seg = 1 }
        }
        Spacer(Modifier.height(10.dp))

        if (seg == 0) {
            ChapterList(chapters, currentIndex, onOpenSentence)
        } else {
            BookmarkList(bookmarks, onAddBookmark, onOpenSentence, onRemoveBookmark)
        }
    }
}

@Composable
private fun SegTab(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (active) Color(0xFFE8EEFF) else Color(0xFFF2F3F5),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) Color(0xFF2B5CFF) else Color.Gray,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/**
 * 章节列表。
 *
 * 正在读的那一章高亮（用 startSentence <= currentIndex < 下一章的 startSentence 判定），
 * 这一点在竖排目录里非常有用：一眼看出"读到哪儿了"。
 */
@Composable
private fun ChapterList(
    chapters: List<Chapter>,
    currentIndex: Int,
    onOpenSentence: (Int) -> Unit
) {
    if (chapters.isEmpty()) {
        EmptyHint(
            "这本书没识别到章节。\n\n" +
                "PDF 试着换个文件（带书签或带目录页的最好认）；" +
                "TXT / EPUB 里章节一般写成「第X章」「第X节」或「一、」这种形式。"
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(chapters) { idx, ch ->
            val nextStart = chapters.getOrNull(idx + 1)?.startSentence ?: Int.MAX_VALUE
            val reading = currentIndex >= ch.startSentence && currentIndex < nextStart

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSentence(ch.startSentence) }
                    .padding(vertical = 10.dp, horizontal = 4.dp)
            ) {
                // 序号：正在读的那章用主题色，其余用灰
                Box(
                    modifier = Modifier
                        .size(width = 34.dp, height = 26.dp)
                        .background(
                            if (reading) Color(0xFFE8EEFF) else Color(0xFFF2F3F5),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${idx + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (reading) Color(0xFF2B5CFF) else Color.Gray
                    )
                }
                Spacer(Modifier.width(10.dp))
                // 标题：完整显示，最多两行（有些章节标题很长，比如 PDF 目录页里的）
                Text(
                    text = ch.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (reading) Color(0xFF2B5CFF) else Color(0xFF1D2129),
                    fontWeight = if (reading) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(color = Color(0xFFF0F1F3))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * 书签列表。
 *
 * 每条显示：句子原文预览（冗余存的那份，见 [BookmarkStore]）+ 打书签的时间。
 * 长按删除（有确认框 —— 书签虽然小，删错了重打也要重新翻到那一页）。
 */
@Composable
private fun BookmarkList(
    bookmarks: List<Bookmark>,
    onAddBookmark: () -> Unit,
    onOpenSentence: (Int) -> Unit,
    onRemoveBookmark: (Int) -> Unit
) {
    var pendingRemove by remember { mutableStateOf<Bookmark?>(null) }

    Button(onClick = onAddBookmark, modifier = Modifier.fillMaxWidth()) {
        Text("＋ 在当前这一句打书签")
    }
    Spacer(Modifier.height(4.dp))
    Text(
        // ⚠️ Compose 的Text 不解析 Markdown（`**` 会原样显示），
        //    要强调就用 fontWeight，别写 Markdown 记号。
        text = "打书签记的是现在高亮的那一句（在正文页点某一句就跳到那一句）。",
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray
    )
    Spacer(Modifier.height(10.dp))

    if (bookmarks.isEmpty()) {
        EmptyHint("还没有书签。\n\n听到有意思的地方，回到这句话点上面的按钮就能记下来。")
        return
    }

    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(bookmarks) { idx, b ->
            Column(
                // ⚠️ 用 combinedClickable（点 + 长按）而不是叠两个 clickable：
                //    叠起来会长按之后又触发一次点击，跳转和删除会互相打架。
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onOpenSentence(b.sentenceIndex) },
                        onLongClick = { pendingRemove = b }
                    )
                    .padding(vertical = 10.dp, horizontal = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${idx + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = fmt.format(Date(b.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "长按删除",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB3261E)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = b.note.ifBlank { "（这一句没有正文，可能是章节标题）" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF1D2129),
                    maxLines = 3
                )
            }
            HorizontalDivider(color = Color(0xFFF0F1F3))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    // 删书签的确认框
    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("删除这条书签？") },
            text = { Text(target.note.take(40)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveBookmark(target.sentenceIndex)
                    pendingRemove = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.Gray,
        modifier = Modifier.padding(top = 24.dp)
    )
}

/** 供上层复用的时间格式化（书签时间戳 → 文字） */
fun formatBookmarkTime(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))