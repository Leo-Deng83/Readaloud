package top.readaloud.app

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * 解析一本 PDF 的结果。
 *
 * @param title      PDF 元数据里的标题（常常是空的，空的就由上层退回文件名）
 * @param author     PDF 元数据里的作者
 * @param text       全书纯文本（已剔除页眉页脚与页码）
 * @param chapters   目录：标题 + 该章在 text 里的**字符偏移**
 * @param tocSource  目录来源：bookmark（内嵌书签）/ none
 * @param pageCount  总页数
 */
data class PdfBook(
    val title: String,
    val author: String,
    val text: String,
    val chapters: List<Pair<String, Int>>,
    val tocSource: String,
    val pageCount: Int
)

/**
 * PDF 文本抽取（PRD FR-03，本项目技术难度最高的一块）。
 *
 * ⚠️ 为什么必须用第三方库：
 *   Android 自带的 PdfRenderer **只能把页面渲染成位图**，取不到文字，
 *   用来做"朗读"是没有意义的。所以用 PdfBox-Android（Apache-2.0）。
 *
 * 本次实现的是 "PDF 全文 + 三级目录重建"（PRD FR-03，本项目技术难度最高的一块）：
 *   · 文本抽取：逐页抽，边抽边算每页从第几个字符开始（后面要把页号换算成"字符位置"）
 *   · L1 内嵌书签：读 PDF 内嵌书签（正式出版物一般都有，准确率 ≥95%）
 *   · L2 目录页：解析「标题 …… 页码」这种目录页（很多电子书没有书签，只有这一页目录）
 *   · L3 版式认标题：都没有时，按**字号**判断谁是标题（正文字号按字符数加权取众数）
 *   · 噪声剔除：跨页重复的页眉页脚、页面首尾的纯页码行、目录页整块 —— 念出来只是干扰
 *   · 去版式硬换行：把"被版面劈成两行"的同一句话接回去（不做的话数字会被拦腰截断）
 *
 * ⚠️ 三级是**降级**关系：L1 准就直接用，L1 没有才看 L2，L2 也没有才用 L3。
 *    日志里会写清这次用的是哪一级（bookmark / tocpage / layout / none）。
 *
 * ⚠️ 内存策略（PRD 明确要求，别改）：
 *   `MemoryUsageSetting.setupTempFileOnly()` 让文档内容落在**临时文件**上而不是全塞进内存，
 *   再配合逐页抽取 —— 300 页的 PDF 在这台 3.6GB 内存的测试机上也不会 OOM。
 *   绝对不要用 `PDDocument.load()` 不传内存设置的默认写法（那会把整本读进内存）。
 */
object PdfReader {

    private const val TAG = "ReadaloudPdf"

    /** 每处理这么多页报一次进度（PRD 要求"分片解析 + 进度"） */
    private const val PAGE_BATCH = 20

    /** 全文上限，防止畸形文件把内存吃光（300 页文档通常 30 万字上下，离这个数很远） */
    private const val MAX_CHARS = 4_000_000

    /**
     * 抽一本 PDF。
     *
     * @param onProgress 进度回调（已处理页数 / 总页数），在调用线程上回调
     * @return 抽不出文字返回 null（典型情况：扫描影印版，整页都是图片、没有文字层）
     */
    fun read(
        context: Context,
        uri: Uri,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): PdfBook? {
        // 必须先初始化资源加载器（库要求，幂等，重复调用没事）
        runCatching { PDFBoxResourceLoader.init(context) }

        var doc: PDDocument? = null
        return try {
            doc = context.contentResolver.openInputStream(uri)?.use { ins ->
                PDDocument.load(ins, MemoryUsageSetting.setupTempFileOnly())
            }
            if (doc == null) {
                Log.w(TAG, "打不开 PDF：$uri")
                null
            } else {
                extract(doc, onProgress)
            }
        } catch (t: Throwable) {
            // 加密的、损坏的、根本不是 PDF 的，都会在这里被接住 —— 不能让它崩到界面上
            Log.w(TAG, "PDF 解析失败：${t.javaClass.simpleName} ${t.message}")
            null
        } finally {
            runCatching { doc?.close() }
        }
    }

    private fun extract(doc: PDDocument, onProgress: ((Int, Int) -> Unit)?): PdfBook? {
        val pageCount = doc.numberOfPages
        if (pageCount <= 0) return null
        Log.i(TAG, "PDF 共 $pageCount 页，开始抽取（临时文件模式）")

        // ① 先读内嵌书签 —— 这是最准的目录来源，正式出版物基本都有
        val bookmarks = readBookmarks(doc)
        Log.i(TAG, "内嵌书签：${bookmarks.size} 条")

        // ② 逐页抽文本（顺便把每行的字号记下来，L3 认标题要用）
        val stripper = runCatching { LayoutStripper() }.getOrElse {
            Log.w(TAG, "文本抽取器初始化失败：${it.message}")
            return null
        }
        // 按位置排序：多栏/复杂版式下比默认的流顺序更接近人的阅读顺序
        stripper.sortByPosition = true

        val pageTexts = ArrayList<String>(pageCount)
        var totalChars = 0
        for (i in 1..pageCount) {
            stripper.startPage = i
            stripper.endPage = i
            val t = runCatching { stripper.getText(doc) }.getOrDefault("")
            pageTexts.add(t)
            totalChars += t.length
            if (i % PAGE_BATCH == 0 || i == pageCount) {
                onProgress?.invoke(i, pageCount)
                Log.i(TAG, "已抽到第 $i/$pageCount 页（累计 $totalChars 字）")
            }
            if (totalChars > MAX_CHARS) {
                Log.w(TAG, "正文超过 $MAX_CHARS 字上限，后面的页不再抽取")
                break
            }
        }

        // ③ 剔噪声：页眉页脚 + 页码行
        val pageLines = pageTexts.map { t ->
            t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        val cleaned = removeNoise(pageLines)

        // ③.1 "印刷页码 → 物理页"映射（L2 定位目录条目要用，见 buildPrintedPageMap）
        val printedMap = buildPrintedPageMap(pageTexts)

        // ③.2 目录块：一边剔掉（不念），一边把条目收起来当目录用（L2）
        val toc = removeTocBlocks(cleaned)

        // ③.3 去掉**版式硬换行** —— 必须在拼全文之前做（见 unwrapLines 的说明）。
        //     不做的话，"2024 年 12\n月期间" 会被断句器当成两句，
        //     数字被拦腰截断（用户听到的是"至20，24年12月"）。
        val joined = unwrapLines(toc.pages)

        // ④ 拼全文，同时记住"每一页从第几个字符开始"（书签只给页号，要靠这张表换算）
        val sb = StringBuilder()
        val pageStart = IntArray(toc.pages.size) { -1 }
        joined.forEach { (page, line) ->
            if (pageStart[page] < 0) pageStart[page] = sb.length
            sb.append(line).append('\n')
        }
        val text = sb.toString().trim()
        if (text.isBlank()) {
            // 一个字都没有 —— 几乎可以断定是扫描影印版
            Log.w(TAG, "抽不出任何文字（$pageCount 页）—— 多半是扫描影印版")
            return null
        }

        // ⑤ 目录重建：三级降级（PRD FR-03）—— L1 书签 → L2 目录页 → L3 版式认标题
        val chapters: List<Pair<String, Int>>
        val tocSource: String
        when {
            bookmarks.isNotEmpty() -> {
                chapters = chaptersFromBookmarks(bookmarks, text, pageStart)
                tocSource = "bookmark"
            }

            toc.entries.isNotEmpty() -> {
                chapters = chaptersFromTocPage(toc.entries, printedMap, text, pageStart)
                tocSource = "tocpage"
            }

            else -> {
                chapters = chaptersFromLayout(stripper.lines, text)
                tocSource = if (chapters.isEmpty()) "none" else "layout"
            }
        }

        val info = runCatching { doc.documentInformation }.getOrNull()
        val title = runCatching { info?.title }.getOrNull().orEmpty().trim()
        val author = runCatching { info?.author }.getOrNull().orEmpty().trim()

        Log.i(
            TAG,
            "PDF 解析完成：书名=${title.ifBlank { "(无元数据)" }} 页数=$pageCount " +
                "字符=${text.length} 章节=${chapters.size} 目录来源=$tocSource"
        )
        return PdfBook(
            title = title,
            author = author,
            text = text,
            chapters = chapters,
            tocSource = tocSource,
            pageCount = pageCount
        )
    }

    // ── L1：内嵌书签 ─────────────────────────────────────────────

    /** 读出全部书签，返回（页索引, 标题），按文档顺序；两层以上用全角空格缩进 */
    private fun readBookmarks(doc: PDDocument): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        val root = runCatching { doc.documentCatalog.documentOutline }.getOrNull()
        if (root == null) {
            Log.i(TAG, "这份 PDF 没有 /Outlines —— 也就是没有内嵌书签")
            return out
        }
        var child: PDOutlineItem? = runCatching { root.firstChild }.getOrNull()
        if (child == null) {
            Log.w(TAG, "有 /Outlines 但取不到第一个书签条目（结构异常）")
        }
        while (true) {
            val c = child ?: break
            collectBookmark(doc, c, out, 0)
            child = runCatching { c.nextSibling }.getOrNull()
        }
        return out
    }

    private fun collectBookmark(
        doc: PDDocument,
        item: PDOutlineItem,
        out: MutableList<Pair<Int, String>>,
        depth: Int
    ) {
        val title = runCatching { item.title }.getOrNull()?.trim().orEmpty()
        // findDestinationPage 能顺带处理"命名目标"（不少 PDF 用命名目标，直接读页号会失败）
        val page: PDPage? = runCatching { item.findDestinationPage(doc) }.getOrNull()
        val idx = pageIndexOf(doc, page)
        when {
            title.isEmpty() -> Log.w(TAG, "书签条目没有标题，跳过")
            idx < 0 -> Log.w(TAG, "书签「$title」定位不到页码，跳过")
            else -> out.add(idx to if (depth > 0) "　".repeat(depth) + title else title)
        }
        var child: PDOutlineItem? = runCatching { item.firstChild }.getOrNull()
        while (true) {
            val c = child ?: break
            collectBookmark(doc, c, out, depth + 1)
            child = runCatching { c.nextSibling }.getOrNull()
        }
    }

    /**
     * 找出某一页在文档里的序号（从 0 开始）。
     *
     * ⚠️ 必须比较**底层 COSDictionary**，不能比较 PDPage 对象（踩过）：
     *   PDFBox 的 `pages.get(i)` 每次调用都会**新建一个 PDPage 包装对象**，
     *   所以 `pages.get(i) === page` 永远是 false ——
     *   结果是所有书签都被判成"定位不到页码"而丢掉。
     *   现象很迷惑：电脑上用 Python 能读出 10 条书签，App 里却是 0 条。
     *   底层 COSDictionary 由文档统一缓存、同一个页面永远是同一个实例，
     *   拿它做引用比较才可靠。
     */
    private fun pageIndexOf(doc: PDDocument, page: PDPage?): Int {
        if (page == null) return -1
        val target = page.getCOSObject()
        val pages = doc.pages
        for (i in 0 until pages.count) {
            if (pages.get(i).getCOSObject() === target) return i
        }
        return -1
    }

    // ── 目录重建：三级降级 ───────────────────────────────────────

    /**
     * L1：内嵌书签。
     *
     * ⚠️ 定位时用"标题在正文里第一次出现的位置"校准一次：
     *    拼接过程会去掉页眉页脚和空行，字符总数和原始页码换算可能有细微出入，
     *    直接用 pageStart 会让章节位置差出几十个字。
     */
    private fun chaptersFromBookmarks(
        bookmarks: List<Pair<Int, String>>,
        text: String,
        pageStart: IntArray
    ): List<Pair<String, Int>> = bookmarks.mapNotNull { (pageIdx, title) ->
        // 页码可能指向"被整页剔掉的目录页"，这时给 0，让后面的标题搜索从头找
        val raw = (pageStart.getOrNull(pageIdx) ?: -1).coerceAtLeast(0)
        val probe = (raw - 200).coerceAtLeast(0)
        val found = text.indexOf(title.trim(), probe)
        title.trim() to (if (found >= 0) found else raw)
    }

    /**
     * L2：从目录页的「标题 …… 页码」行里抽条目。
     *
     * 目录页上写的是**印刷页码**，而 PDF 的物理页序号通常对不上
     * （前面还有封面、目录页自己），所以要先用 [buildPrintedPageMap] 建一张映射表。
     */
    private fun chaptersFromTocPage(
        entries: List<Pair<String, Int>>,
        printedMap: Map<Int, Int>,
        text: String,
        pageStart: IntArray
    ): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var viaPage = 0
        var viaSearch = 0
        var missing = 0
        for ((rawTitle, printed) in entries) {
            val title = rawTitle.trim()
            if (title.isEmpty()) continue
            val phys = printedMap[printed]
            var offset = -1
            if (phys != null && phys < pageStart.size) {
                offset = text.indexOf(title, (pageStart[phys] - 200).coerceAtLeast(0))
                if (offset >= 0) viaPage++
            }
            if (offset < 0) {
                // 兜底：页码对不上就直接全篇找这个标题
                offset = text.indexOf(title)
                if (offset >= 0) viaSearch++ else missing++
            }
            if (offset >= 0) out.add(title to offset)
        }
        Log.i(
            TAG,
            "L2 目录页：抽到 ${entries.size} 条 → 成功 ${out.size} 条" +
                "（按页码定位 $viaPage，全篇搜索 $viaSearch，找不到 $missing）"
        )
        return out
    }

    /**
     * L3：既没书签也没目录页时，靠**版式**认标题。
     *
     * 判据（就一条，但很有效）：**字号明显大于正文**。
     *   · 正文字号 = 按字符数加权的众数 —— 页眉页脚字号小但字少，会被自然忽略
     *   · 阈值取正文的 1.08 倍：小于它的变化是排版噪声，大于它的基本就是标题
     *   · 再排除"以句末标点结尾"的行（那是正文句子）
     *   · 长度 ≤ 40 字（长句子不会整句都放大）
     *
     * ⚠️ 已知局限：正文与标题同字号（比如整篇都是加粗但字号一样）时认不出来。
     *    这时只能靠编号（第一、/ 1.2 这类），而那些在下一层由正则兜底。
     */
    private fun chaptersFromLayout(
        lines: List<LayoutLine>,
        text: String
    ): List<Pair<String, Int>> {
        if (lines.isEmpty()) return emptyList()

        val weight = HashMap<Float, Int>()
        for (l in lines) {
            val key = Math.round(l.size * 2f) / 2f
            weight[key] = (weight[key] ?: 0) + l.text.length
        }
        val bodySize = weight.maxByOrNull { it.value }?.key ?: 0f
        val threshold = bodySize * 1.08f

        val titles = LinkedHashSet<String>()
        for (l in lines) {
            if (l.size < threshold) continue
            if (l.text.length > 40) continue
            if (l.text.last() in SENTENCE_PUNCT) continue
            titles.add(l.text.trim())
        }

        // ⚠️ 定位前先把正文里的空白"投影掉"（见 CompactText 的说明）：
        //    版式行是拿 TextPosition 一个个拼出来的（空白会被过滤掉），
        //    而正文是 PDFTextStripper 自己拼的（空格保留）——
        //    「1. 数据抽取」vs「1.数据抽取」只差一个空格，indexOf 就永远找不到。
        val compact = CompactText(text)

        // 标题在全文里**顺序定位**（从上一个标题的后面接着找），避免同名/重复标题全落到开头
        val out = ArrayList<Pair<String, Int>>()
        var cursor = 0          // 注意：这是"去空白文本"里的下标
        var fallback = 0
        for (t in titles) {
            if (t.isEmpty()) continue
            val key = compact.of(t)
            var at = compact.indexOf(key, cursor)
            if (at < 0 && cursor > 0) {
                // 兜底：候选顺序万一跟正文顺序对不上（旋转页、多栏版式），
                // 别因为"游标已经走过去了"就把这个标题整个丢掉 —— 从头再找一次。
                at = compact.indexOf(key, 0)
                if (at >= 0) fallback++
            }
            if (at >= 0) {
                out.add(t to compact.originalIndex(at))
                cursor = at + key.length
            } else {
                // ⚠️ 定位失败要能查：把附近的正文打出来，才能看出差在哪。
                val near = compact.indexOf(compact.of(t.take(2)), 0)
                Log.w(
                    TAG,
                    "L3 标题「$t」在正文里定位失败；正文开头：${text.take(60)}" +
                        "；含前两字的片段位置=$near"
                )
            }
        }
        // ⚠️ 必须按"在正文里的先后"排一遍：候选的顺序来自版式行的收集顺序，
        //    万一它跟阅读顺序不一致（旋转页、多栏），目录就会倒过来 ——
        //    而目录一旦不是文档顺序，"下一章/念完本章后停"这些全会错。
        out.sortBy { it.second }
        if (fallback > 0) Log.w(TAG, "L3 有 $fallback 个标题靠兜底找回（候选顺序与正文不符）")
        Log.i(
            TAG,
            "L3 版式认标题：正文字号 ${fmtSize(bodySize)}，候选 ${titles.size} 个 → " +
                "定位成功 ${out.size} 个（依次：${out.take(6).joinToString(" / ") { it.first }}…）"
        )
        if (titles.isNotEmpty()) {
            Log.i(TAG, "L3 候选清单：${titles.joinToString(" | ")}")
        }
        return out
    }

    /**
     * 正文的"去空白"投影 + 位置回映射（L3 定位专用）。
     *
     * ⚠️ 为什么非要有它（实测踩过）：
     *   L3 的候选标题来自我们自己拼的**版式行**（[LayoutStripper] 拿 TextPosition
     *   一个个拼，空白字符会被过滤掉），而正文 [text] 是 PDFTextStripper 自己拼的
     *   （空格留着）。于是同一个小标题，一边是「1.数据抽取」、另一边是「1. 数据抽取」——
     *   只差一个空格，`indexOf` 就是找不到，标题全被判为"定位失败"。
     *   做法：把正文也去一次空白（记下每个字符在原文里的下标），两边同一口径再找，
     *   找到后映射回**原文下标** —— 下游要靠这个偏移换算"第几句"，必须是原文坐标。
     */
    private class CompactText(private val src: String) {
        private val compact = StringBuilder(src.length)
        private val back = IntArray(src.length)

        init {
            for (i in src.indices) {
                if (!src[i].isWhitespace()) {
                    back[compact.length] = i
                    compact.append(src[i])
                }
            }
        }

        /** 把要查找的串也做成同一口径（去空白） */
        fun of(s: String): String {
            if (s.none { it.isWhitespace() }) return s
            val b = StringBuilder(s.length)
            for (c in s) if (!c.isWhitespace()) b.append(c)
            return b.toString()
        }

        /** 在去空白文本里查找，返回的是**去空白文本**的下标（-1 找不到） */
        fun indexOf(key: String, from: Int): Int {
            if (key.isEmpty()) return -1
            return compact.indexOf(key, from.coerceAtLeast(0))
        }

        /** 把去空白文本的下标换回原文下标 */
        fun originalIndex(compactIdx: Int): Int = back[compactIdx]
    }

    private fun fmtSize(v: Float): String =
        if (v <= 0f) "未知" else "${Math.round(v * 10) / 10f}pt"

    /** 句子级别的标点：这些结尾的行是正文，不是标题 */
    private val SENTENCE_PUNCT = charArrayOf('。', '！', '？', '；', '，', '、', '…')

    /**
     * "印刷页码 → PDF 物理页序号"的映射。
     *
     * ⚠️ 为什么必须有（L2 的前提）：目录页上写「第一章 ……… 1」，
     *    那个 1 是书上印的页码；而它在 PDF 文件里往往是第 3 页（封面、目录页占了前面）。
     *    不建这张表，目录里的每一条都会定位失败。
     *
     * 做法：每页的首尾两行里找"像页码的行"（"第 3 页" / "- 3 -" / "3" / "Page 3"）。
     */
    private fun buildPrintedPageMap(pageTexts: List<String>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        pageTexts.forEachIndexed { i, t ->
            val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val edge = (lines.take(2) + lines.takeLast(2)).distinct()
            for (ln in edge) {
                val n = pageNumberOf(ln)
                if (n != null) {
                    out.putIfAbsent(n, i)
                    break
                }
            }
        }
        Log.i(TAG, "印刷页码→物理页：$out")
        return out
    }

    /** 像页码的行里，那个页码数字是多少 */
    private fun pageNumberOf(line: String): Int? {
        val m = PAGE_NUMBER.find(line) ?: return null
        for (g in m.groupValues) g.toIntOrNull()?.let { return it }
        return null
    }

    // ── 版式硬换行 ───────────────────────────────────────────────

    /** 一行正文，外加"它属于第几页"（拼全文时要靠页码算书签的位置） */
    private data class PageLine(val page: Int, val text: String)

    /** 一行文字的版式信息（L3 认标题靠字号） */
    private data class LayoutLine(
        val page: Int,
        val text: String,
        val size: Float,
        val x: Float,
        val y: Float
    )

    /**
     * 抽取正文的同时，把每一行的**字号和坐标**也记下来（L3 认标题要用）。
     *
     * ⚠️ 为什么必须自己写子类：PDFTextStripper 只吐纯文本，
     *    字号/坐标全都只在 `processTextPosition` 里经过一次，错过就再也拿不到。
     * ⚠️ 覆盖它之后**一定要调 super**，否则正文一行都抽不出来（会静默变成空字符串）。
     *
     * 行的归并方式：按 y 坐标分组（同一行的 y 会有零点几个点的抖动，所以按 0.5pt 取整），
     * 组内按 x 从小到大拼成一行 —— 与 PDFBox 自己分行���结果一致。
     */
    private class LayoutStripper : PDFTextStripper() {
        val lines = ArrayList<LayoutLine>()
        private val buf = ArrayList<TextPosition>()
        private var pageIdx = -1

        override fun startPage(page: PDPage) {
            super.startPage(page)
            pageIdx++
            buf.clear()
        }

        override fun processTextPosition(text: TextPosition) {
            if (text.unicode.isNotBlank()) buf.add(text)
            super.processTextPosition(text)
        }

        override fun endPage(page: PDPage) {
            val byY = LinkedHashMap<Float, MutableList<TextPosition>>()
            for (t in buf) {
                val y = Math.round(t.yDirAdj * 2f) / 2f
                byY.getOrPut(y) { ArrayList() }.add(t)
            }
            // ⚠️ 必须**升序**：PDFBox 的 yDirAdj 是"方向校正后"的坐标 ——
            //    它的值**从上往下递增**（页面顶部接近 0）。以前写成降序，
            //    结果每页的行都是从下往上收集的 → L3 的候选标题整体倒序，
            //    定位游标一开头就跑到文末，其余标题全"落在游标前面"而找不到
            //    （实测症状：一份 6 个标题的 PDF 只认出 1 个）。
            for ((y, group) in byY.entries.sortedBy { it.key }) {
                val sorted = group.sortedBy { it.xDirAdj }
                val line = sorted.joinToString("") { it.unicode }.trim()
                if (line.isEmpty()) continue
                // 一行里字号可能略有差异（中西文混排），取最常见的那个
                val size = sorted.groupingBy { it.fontSizeInPt }.eachCount()
                    .maxByOrNull { it.value }?.key ?: 0f
                lines.add(LayoutLine(pageIdx, line, size, sorted.first().xDirAdj, y))
            }
            super.endPage(page)
        }
    }

    /**
     * 去掉 PDF 的**版式硬换行**：把"同一句话被排版劈成的多行"接回一行。
     *
     * ⚠️ 这是用户验收反馈过的真实问题（"2024年1月至2024年12月 被读成 至20，24年12月"）：
     *   PDF 里的换行由**版面宽度**决定，跟句子在哪儿结束没关系：
     *       本次审查覆盖 2024 年 1 月至 2024 年 12      ← 这行结尾不是句子结束
     *       月期间，公司全部业务系统……                  ← 其实是同一句话
     *   而断句器把 `\n` 当句末标点 —— 于是年份被劈成两句、分别合成，
     *   中间还夹一段静音。"ISO 27001" 被劈成 "ISO 27" + "001" 同理。
     *
     * 判定规则在 [LineBreaks.shouldJoin]（保守优先：标题行绝不接）。
     * 拼接时可能**跨页**：上一页最后一行没说完，正好和下一页第一行接上。
     */
    private fun unwrapLines(pages: List<List<String>>): List<PageLine> {
        val flat = ArrayList<PageLine>()
        pages.forEachIndexed { i, lines -> lines.forEach { flat.add(PageLine(i, it)) } }

        val out = ArrayList<PageLine>()
        for (p in flat) {
            val prev = out.lastOrNull()
            if (prev != null && LineBreaks.shouldJoin(prev.text, p.text, ::heading)) {
                out[out.size - 1] = PageLine(
                    prev.page,
                    prev.text + LineBreaks.glue(prev.text, p.text) + p.text
                )
            } else {
                out.add(p)
            }
        }
        val merged = flat.size - out.size
        if (merged > 0) Log.i(TAG, "去掉版式硬换行：$merged 行接了回来（同一句话被排版切断的）")
        return out
    }

    /**
     * 判断"像不像标题行" —— 复用 TextFileReader 那套规则，别各写一份。
     *
     * ⚠️ 这里用的是**宽松版**：技术文档里的「一、总体要求」「（一）数据抽取」
     *    「1. 数据抽取」严格的规则认不出来，而它们一旦被当成普通正文行，
     *    去硬换行时就会把标题和下一段正文粘成一行 —— 标题也就废了。
     *    （认章节仍然用严格版，别让正文里的编号列表全变成章节。）
     */
    private fun heading(t: String): Boolean = TextFileReader.looksLikeHeadingLoose(t)

    // ── 噪声剔除 ─────────────────────────────────────────────────

    /**
     * 去掉页眉、页脚和页码行。
     *
     * 思路（PRD 说的"同 y 区高频重复串"的简化版）：
     *   · 每页的**首行**如果在一半以上的页里都一模一样 → 那是页眉，删掉
     *   · 每页的**末行**同理 → 页脚
     *   · 页面首尾两行里，长得像页码的（"12"、"- 12 -"、"第 12 页"）→ 删掉
     *
     * ⚠️ 页码只在页面首尾两行里才删：正文中间出现的纯数字行（比如表格里的一格）
     *    是有意义的内容，删了就丢字了。
     * ⚠️ 页数少于 3 页时什么都不删 —— 样本太少，"重复"判断不可靠。
     */
    private fun removeNoise(pages: List<List<String>>): List<List<String>> {
        if (pages.size < 3) return pages
        val n = pages.size
        val threshold = maxOf(2, n / 2)

        fun mostRepeated(pick: (List<String>) -> String?): String? {
            val counts = HashMap<String, Int>()
            pages.forEach { p ->
                pick(p)?.let { counts[it] = (counts[it] ?: 0) + 1 }
            }
            return counts.entries.maxByOrNull { it.value }?.takeIf { it.value >= threshold }?.key
        }

        val header = mostRepeated { it.firstOrNull() }
        val footer = mostRepeated { it.lastOrNull() }
        if (header != null || footer != null) {
            Log.i(TAG, "剔除重复页眉/页脚：header=${header ?: "-"} footer=${footer ?: "-"}")
        }

        return pages.map { lines ->
            var l = lines
            if (header != null && l.firstOrNull() == header) l = l.drop(1)
            if (footer != null && l.lastOrNull() == footer) l = l.dropLast(1)
            l.filterIndexed { idx, line ->
                val nearEdge = idx < 2 || idx >= l.size - 2
                !(nearEdge && PAGE_NUMBER.matches(line))
            }
        }
    }

    /** 像页码的行："12"、"- 12 -"、"第 12 页"、"Page 12"、"12 / 30" */
    private val PAGE_NUMBER = Regex(
        """^[\s\-—–_·.]*(\d{1,4})[\s\-—–_·.]*$""" + "|" +
            """^第\s*(\d{1,4})\s*页$""" + "|" +
            """^[Pp][Aa][Gg][Ee]\s*(\d{1,4})(\s*/\s*\d{1,4})?$""" + "|" +
            """^(\d{1,4})\s*/\s*\d{1,4}$"""
    )

    /** 目录行的解析结果：剔干净之后的正文 + 顺手抽出来的目录条目（L2 用） */
    private data class TocBlockResult(
        val pages: List<List<String>>,
        val entries: List<Pair<String, Int>>
    )

    /** 目录行：「第一章 审查概况 ………………………… 1」这种"标题 + 引导点 + 页码" */
    private val TOC_LINE = Regex("""^\s*(.{2,40}?)\s*[.·…⋯]{3,}\s*(\d{1,4})\s*$""")

    /**
     * 剔除目录页内容（PRD FR-20 第 1 条里的"目录块"），**同时把目录条目收出来**。
     *
     * ⚠️ 一举两用是关键：目录页内容必须剔掉（念出来就是一串"点点点点点、一"的噪音，
     *    而且每次从头听都得先听一遍），但它同时是 L2 的目录来源 ——
     *    剔之前先把「标题 + 页码」抽出来，正文里就不用这本书了。
     *
     * 判定方式：一页里出现 3 行以上这种"引导点 + 页码"的格式，就认为这页是目录。
     */
    private fun removeTocBlocks(pages: List<List<String>>): TocBlockResult {
        var removed = 0
        val entries = ArrayList<Pair<String, Int>>()
        val out = pages.map { lines ->
            val tocCount = lines.count { TOC_LINE.matches(it) }
            // 少于 3 行不成块 —— 正文里偶尔出现的省略号不该被误伤
            if (tocCount < 3) {
                lines
            } else {
                removed += tocCount
                for (l in lines) {
                    val m = TOC_LINE.find(l) ?: continue
                    val title = m.groupValues[1].trim()
                    val page = m.groupValues[2].toIntOrNull() ?: continue
                    if (title.isNotEmpty()) entries.add(title to page)
                }
                val kept = lines.filterNot { TOC_LINE.matches(it) }
                // 整页基本都是目录（剩下的只是"目录"两个字这种）→ 整页丢掉
                if (kept.size <= 2) emptyList() else kept
            }
        }
        if (removed > 0) Log.i(TAG, "剔除目录块：$removed 行「标题……页码」（条目已收走当目录用）")
        return TocBlockResult(out, entries)
    }
}
