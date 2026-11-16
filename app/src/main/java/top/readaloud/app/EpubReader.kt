package top.readaloud.app

import android.content.Context
import android.net.Uri
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/**
 * 解析一本 EPUB 的结果。
 *
 * @param title      书名（来自 dc:title；拿不到才退回文件名）
 * @param author     作者（dc:creator，可能为空）
 * @param language   语言（dc:language）
 * @param text       全书纯文本（已剥掉标签，段落之间用换行分隔）
 * @param chapters   目录：章节标题 + 该章在 text 里的**字符偏移**
 * @param tocSource  目录是从哪来的（nav / ncx / spine）—— PRD 要求 UI 标注来源
 */
data class EpubBook(
    val title: String,
    val author: String,
    val language: String,
    val text: String,
    val chapters: List<Pair<String, Int>>,
    val tocSource: String
)

/**
 * 自研 EPUB 解析器（PRD FR-02）。
 *
 * ⚠️ 为什么不用现成库（这是 PRD 里的明确决定，别改）：
 *   常见的 Java EPUB 库要么停止维护、要么许可证五花八门 ——
 *   我们的项目是 Apache-2.0 开源、还要给涉密场景用，
 *   引一个说不清来源的解析库风险不值当。自己写 400 行反而可控，
 *   而且能精确决定"什么进朗读、什么不进"（比如注音不能念出来）。
 *
 * 流程（EPUB 就是一个 zip，按规范一层层找）：
 *   mimetype → META-INF/container.xml（指向 OPF）→ OPF（书单：清单/顺序/元数据）
 *   → 目录（优先 nav，回退 NCX）→ 按 spine 顺序逐个 XHTML 抽文本
 *
 * ⚠️ 只在内存里处理**文本类**条目，图片/字体一律跳过不读 ——
 *   EPUB 的主要体积都在图里，全读进来白白吃内存（这台测试机只有 3.6GB）。
 */
object EpubReader {

    private const val TAG = "ReadaloudEpub"
    /** 单个文本条目最多读这么多字节，防止畸形文件把内存吃爆 */
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    /** 全书文本总量上限 */
    private const val MAX_TOTAL_BYTES = 64 * 1024 * 1024

    /** 剥掉标签时，这些元素的内容**一个字都不要**（注音、脚本、样式） */
    private val SKIP_CONTENT = setOf("script", "style", "rt", "rp", "head", "title", "meta", "link")

    /** 遇到这些块级元素就换行（否则整章会连成一行，朗读时没有停顿） */
    private val BLOCK_TAGS = setOf(
        "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "tr", "blockquote", "section", "article", "header", "footer",
        "aside", "nav", "figure", "figcaption", "pre", "hr", "dt", "dd", "tbody", "thead"
    )

    /** 这几个标签的内容当"标题"看（书里没带目录时，用它兜底分章） */
    private val HEADING_TAGS = setOf("h1", "h2", "h3")

    /**
     * 读一本 EPUB。
     *
     * @return 解析成功返回 EpubBook；文件不是合法 EPUB / 打不开则返回 null
     */
    fun read(context: Context, uri: Uri): EpubBook? {
        val texts = readTextEntries(context, uri) ?: return null
        if (texts.isEmpty()) return null

        // 1) container.xml 告诉我们 OPF（书单）在哪
        val container = texts["META-INF/container.xml"]
        val opfPath = container?.let { parseContainer(it) }
            ?: texts.keys.firstOrNull { it.endsWith(".opf") }
            ?: return null
        val opf = texts[opfPath] ?: return null
        val baseDir = opfPath.substringBeforeLast('/', "")

        val meta = parseOpf(opf)
        Log.i(
            TAG,
            "OPF 解析：书名=${meta.title} 清单=${meta.manifest.size} 项 spine=${meta.spine.size} 项 " +
                "nav=${meta.navHref ?: "无"} ncx=${meta.ncxHref ?: "无"}"
        )
        if (meta.spine.isEmpty()) {
            Log.w(TAG, "OPF 里 spine 是空的，读不出正文")
            return null
        }

        // 2) 目录：EPUB3 的 nav 优先，其次 NCX（EPUB2 的老写法）
        var tocSource = "spine"
        var toc: List<Pair<String, String>> = emptyList()      // href → 标题
        meta.navHref?.let { navHref ->
            val navPath = resolve(baseDir, navHref)
            texts[navPath]?.let { xml ->
                val list = parseNav(xml, navPath.substringBeforeLast('/', ""))
                Log.i(TAG, "nav 文档 $navPath → ${list.size} 条目录")
                if (list.isNotEmpty()) {
                    toc = list
                    tocSource = "nav"
                }
            }
        }
        if (toc.isEmpty()) {
            meta.ncxHref?.let { ncxHref ->
                val ncxPath = resolve(baseDir, ncxHref)
                texts[ncxPath]?.let { xml ->
                    val list = parseNcx(xml, ncxPath.substringBeforeLast('/', ""))
                    Log.i(TAG, "NCX 文档 $ncxPath → ${list.size} 条目录")
                    if (list.isNotEmpty()) {
                        toc = list
                        tocSource = "ncx"
                    }
                }
            }
        }

        // 3) 按 spine 顺序拼正文，同时记录每一章从第几个字符开始
        val sb = StringBuilder()
        val chapters = ArrayList<Pair<String, Int>>()
        var usedTotal = 0
        for (idref in meta.spine) {
            val item = meta.manifest[idref] ?: continue
            val path = resolve(baseDir, item.href)
            val raw = texts[path] ?: continue
            val body = htmlToText(raw)
            if (body.isBlank()) continue
            if (usedTotal + body.length > MAX_TOTAL_BYTES) {
                Log.w(TAG, "正文超过上限，后面的章节不再读入")
                break
            }
            // 这一篇文档对应目录里的哪个标题？
            // ⚠️ 目录里的 href 在解析 nav/NCX 时**就已经换算成包内路径**了，
            //    这里不能再 resolve 一次 —— 否则会变成 OEBPS/OEBPS/ch1.xhtml，
            //    永远匹配不上（表现为"目录读到了 4 条、章节却只有 1 个"）。
            var title = toc.firstOrNull { (href, _) -> href == path }?.second
            if (title == null && toc.isEmpty()) {
                title = firstHeading(raw)
                if (title != null) tocSource = "heading"
            }
            if (title != null) {
                chapters.add(title to sb.length)
            } else if (chapters.isEmpty()) {
                chapters.add("开篇" to 0)
            }
            sb.append(body)
            usedTotal += body.length
        }

        val text = tidy(sb.toString())
        if (text.isBlank()) {
            Log.w(TAG, "抽不出文字 —— 可能是纯图片的 EPUB")
            return null
        }

        // 章节偏移要在"清洗过的文本"上重算：tidy 会压掉多余空行，偏移会整体前移，
        // 所以这里按"章节标题第一次出现的位置"重新定位（标题在正文里一定存在）
        val fixed = chapters.map { (title, offset) ->
            val guess = offset.coerceIn(0, text.length)
            val found = text.indexOf(title, (guess - 200).coerceAtLeast(0))
            title to (if (found >= 0) found else guess)
        }

        Log.i(
            TAG,
            "EPUB 解析完成：书名=${meta.title} 章节数=${fixed.size} 目录来源=$tocSource " +
                "正文 ${text.length} 字"
        )
        return EpubBook(
            title = meta.title.ifBlank { "" },
            author = meta.author,
            language = meta.language,
            text = text,
            chapters = fixed,
            tocSource = tocSource
        )
    }

    // ── zip 读取 ─────────────────────────────────────────────────

    /** 把压缩包里所有**文本类**条目的内容读进内存（图片字体跳过不读） */
    private fun readTextEntries(context: Context, uri: Uri): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        if (!isTextEntry(name)) {
                            zip.closeEntry()
                            continue
                        }
                        val bytes = readLimited(zip, MAX_ENTRY_BYTES)
                        out[name] = TextFileReader.decode(bytes)
                        zip.closeEntry()
                    }
                }
            }
            out
        }.getOrElse {
            // 选中的可能压根不是 zip（比如用户把 .txt 改名成 .epub）
            Log.w(TAG, "读 EPUB 失败：${it.message}")
            null
        }
    }

    /** zip 里我们关心的文本类文件 */
    private fun isTextEntry(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") ||
            lower.endsWith(".xml") || lower.endsWith(".opf") || lower.endsWith(".ncx") ||
            lower.endsWith(".txt")
    }

    /** 读一个条目，最多读 max 字节（防止畸形包撑爆内存） */
    private fun readLimited(zip: ZipInputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val n = zip.read(buf, 0, minOf(buf.size, max - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    // ── container.xml / OPF ──────────────────────────────────────

    /** 从 META-INF/container.xml 里取出 OPF 的路径 */
    private fun parseContainer(xml: String): String? {
        var path: String? = null
        eachTag(xml) { parser, name, _, _ ->
            if (name == "rootfile" && path == null) {
                path = parser.getAttributeValue(null, "full-path")
            }
        }
        return path
    }

    private class ManifestItem(val href: String)

    private class OpfMeta(
        val title: String,
        val author: String,
        val language: String,
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>,
        val navHref: String?,
        val ncxHref: String?
    )

    /**
     * 解析 OPF（书单）：
     *   metadata 里有书名/作者/语言；manifest 是"文件清单"；
     *   spine 是"阅读顺序"（真正决定朗读顺序的就是它）；
     *   目录文档靠 properties="nav" 或 spine 的 toc 属性指出。
     */
    private fun parseOpf(xml: String): OpfMeta {
        var title = ""
        var author = ""
        var language = ""
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<String>()
        var navHref: String? = null
        var ncxId: String? = null

        eachTag(xml) { parser, name, _, isStart ->
            // ⚠️ 下面每个分支都要判 isStart（踩过）：
            //   像 <itemref idref="ch1"/> 这种自闭合标签，解析器会先发"开始"再发"结束"，
            //   两边都处理的话，阅读顺序表里每一项都会被加两遍（日志里表现为 spine=8 项），
            //   拼出来的正文也就跟着重复了。
            if (!isStart) return@eachTag
            when (name) {
                "title" -> if (title.isBlank()) title = readTextOf(parser)
                "creator" -> if (author.isBlank()) author = readTextOf(parser)
                "language" -> if (language.isBlank()) language = readTextOf(parser)
                "item" -> {
                    val id = parser.getAttributeValue(null, "id") ?: return@eachTag
                    val href = parser.getAttributeValue(null, "href") ?: return@eachTag
                    val props = parser.getAttributeValue(null, "properties") ?: ""
                    val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                    manifest[id] = ManifestItem(href)
                    // EPUB3 的目录文档：properties 里带 nav
                    if (props.split(' ').contains("nav")) navHref = href
                    // EPUB2 的目录：media-type 是 dtbncx
                    if (mediaType.contains("dtbncx")) ncxId = id
                }
                "spine" -> {
                    parser.getAttributeValue(null, "toc")?.let { ncxId = it }
                }
                "itemref" -> {
                    parser.getAttributeValue(null, "idref")?.let {
                        // linear="no" 的是"不在正文流里"的附属页（比如封面），跳过不朗读
                        val linear = parser.getAttributeValue(null, "linear")
                        if (linear == null || linear.equals("yes", true)) spine.add(it)
                    }
                }
            }
        }
        // ncx 文件也可能通过 id 记录，转成 href
        val ncxHref = ncxId?.let { manifest[it]?.href }
            ?: manifest.values.firstOrNull { it.href.lowercase().endsWith(".ncx") }?.href
        return OpfMeta(title, author, language, manifest, spine, navHref, ncxHref)
    }

    // ── 目录：nav（EPUB3）/ NCX（EPUB2）────────────────────────────

    /**
     * 解析 EPUB3 的 nav 文档：只认 `<nav epub:type="toc">` 里的链接。
     * 返回 href → 标题（href 已按 nav 文档所在目录解析成包内路径）。
     */
    private fun parseNav(xml: String, baseDir: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var inTocNav = false
        var pendingHref: String? = null
        var pendingTitle: StringBuilder? = null
        eachTag(xml, includeText = true) { parser, name, text, isStart ->
            if (isStart) {
                when (name) {
                    "nav" -> {
                        val type = parser.getAttributeValue("http://www.idpf.org/2007/ops", "type")
                            ?: parser.getAttributeValue(null, "type")
                        inTocNav = type?.contains("toc") == true
                    }
                    "a" -> if (inTocNav) {
                        pendingHref = parser.getAttributeValue(null, "href")
                        pendingTitle = StringBuilder()
                    }
                }
            } else {
                when (name) {
                    "a" -> {
                        val h = pendingHref
                        val t = pendingTitle?.toString()?.trim()
                        if (h != null && !t.isNullOrBlank()) {
                            out.add(resolve(baseDir, h) to t)
                        }
                        pendingHref = null
                        pendingTitle = null
                    }
                    "nav" -> inTocNav = false
                }
            }
            if (isStart && text != null) pendingTitle?.append(text)
        }
        return out
    }

    /**
     * 解析 EPUB2 的 NCX。
     *
     * ⚠️ 必须等 navPoint **结束**时才能配对（踩过的顺序问题）：
     *   NCX 的结构是 navPoint > navLabel > text 在前、content src 在后，
     *   如果一见到 navLabel 结束就输出，这时 content 还没读到，
     *   配出来的标题会整体错位一格（第一章配成第二章的位置）。
     *   用栈处理，嵌套的 navPoint（章下面带节）也能各归各位。
     */
    private class NcxPoint {
        val label = StringBuilder()
        var src: String? = null
    }

    private fun parseNcx(xml: String, baseDir: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val stack = ArrayList<NcxPoint>()
        var inLabel = false
        eachTag(xml, includeText = true) { parser, name, text, isStart ->
            if (isStart) {
                when (name) {
                    "navpoint" -> stack.add(NcxPoint())
                    "navlabel" -> inLabel = true
                    "content" -> {
                        val src = parser.getAttributeValue(null, "src")
                        if (!src.isNullOrBlank() && stack.isNotEmpty()) stack.last().src = src
                    }
                }
                if (inLabel && !text.isNullOrBlank() && stack.isNotEmpty()) {
                    stack.last().label.append(text)
                }
            } else {
                when (name) {
                    "navlabel" -> inLabel = false
                    "navpoint" -> {
                        val p = if (stack.isEmpty()) null else stack.removeAt(stack.size - 1)
                        val t = p?.label?.toString()?.trim()
                        val src = p?.src
                        if (!t.isNullOrBlank() && src != null) out.add(resolve(baseDir, src) to t)
                    }
                }
            }
        }
        return out
    }

    // ── XHTML → 纯文本 ───────────────────────────────────────────

    /**
     * 把一篇 XHTML 正文抽成纯文本。
     *
     * 几个必须做对的地方（PRD FR-02 明确要求）：
     *   · `<ruby>星图<rt>xīng tú</rt></ruby>` —— 只取"星图"，**注音不能念出来**；
     *   · `<script>` / `<style>` 整段丢弃（否则会念出 JS 代码）；
     *   · `<table>` 转成文本网格（单元格用制表符分隔，一行一条）；
     *   · 块级元素之间补换行（否则整章连成一行，朗读没有停顿）；
     *   · 图片跳过（TXT/EPUB 朗读场景里，"图片"两个字念出来只是噪音）。
     */
    private fun htmlToText(xml: String): String {
        val sb = StringBuilder()
        var skipDepth = -1

        runCatching {
            val parser = newParser(preprocessEntities(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        if (skipDepth < 0 && name in SKIP_CONTENT) skipDepth = parser.depth
                        if (skipDepth < 0) {
                            if (name in BLOCK_TAGS) newline(sb)
                            if (name == "td" || name == "th") {
                                if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\t')
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        if (skipDepth >= 0 && parser.depth <= skipDepth) skipDepth = -1
                        if (skipDepth < 0 && name in BLOCK_TAGS) newline(sb)
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                        // ⚠️ 不要再自己做转义还原：`&amp;` 这类标准实体会被解析器
                        //    直接还原成字符，再反转义一次会把正文里原本就写着的
                        //    "&lt;" 字面量误解成 "<"。
                        if (skipDepth < 0) {
                            val t = parser.text ?: ""
                            // ⚠️ XHTML 源码里的折行只是排版（渲染出来是空格，不是句末），
                            //    必须折叠掉 —— 否则一句话会被断句器从中间劈开合成，
                            //    "2024 年 12\n月期间" 这种断在数字中间的停顿就是这么来的。
                            //    （段落之间是分开的：下面的块级标签会补真正的换行）
                            if (t.isNotBlank()) sb.append(LineBreaks.foldSoft(t))
                        }
                    }
                }
                event = parser.next()
            }
        }.onFailure { Log.w(TAG, "XHTML 解析中断（已抽到的内容照常用）：${it.message}") }

        return sb.toString()
    }

    /**
     * 取一篇文档里第一个小标题（h1–h3）当章节名。
     * 只在书里完全没有目录（nav / NCX 都没有）时才用得上。
     */
    private fun firstHeading(xml: String): String? {
        var out: String? = null
        var inHeading = false
        val sb = StringBuilder()
        eachTag(xml, includeText = true) { _, name, text, isStart ->
            if (isStart) {
                if (name in HEADING_TAGS) {
                    inHeading = true
                    sb.clear()
                }
                if (inHeading && text != null) sb.append(text)
            } else if (name in HEADING_TAGS && inHeading) {
                inHeading = false
                val t = sb.toString().trim()
                if (out == null && t.length in 1..60) out = t
            }
        }
        return out
    }

    /** 只在缓冲区末尾不是换行时补一个换行（避免攒出一堆空行） */
    private fun newline(sb: StringBuilder) {
        if (sb.isEmpty()) return
        if (sb.last() != '\n') sb.append('\n')
    }

    /**
     * ⚠️ 为什么要在解析前做一遍实体替换（实测踩到）：
     *   XHTML 理论上只能用 XML 预定义的 5 个实体 + 数字引用，
     *   但现实里大量 EPUB 直接写 `&nbsp;` `&mdash;` —— 严格的 XML 解析器
     *   遇到未定义实体会**当场抛异常**，整本书就废了。
     *   所以在喂给解析器之前，把常见 HTML 命名实体换成真字符。
     */
    private fun preprocessEntities(xml: String): String {
        if (!xml.contains('&')) return xml
        return Regex("&([a-zA-Z][a-zA-Z0-9]{1,10});").replace(xml) { m ->
            HTML_ENTITIES[m.groupValues[1]] ?: ""
        }
    }

    private val HTML_ENTITIES = mapOf(
        "nbsp" to "\u00A0", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "middot" to "·", "bull" to "•", "copy" to "©", "reg" to "®", "trade" to "™",
        "times" to "×", "divide" to "÷", "deg" to "°", "sect" to "§", "para" to "¶",
        "laquo" to "«", "raquo" to "»", "euro" to "€", "pound" to "£", "yen" to "¥",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "harr" to "↔",
        "szlig" to "ß", "agrave" to "à", "eacute" to "é", "egrave" to "è"
    )

    // ── 小工具 ───────────────────────────────────────────────────

    private fun newParser(xml: String): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        return parser
    }

    /**
     * 简化版"遍历所有标签"：大多数结构只需要看标签名和属性。
     *
     * ⚠️ 文本事件必须单独分派，不能靠 `parser.name != null` 来筛（踩过）：
     *   XmlPullParser 的 name 只在"开始/结束标签"事件上有值，文本事件上是 null ——
     *   一开始我写了个 `if (name == null) 跳过`，结果所有文本回调都被吃掉，
     *   nav / NCX 里的"链接文字 = 章节标题"永远收集不到，
     *   表现为解析成功、但目录来源退化成 spine、章节数只有 1。
     */
    private inline fun eachTag(
        xml: String,
        includeText: Boolean = false,
        action: (XmlPullParser, String, String?, Boolean) -> Unit
    ) {
        runCatching {
            val parser = newParser(preprocessEntities(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG ->
                        action(parser, parser.name?.lowercase() ?: "", parser.text, true)
                    XmlPullParser.END_TAG ->
                        action(parser, parser.name?.lowercase() ?: "", null, false)
                    XmlPullParser.TEXT, XmlPullParser.CDSECT ->
                        if (includeText) action(parser, "", parser.text, true)
                }
                event = parser.next()
            }
        }.onFailure { Log.w(TAG, "XML 解析中断：${it.message}") }
    }

    /** 读一个元素的文字内容（用于 <dc:title>序章</dc:title> 这种） */
    private fun readTextOf(parser: XmlPullParser): String = runCatching {
        val depth = parser.depth
        val sb = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
                sb.append(parser.text ?: "")
            }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        sb.toString().trim()
    }.getOrDefault("")

    /**
     * 把 EPUB 内部的相对路径拼成"包内绝对路径"。
     * 例：baseDir="OEBPS"，href="../Text/ch1.xhtml" → "Text/ch1.xhtml"
     */
    private fun resolve(baseDir: String, href: String): String {
        val raw = href.substringBefore('#').substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        if (decoded.startsWith("/")) return decoded.trimStart('/')
        val stack = ArrayList<String>()
        val parts = (if (baseDir.isBlank()) "" else "$baseDir/") + decoded
        for (p in parts.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(p)
            }
        }
        return stack.joinToString("/")
    }

    /** 收尾清洗：压掉过多空行、去掉行首行尾空白 */
    private fun tidy(text: String): String {
        val lines = text.split('\n').map { it.trim() }
        val sb = StringBuilder()
        var blank = 0
        for (line in lines) {
            if (line.isEmpty()) {
                blank++
                if (blank > 1) continue          // 最多留一个空行
            } else {
                blank = 0
            }
            sb.append(line).append('\n')
        }
        return sb.toString().trim()
    }
}
