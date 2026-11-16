package top.readaloud.app

/**
 * 处理"版式换行"与"词与词之间的缝"。
 *
 * 这里解决的是**同一类**问题，它踩过一次而且很隐蔽：
 *
 *   电子书和 PDF 的正文里，换行**不一定**是句子结束 ——
 *     · PDF 是"一行排满就换行"，跟句子在哪儿结束毫无关系
 *     · EPUB 的 XHTML 源码里也可以随便折行，那只是源码排版
 *   而我们的断句器把 `\n` 当句末标点。于是这样一段文字：
 *
 *       本次审查覆盖 2024 年 1 月至 2024 年 12
 *       月期间，公司全部业务系统……
 *
 *   会被劈成两句分别合成 —— 一个年份被拦腰截断，
 *   听感上就是用户反馈的"至20，24年12月"（数字读到一半断掉）。
 *   "ISO 27001" 被劈成 "ISO 27" + "001" 也是同一个原因。
 *
 * 所以：**先判断这个换行是不是"真停顿"，不是就接回去**。
 * 判断必须保守 —— 章节标题（"第一章 审查实例"）本来就不带标点，
 * 一旦被并进正文，标题识别和朗读停顿全都会跟着坏。
 */
object LineBreaks {

    /** 这些字结尾 = 这句话说完了，换行是真的停顿，不要接 */
    private val SENTENCE_END = charArrayOf('。', '！', '？', '；', '…')

    /**
     * 排满一行大约多少字以上，才认为"这一行是被版面切开的"。
     * 短行多半是标题/小标题/列表项 —— 宁可保守一点不要乱接。
     * （两端都是数字/字母时会无条件接回去，见 [shouldJoin]。）
     */
    private const val WRAP_MIN_CHARS = 14

    /** 是不是中日韩文字（含「々〆〇」这几个符号区的字） */
    fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF ||      // 常用汉字
            c.code in 0x3400..0x4DBF ||  // 扩展 A
            c.code in 0xF900..0xFAFF ||  // 兼容汉字
            c.code in 0x3005..0x3007     // 々 〆 〇（就是那个模型不认的字）

    /** 字母或数字（含全角）—— 被劈开的编号/单词靠它判断 */
    private fun isWordChar(c: Char): Boolean = !isCjk(c) && c.isLetterOrDigit()

    /**
     * 这一行没说完吗？（该不该把下一行接上来）
     *
     * @param prev 上一行的内容
     * @param next 下一行的内容
     * @param isHeading 判断"像不像章节标题"的函数（由调用方注入，避免两份规则各说各话）
     */
    fun shouldJoin(prev: String, next: String, isHeading: (String) -> Boolean): Boolean {
        if (prev.isEmpty() || next.isEmpty()) return false
        if (prev.last() in SENTENCE_END) return false
        if (isHeading(prev.trim()) || isHeading(next.trim())) return false
        // 编号/数字被版面劈开 → 无条件接回去（"ISO 27" + "001" → "ISO 27001"）
        if (isWordChar(prev.last()) && isWordChar(next.first())) return true
        return prev.length >= WRAP_MIN_CHARS
    }

    /**
     * 两行拼起来时中间补什么。
     *
     * · 有一边是数字 → 什么都不补。因为这是**同一个数字**被劈开了
     *   （"ISO 27"+"001"、"至 20"+"24 年"），补空格反而又造出一个停顿。
     * · 两边都是拉丁字母 → 补一个空格（排版是在空格处折行的，多半是两个单词）。
     * · 其余 → 什么都不补：中文本来就不用空格，
     *   中英之间该有的空格由 TextNormalizer 统一负责。
     */
    fun glue(prev: String, next: String): String {
        val a = prev.last()
        val b = next.first()
        if (a.isDigit() || b.isDigit()) return ""
        return if (isWordChar(a) && isWordChar(b)) " " else ""
    }

    /**
     * 把文本节点内部的"软换行"折叠掉（EPUB 的 XHTML 源码折行）。
     *
     * XHTML 里的换行只是源码排版，渲染出来是**空格**，不是句子结束。
     * 中文之间直接去掉（中文不用空格），其余情况折成一个空格。
     */
    fun foldSoft(text: String): String {
        if (!text.contains('\n') && !text.contains('\r')) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' || c == '\r') {
                // 把这一串换行/空白看成一个"缝"
                var j = i
                while (j < text.length && (text[j] == '\n' || text[j] == '\r' ||
                            text[j] == ' ' || text[j] == '\t')
                ) j++
                val before = sb.lastOrNull()
                val after = text.getOrNull(j)
                // 中文之间：什么都不补；否则补一个空格（英文单词之间需要它）
                if (before != null && after != null && isCjk(before) && isCjk(after)) {
                    // 不加
                } else if (sb.isNotEmpty() && sb.last() != ' ') {
                    sb.append(' ')
                }
                i = j
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
