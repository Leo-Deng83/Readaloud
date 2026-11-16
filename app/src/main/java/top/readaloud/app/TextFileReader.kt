package top.readaloud.app

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * 一句话。
 * @param text 这句话的内容
 * @param start 这句话在整篇文章里，从第几个字符开始的（用来定位章节）
 */
data class Sentence(val text: String, val start: Int)

/**
 * 一个章节。
 * @param title 章节标题，比如「第一章 初见」
 * @param startSentence 这一章从 sentences 里的第几句话开始
 */
data class Chapter(val title: String, val startSentence: Int)

/**
 * 交给语音引擎合成的一个"段落"。
 * @param startSentence 这段从第几句开始（用来在屏幕上高亮）
 * @param text 这段的内容
 * @param isChapter 是不是章节标题（是的话朗读前后要留更长的停顿）
 */
data class TtsGroup(val startSentence: Int, val text: String, val isChapter: Boolean)

object TextFileReader {

    /**
     * 章节标题的识别规则（PRD FR-01）。
     * 四条规则，从上到下依次匹配：
     *   1. 中文：第X章 / 第X节 / 第X回……
     *   2. 英文：Chapter 1 / Part II……
     *   3. 特殊：序 / 楔子 / 前言 / 后记 / 附录……
     *   4. 编号式：1.1 / 2.3.1 开头的小标题
     *
     * ⚠️ 第 4 条是后补的（踩过）：PDF 报告、技术文档里到处都是「1.2 审查方法」
     *    这种编号小标题，原来的三条规则都不认 —— 结果它被当成"太短的句子"
     *    并进前一句，界面上就出现「……10% 下限。1.2 审查方法」这样的怪段落，
     *    朗读时这一节的开头也没有应有的停顿。
     *    ⚠️ 故意要求"至少有一个点"（\d.\d）：像"1. 第一步"这种列表项不会被误认成标题。
     */
    private val CHAPTER_REGEX = Regex(
        """^\s*第[零一二三四五六七八九十百千万两0-9]+[章节回卷篇部集][\s、:：.]?.*$|""" +
            """^\s*(Chapter|CHAPTER|Part|PART)\s+[IVXLCDM0-9]+\b.*$|""" +
            """^\s*(序|序言|楔子|引子|前言|后记|尾声|附录|番外).*$|""" +
            """^\s*\d{1,3}(?:\.\d{1,3}){1,3}[\s、:：]\s*[^\s\d].{0,24}$"""
    )

    /** 断句用的标点符号：遇到这些就认为一句话结束 */
    private val SENTENCE_END = charArrayOf('。', '！', '？', '；', '…', '\n')

    /** 一句话最长多少字（PRD FR-20 第 4 条），超过就在逗号处二次切分 */
    private const val MAX_SENTENCE_CHARS = 60

    /** 一句话最短多少字，太短就并进前一句（避免「是我。」这种碎句反复打断节奏） */
    private const val MIN_SENTENCE_CHARS = 12

    /**
     * 读取用户选中的文件，并自动判断编码。
     *
     * 为什么需要判断编码？因为同一个「中文.txt」，
     * Windows 老电脑上存的可能是 GBK，Mac/新软件存的一般是 UTF-8。
     * 用错编码打开就会看到一堆乱码。
     */
    fun read(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return ""
        return decode(bytes)
    }

    /** 编码探测：先看文件头的 BOM 标记，再试 UTF-8，最后退回 GB18030（兼容 GBK） */
    fun decode(bytes: ByteArray): String {
        // 1) 带 BOM 的（文件开头的几个特殊字节，相当于自报家门）
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, Charsets.UTF_16BE)
        }
        // 2) 严格按 UTF-8 解：只要有一个字节解释不通，就说明它不是 UTF-8
        tryDecode(bytes, Charset.forName("UTF-8"))?.let { return it }
        // 3) 否则按中文国标编码解（GB18030 向下兼容 GBK、GB2312）
        return tryDecode(bytes, Charset.forName("GB18030")) ?: String(bytes, Charsets.UTF_8)
    }

    /** 用指定编码严格解码；遇到解释不通的字节就返回 null（而不是用 '?' 糊弄过去） */
    private fun tryDecode(bytes: ByteArray, charset: Charset): String? {
        return try {
            val decoder: CharsetDecoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    /**
     * 把一整篇文章切成一句一句。
     *
     * 为什么切成句子？两个原因：
     *   1. 朗读时要一句一句来，才能知道"现在读到哪了"，才能高亮
     *   2. 系统 TTS 一次能读的文字长度有限，太长会被截断
     *
     * PRD FR-20 第 4 条给句子加了长度约束（12–60 字），所以这里是三步：
     *   ① 按句末标点粗切 → ② 超长句在逗号处再切一刀 → ③ 过短句并进前一句
     */
    fun splitSentences(text: String): List<Sentence> {
        // ① 按句末标点粗切
        val rough = ArrayList<Sentence>()
        val sb = StringBuilder()
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            sb.append(c)
            if (c in SENTENCE_END) {
                // 太短的碎片（比如只是一个换行）先丢掉，后面会靠合并补回来
                val s = sb.toString().trim()
                if (s.isNotEmpty() && s.length >= 2) {
                    rough.add(Sentence(s, start))
                }
                start = i + 1
                sb.clear()
            }
        }
        val last = sb.toString().trim()
        if (last.isNotEmpty()) rough.add(Sentence(last, start))

        // ② 超长句在逗号/顿号处二次切分。
        //    ⚠️ 一句 100 多字塞给模型，它自己会在莫名其妙的地方断，节奏很乱；
        //       而在逗号处切，是我们能控制的最自然的位置。
        val split = ArrayList<Sentence>()
        rough.forEach { s ->
            if (s.text.length <= MAX_SENTENCE_CHARS) split.add(s) else split.addAll(splitLong(s))
        }

        // ③ 过短句向前合并（PRD：过短句合并）。
        //    ⚠️ 但**章节标题绝不能被并掉** —— 标题必须独立成句，
        //       否则 groupForTts 就不会给它单独分段，朗读时那一章的开头会没有停顿。
        //    ⚠️ 这里必须用**宽松版**判定标题（踩过）：技术文档/PDF 的小标题
        //       写成「一、总体要求」「1. 数据抽取」，严格版认不出来，
        //       于是被并进上一段 —— 表现就是"点这个章节，却从上一段开头念起"
        //       （实测：点「1. 数据抽取」，高亮落在一整段正文的开头）。
        //       （认"章节"仍然用严格版，别让正文里的编号列表全变成章节。）
        val merged = ArrayList<Sentence>()
        for (s in split) {
            val prev = merged.lastOrNull()
            if (prev != null && s.text.length < MIN_SENTENCE_CHARS &&
                !looksLikeHeadingLoose(s.text) && !looksLikeHeadingLoose(prev.text)
            ) {
                merged[merged.size - 1] = Sentence(prev.text + s.text, prev.start)
            } else {
                merged.add(s)
            }
        }
        return merged
    }

    /** 在本句内部的逗号/顿号处切开，每段尽量凑到够长 */
    private fun splitLong(s: Sentence): List<Sentence> {
        val text = s.text
        val out = ArrayList<Sentence>()
        val sb = StringBuilder()
        var segmentStart = 0
        for (i in text.indices) {
            sb.append(text[i])
            val isBreak = text[i] in charArrayOf('，', '、', '；', '：')
            if (isBreak && sb.length >= MIN_SENTENCE_CHARS) {
                out.add(Sentence(sb.toString().trim(), s.start + segmentStart))
                segmentStart = i + 1
                sb.clear()
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) {
            val prevSeg = out.lastOrNull()
            if (prevSeg != null && tail.length < MIN_SENTENCE_CHARS) {
                // 尾巴太短并回上一段，避免又造出一个碎句
                out[out.size - 1] = Sentence(prevSeg.text + tail, prevSeg.start)
            } else {
                out.add(Sentence(tail, s.start + segmentStart))
            }
        }
        return out
    }

    /**
     * 长得像不像一个章节标题（用来保护它不被合并）。
     *
     * ⚠️ 这个判断被**两个地方**用，所以是 public 而不是 private：
     *    · 这里 —— 别把标题并进前一句
     *    · PdfReader —— 去版式硬换行时，标题行不能被接到上一行后面
     *    （同一套规则只写一份，否则两处判断不一致，问题会非常难查。）
     */
    fun looksLikeHeading(t: String): Boolean {
        val s = t.trim()
        return s.length in 1..40 && CHAPTER_REGEX.matches(s)
    }

    /**
     * 把碎句子并成"够长的一段"，专门给语音合成用。
     *
     * 为什么需要？实测发现：合成一句话的**固定开销**很大（约 7 秒），
     * 跟这句话长短关系不大。所以像「是我。」这种 4 个字的句子，
     * 合成要 7 秒、播出来才 1.5 秒 —— 严重入不敷出，听起来就是"卡、卡、卡"。
     * 并成 80 字左右的一段再合成，同样的 7 秒能换来十几秒的音频，就顺畅多了。
     *
     * @return 每个元素是「这一段从第几句开始」+「这一段的内容」
     */
    fun groupForTts(
        sentences: List<Sentence>,
        minChars: Int = 40,
        chapterStarts: Set<Int> = emptySet(),
        firstMinChars: Int = 12
    ): List<TtsGroup> {
        if (sentences.isEmpty()) return emptyList()
        val out = ArrayList<TtsGroup>()
        var i = 0
        while (i < sentences.size) {
            val start = i
            // 章节标题单独成一段，这样它前后能有明显的停顿，不会被当成正文一口气念过去
            val isChapter = start in chapterStarts
            // ⚠️ 第一段刻意切小一点：让"第一句话的声音"尽快出来。
            //    合成一段的耗时主要跟字数有关，第一段太长的话，
            //    用户点完朗读要等好几十秒才听到声，会以为软件坏了。
            val limit = if (out.isEmpty()) firstMinChars else minChars
            val sb = StringBuilder()
            if (isChapter) {
                sb.append(sentences[i].text)
                i++
            } else {
                while (i < sentences.size && sb.length < limit && i !in chapterStarts) {
                    sb.append(sentences[i].text)
                    i++
                }
            }
            out.add(TtsGroup(start, sb.toString(), isChapter))
        }
        return out
    }

    /**
     * 宽松版"像不像标题"——**只用于保护标题行**，不用于认章节。
     *
     * ⚠️ 为什么要多这一套（PDF 的 L3 逼出来的）：
     *   严格的 [CHAPTER_REGEX] 只认"第X章 / Chapter / 1.2 / 序"，
     *   而技术文档里满地都是「一、总体要求」「（一）数据抽取」「1. 数据抽取」这种标题。
     *   它们在严格的规则眼里是普通正文行 —— 而 PDF 去版式硬换行时
     *   "结尾没有句末标点就与下一行接上"，于是标题会被和下面一段正文粘成一行，标题就废了。
     *
     * ⚠️ 认章节仍然只用严格的 [CHAPTER_REGEX]：
     *   放宽认章节会让正文里的编号列表（"1. ……" "2. ……"）全变成章节，噪音太大。
     */
    private val LOOSE_HEADING = Regex(
        """^\s*[一二三四五六七八九十百]+\s*[、.．]\s*\S.{0,30}$""" + "|" +
            """^\s*[（(]\s*[一二三四五六七八九十]+\s*[）)]\s*\S.{0,30}$""" + "|" +
            """^\s*\d{1,2}\s*[.、．]\s*[^\s\d].{0,30}$"""
    )

    fun looksLikeHeadingLoose(t: String): Boolean {
        val s = t.trim()
        return s.length in 1..40 &&
            (CHAPTER_REGEX.matches(s) || LOOSE_HEADING.matches(s))
    }

    /**
     * 把"章节标题在全文里的字符位置"换算成"从第几句开始"。
     *
     * EPUB 的目录（nav / NCX）给的是标题 + 它在正文里的位置，
     * 而界面/播放服务要的是"第几句" —— 这里做一次换算。
     *
     * ⚠️ 必须再用**标题文本**在附近校准一次（不能只信偏移）：
     *   解析出来的文本还会经过结构噪声清理（URL、引文编号会被删掉），
     *   字符总数因此变少、偏移整体前移 —— 只按偏移找，章节会落到差几句话的位置。
     *   所以先按偏移粗定位，再在附近一段区间里找"哪一句里含有这个标题"。
     */
    fun chaptersAtOffsets(
        sentences: List<Sentence>,
        offsets: List<Pair<String, Int>>
    ): List<Chapter> {
        if (sentences.isEmpty()) return emptyList()
        return offsets.map { (title, offset) ->
            val guess = sentences.indexOfFirst { it.start >= offset }
                .let { if (it < 0) 0 else it }
            // 去掉层级缩进（PDF 书签的子级用全角空格缩进）再比对
            val key = title.trim().trimStart('　').trim()
            var resolved = guess
            if (key.isNotEmpty()) {
                val from = (guess - 12).coerceAtLeast(0)
                val to = (guess + 40).coerceAtMost(sentences.size)
                val hit = (from until to).firstOrNull { sentences[it].text.contains(key) }
                    // ⚠️ 兜底：PDF 抽出的正文里常多带空格（「1. 数据抽取」），
                    //    而标题可能来自版式行/书签（「1.数据抽取」）——
                    //    两边去掉空白再比一次，否则这一步"校准"会白过，
                    //    章节就退回"只按字符偏移猜"，前面对齐差一点就错一句话。
                    ?: (from until to).firstOrNull {
                        looseSpace(sentences[it].text).contains(looseSpace(key))
                    }
                if (hit != null) resolved = hit
            }
            Chapter(title, resolved)
        }
    }

    /** 去掉所有空白的副本（比对标题用，见 chaptersAtOffsets 的兜底） */
    private fun looseSpace(s: String): String =
        if (s.none { it.isWhitespace() }) s else s.filterNot { it.isWhitespace() }

    /**
     * 识别章节目录（PRD FR-01 的简化版，够 v0.1 用）。
     * 做法：一行一行扫描，符合章节标题规则的行就记为一个章节，
     * 再根据它出现在文章的第几个字符，换算成 sentences 里的第几句。
     */
    fun detectChapters(text: String, sentences: List<Sentence>): List<Chapter> {
        val chapters = ArrayList<Chapter>()
        var offset = 0
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.length in 1..40 && CHAPTER_REGEX.matches(trimmed)) {
                val index = sentences.indexOfFirst { it.start >= offset }
                chapters.add(Chapter(trimmed, if (index < 0) 0 else index))
            }
            offset += line.length + 1
        }
        return chapters
    }
}
