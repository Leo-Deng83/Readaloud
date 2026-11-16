package top.readaloud.app

import android.util.Log

/**
 * 正文结构噪声清理（PRD FR-20 第 1 条）。
 *
 * ⚠️ 为什么必须做：
 *   下面这些东西念出来纯粹是噪音 —— 一串"w w w 点 example 点 org 斜杠 archive"，
 *   或者"左方括号十二右方括号"，听众只能干等着它过去。
 *   （PRD 把它排在正文清洗的第一条，原话是"结构噪声删除：目录块、[12] 式引文编号、
 *   URL、邮箱、ISBN、代码块、表格边框符"。）
 *
 * ⚠️ 清理发生在**切句之前**（BookReader 里），所以界面显示和朗读都变干净 ——
 *   这跟 TextNormalizer 不一样：那个只改送去合成的文本，界面上还显示原文。
 *   这里删掉的东西，屏幕上也不该出现。
 *
 * ⚠️ 注意：清理会改变字符偏移，而章节定位正是靠"标题在正文里的位置"换算的。
 *   所以 chaptersAtOffsets 里加了一步"按标题文本校准"（见那个函数的说明）。
 */
object TextCleaner {

    private const val TAG = "ReadaloudClean"

    /** URL：http(s):// 或 www. 开头 */
    private val URL = Regex(
        """(?:https?://|www\.)[^\s，。；、！？）】」』"']+""",
        RegexOption.IGNORE_CASE
    )

    /** 邮箱 */
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    /** ISBN（含 10 位/13 位、带连字符或不带） */
    private val ISBN = Regex("""ISBN[\s:：]*[\d][\d\-\s]{8,20}[\dXx]""", RegexOption.IGNORE_CASE)

    /**
     * 引文编号：[12] / [13-15] / 〔7〕。
     *
     * ⚠️ 判定要看**后面的字符**，不是前面的（这里踩过一次）：
     *    · "稿末附着一行脚注[12]，" —— 该删，但它前面是汉字"注"；
     *    · "第[1]章" —— 不能删，它后面是汉字"章"。
     *    所以规则是：编号后面必须跟标点、空白或行尾，才认定它是独立的引文编号。
     */
    private val CITATION = Regex(
        """\s*[\[［〔]\s*\d{1,3}(?:\s*[-–,，]\s*\d{1,3})*\s*[\]］〕](?=\s|[，。；、：！？）】」』]|$)"""
    )

    /** 连续空白 */
    private val MULTI_SPACE = Regex("""[ \t\u00A0]{2,}""")

    /** 标点前面的空白（删掉噪声后容易留下"了 ，"这种残迹） */
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([，。；、：！？])""")

    /** 中文之间的空格 —— 朗读时会被当成停顿，听起来一顿一顿的，直接去掉 */
    private val SPACE_BETWEEN_CJK = Regex("""(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])""")

    /** 挨在一起的重复标点（"：，"这种删除噪声后留下的残渣） */
    private val DUP_PUNCT = Regex("""([，。；、：！？])\s*[，。；、：！？]+""")

    /**
     * 清掉结构噪声。
     *
     * @return 清理后的正文（整行都是噪声的行会被整行丢掉）
     */
    fun clean(text: String): String {
        if (text.isBlank()) return text
        var removedChars = 0
        var removedLines = 0
        val out = StringBuilder()

        for (line in text.lines()) {
            val before = line.length
            var l = line
            l = URL.replace(l, "")
            l = EMAIL.replace(l, "")
            l = ISBN.replace(l, "")
            l = CITATION.replace(l, "")
            l = tidy(l)

            if (l.isBlank()) {
                // 整行都是噪声（比如一行只有"[12]"）—— 直接丢掉，别留个空行
                if (before > 0) removedLines++
                continue
            }
            removedChars += before - l.length
            out.append(l).append('\n')
        }

        if (removedChars > 0 || removedLines > 0) {
            Log.i(
                TAG,
                "结构噪声清理：去掉约 $removedChars 个字符、$removedLines 整行噪声" +
                    "（URL / 邮箱 / 引文编号 / ISBN）"
            )
        }
        return out.toString()
    }

    /** 收拾删除噪声后留下的残渣：撑开的空白、飞出去的标点、中文之间被切碎的缝 */
    private fun tidy(s: String): String {
        var r = s.replace(MULTI_SPACE, " ")
            .replace(SPACE_BEFORE_PUNCT, "$1")
            .replace(DUP_PUNCT, "$1")

        // ⚠️ 中文之间的空格要**看情况**删（这里踩过）：
        //    有些 PDF 抽出来的文本是「字 与 字 之 间 都 有 空 格」，朗读会一顿一顿的，该删；
        //    但「第一章 雨夜来客」这种标题里的空格是**有意的分隔**，删了就变成
        //    「第一章雨夜来客」—— 显示不好看，而且章节标题跟书里目录对不上，定位会失准。
        //    判据：一行里这种"中文字夹空格"出现 3 次以上，才认定它是被切碎的那种。
        if (SPACE_BETWEEN_CJK.findAll(r).count() >= 3) {
            r = r.replace(SPACE_BETWEEN_CJK, "")
        }
        return r.trim()
    }
}
