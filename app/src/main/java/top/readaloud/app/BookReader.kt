package top.readaloud.app

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * 一本书「载入之后」的全部成果：正文、切好的句子、章节、朗读分段。
 *
 * ⚠️⚠️ 界面和播放服务**必须都从这里取内容**（这里是踩过大坑的地方）：
 *
 *   播放服务原本是自己去 `TextFileReader.read()` 读文件的，它只认纯文本。
 *   换成 EPUB 之后，服务端读到的就是 **zip 二进制被当成文本解码**的结果 ——
 *   切句、合成、念出来的全是一串怪声；而界面上显示的却是正确的中文
 *   （界面走的是 EpubReader）。一个文件被两边各读一遍，还读出了两种东西。
 *
 *   顺带还有第二个更隐蔽的坑：章节识别。界面用书里的 nav/NCX 目录，
 *   服务端用正则去猜 —— 两边章节不一样 → 分组就不一样 →
 *   「第几段」这个坐标两边各说各话，高亮和跳句会整体错位。
 *
 *   所以这四件事（读文件 / 切句 / 认章节 / 分段）必须合并成一个入口，
 *   谁需要谁来调，保证两边看到的是同一本书、同一套切分。
 */
data class BookContent(
    val title: String,
    val text: String,
    val sentences: List<Sentence>,
    val chapters: List<Chapter>,
    val groups: List<TtsGroup>,
    /** 目录是哪来的：nav / ncx / heading / spine / regex（TXT 是用正则认的） */
    val tocSource: String
)

object BookReader {

    private const val TAG = "ReadaloudBook"

    /**
     * 同一次运行里同一个文件只解析一次。
     *
     * 为什么需要：界面打开一本书要一份内容，播放服务收到播放指令还要一份 ——
     * 不缓存的话，大 EPUB / PDF 会被完整解析两遍，白白多等一倍时间
     * （PRD 要求 10MB EPUB 解析 < 4s，翻倍就超了）。
     * Activity 和 MediaSessionService 跑在同一个进程里，所以这块缓存是共享的。
     */
    private var cachedKey: String? = null
    private var cached: BookContent? = null

    /**
     * 读一本书：按类型解析 → 切句 → 认章节 → 分段。
     *
     * @return 读不出来返回 null（文件损坏、正文全是图片的 EPUB、PDF 等）
     */
    @Synchronized
    fun load(
        context: Context,
        uri: Uri,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): BookContent? {
        val key = uri.toString()
        if (key == cachedKey) {
            cached?.let {
                Log.i(TAG, "复用上次解析结果（同一个文件不重复解析）")
                return it
            }
        }
        val content = parse(context, uri, onProgress) ?: return null
        cachedKey = key
        cached = content
        return content
    }

    /** 手动作废缓存（比如源文件被替换过） */
    @Synchronized
    fun clearCache() {
        cachedKey = null
        cached = null
    }

    private fun parse(
        context: Context,
        uri: Uri,
        onProgress: ((done: Int, total: Int) -> Unit)?
    ): BookContent? {
        val ext = BookShelf.fileExtension(context, uri)
        val fallbackTitle = BookShelf.displayTitle(context, uri)

        var text: String
        var title = fallbackTitle
        var offsets: List<Pair<String, Int>>? = null
        var tocSource = "regex"

        when (ext) {
            "epub" -> {
                val book = runCatching { EpubReader.read(context, uri) }.getOrNull()
                if (book == null || book.text.isBlank()) {
                    Log.w(TAG, "EPUB 抽不出文字：$uri")
                    return null
                }
                text = book.text
                if (book.title.isNotBlank()) title = book.title
                offsets = book.chapters
                tocSource = book.tocSource
            }

            "pdf" -> {
                // ⚠️ 必须在这里统一抽 —— 绝不能让播放服务自己去读（它就是上次
                //    把 EPUB 的 zip 当文本、念出乱码的元凶）。PDF 尤其危险：
                //    二进制被当文本解出来的东西同样是一堆乱码。
                val pdf = runCatching { PdfReader.read(context, uri, onProgress) }.getOrNull()
                if (pdf == null || pdf.text.isBlank()) {
                    Log.w(TAG, "PDF 抽不出文字：$uri")
                    return null
                }
                text = pdf.text
                if (pdf.title.isNotBlank()) title = pdf.title
                offsets = pdf.chapters
                tocSource = pdf.tocSource
            }

            else -> {
                // TXT 及无扩展名：按纯文本读（会做编码探测：BOM → UTF-8 → GB18030）
                val t = runCatching { TextFileReader.read(context, uri) }.getOrNull()
                if (t.isNullOrBlank()) {
                    Log.w(TAG, "文本文件读不出内容：$uri")
                    return null
                }
                text = t
            }
        }

        // ⚠️ 结构噪声清理放在切句之前（PRD FR-20 第 1 条）：
        //    URL、邮箱、[12] 引文编号、ISBN 这些东西念出来纯粹是噪音，
        //    界面上显示它们也没有意义，所以这里直接清掉，显示和朗读都干净。
        //    注意：清理会减少字符数、让偏移整体前移 —— 下面的章节换算因此
        //    改用"标题文本"在附近校准（见 chaptersAtOffsets）。
        val body = TextCleaner.clean(text)

        val sentences = TextFileReader.splitSentences(body)
        if (sentences.isEmpty()) return null

        // EPUB 用书里带的目录（标题 + 字符偏移），TXT 只能靠正则猜
        val chapters = if (offsets != null) {
            TextFileReader.chaptersAtOffsets(sentences, offsets)
        } else {
            TextFileReader.detectChapters(body, sentences)
        }

        val groups = TextFileReader.groupForTts(
            sentences, 40, chapters.map { it.startSentence }.toSet()
        )

        Log.i(
            TAG,
            "载入完成：$title（$ext）句子 ${sentences.size} · 章节 ${chapters.size} " +
                "· 段落 ${groups.size} · 目录来源 $tocSource"
        )
        return BookContent(title, body, sentences, chapters, groups, tocSource)
    }
}
