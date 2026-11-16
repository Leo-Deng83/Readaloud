package top.readaloud.app

/**
 * 正文归一化（PRD FR-20「正文清洗与归一化」）。
 *
 * ⚠️ 为什么必须做这件事（用户验收反馈的真实问题）：
 *   本地 Kokoro 模型遇到阿拉伯数字时，用的是**非中文音素规则** ——
 *   听感上音调明显别扭：
 *     · "1.1" 这种小标题编号，要么被整个跳过，要么读成"1，1"
 *     · "12.5%" 的百分号被读成英文的 percent
 *     · 一串数字连读时语调忽上忽下，不像中文
 *   把数字换成中文读法之后，模型读的是它最擅长的中文文本，音调自然了，
 *   而且"百分之十二点五"这种读法本来就是人念数字的方式。
 *
 * ⚠️ 关键：**只改送去合成的那份文本，界面显示的句子保持原文**。
 *   （所以"12.5%"在屏幕上仍是"12.5%"，只有喇叭里念的是"百分之十二点五"。）
 *   而且归一化不改变句子的划分，高亮、跳句、断点那些按句子索引工作的功能都不受影响。
 *
 * 规则顺序是刻意的：先处理复合形态（日期/时间/区间/百分比/单位），
 * 最后才处理"光秃秃的数字" —— 否则 "2024-03-11" 会先被数字规则拆得乱七八糟。
 */
object TextNormalizer {

    /**
     * 0-9 → 中文，**必须是 10 个元素**（按序）。
     * ⚠️ 用 charArray 显式写出来而不是写成字符串：字符串少写几个字符看不出来，
     *    而拿它做 `表[c - '0']` 索引时会直接运行时越界崩溃（踩过）。
     *
     * ⚠️ 0 用「零」而不是「〇」（踩过，用户验收反馈）：
     *    模型的中文词表（lexicon-zh.txt，68004 条）里**没有「〇」这个字**，
     *    而"逐位读"（年份 2024 → 二〇二四、编号 27001 → 二七〇〇一）会产出一堆〇。
     *    模型遇到词表外的字，读出来就会在**中间断开** ——
     *    用户听到的正是"至20，24年12月"、"ISO27 001"这种怪停顿。
     *    改回「零」（词表里有，读 líng）听感完全一样：人念年份本来也是"二零二四"。
     *
     * 一句话记住这条规则：**送进合成器的每个字，都必须是模型词表里有的字。**
     */
    private val CN_DIGITS = charArrayOf('零', '一', '二', '三', '四', '五', '六', '七', '八', '九')

    private val BIG_UNITS = arrayOf("", "万", "亿", "万亿", "亿亿")

    private const val OPERAND = "[0-9A-Za-z〇零一二三四五六七八九十百千万亿点]"

    private val DATE = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val TIME = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val YEAR_RANGE = Regex("""(\d{4})\s*[-–—]\s*(\d{4})(\s*[年月日天页%]*)""")
    private val RANGE = Regex("""(\d+(?:\.\d+)?)\s*[–—~～至]\s*(\d+(?:\.\d+)?)(\s*[年月日天页%]*)""")
    private val RANGE_SPACED =
        Regex("""(?<![A-Za-z0-9])(\d{1,3})\s+-\s+(\d{1,3})(?![0-9])(\s*[年月日天页%]*)""")

    /**
     * 区间词（至/到）两侧加逗号 —— **这是 Leo 试听后定的听感**。
     *
     * ⚠️ 过程值得记下来（不然以后会"好心"改回去）：
     *   修完"数字被读断"之后，我把同一句话做了四个版本让他试听，
     *   他把**数字两侧留空格**那版评为"最自然" ——
     *   因为那版在"二零二四年 ｜ 一月"这种地方有约 0.8 秒的气口，
     *   听着像在把日期念清楚；他不要的是"一味求连贯"。
     *   但靠空格换气会把词切开（停顿正好落在年/月之间，很机械）。
     *   所以最终方案：**数字一个字都不拆，只在语义边界（至/到）给气口** ——
     *   实测停顿 840ms，正好落在"至"两侧，其余地方只有几十毫秒。
     *
     * 条件要严：左边是数字/年月日时分点、右边是数字才算区间。
     *   这样"截至目前""以至于""来到"里的至/到都不会被误伤。
     * ⚠️ 必须排在 [RANGE] 之前 —— 否则 "1月至2024" 会先被 RANGE 吃掉（变成"到"），
     *    这条规则就再也看不到"至"了。
     */
    private val RANGE_WORD_PAUSE = Regex(
        """(?<=[\d月日年点时分秒%])[ \t]*(至|到)[ \t]*(?=[\d零一二三四五六七八九十])"""
    )
    private val PERCENT = Regex("""(\d+(?:\.\d+)?)\s*%""")
    private val KMH = Regex("""(\d+(?:\.\d+)?)\s*km/h""", RegexOption.IGNORE_CASE)
    private val MPS = Regex("""(\d+(?:\.\d+)?)\s*m/s""", RegexOption.IGNORE_CASE)
    private val CELSIUS = Regex("""(\d+(?:\.\d+)?)\s*°C""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""(\d{4})\s*年""")
    private val DECIMAL = Regex("""(\d+)\.(\d+)""")
    private val NUMBER = Regex("""\d+""")
    private val PLUS = Regex("""(?<=$OPERAND)\s*\+\s*(?=$OPERAND)""")
    private val EQUAL = Regex("""(?<=$OPERAND)\s*=\s*(?=$OPERAND)""")
    private val ELLIPSIS = Regex("""\.{3,}""")
    private val DUP_END_PUNCT = Regex("""([。！？；])\1+""")

    /**
     * 全角数字/字母 → 半角。
     * ⚠️ 必须放在最前面：Java 的 `\d` **不匹配全角数字**（１２３），
     *    不先转的话，下面那一整套数字规则遇到全角数字会整条失效。
     */
    private val FULLWIDTH_ALNUM = Regex("""[\uFF10-\uFF19\uFF21-\uFF3A\uFF41-\uFF5A]""")

    /** 罗马数字（单个字符那种）→ 中文 */
    private val ROMAN_DIGITS = mapOf(
        'Ⅰ' to "一", 'Ⅱ' to "二", 'Ⅲ' to "三", 'Ⅳ' to "四", 'Ⅴ' to "五",
        'Ⅵ' to "六", 'Ⅶ' to "七", 'Ⅷ' to "八", 'Ⅸ' to "九", 'Ⅹ' to "十",
        'Ⅺ' to "十一", 'Ⅻ' to "十二"
    )

    /** 单位/特殊符号 → 中文读法（这些符号模型多半不认识，会跳过或读乱） */
    private val UNIT_SYMBOLS = mapOf(
        '℃' to "摄氏度", '℉' to "华氏度", '％' to "百分之", '‰' to "千分之",
        '㎡' to "平方米", '㎥' to "立方米", '㎞' to "公里", '㎝' to "厘米",
        '㎜' to "毫米", '㎏' to "千克", '㎎' to "毫克", '°' to "度"
    )

    /**
     * 中英文边界（PRD FR-20 第 5 条"中英混读"）。
     *
     * ⚠️ 我们没法用 PRD 说的"语言标记"：Kokoro 是端到端模型，
     *    不支持在文本里插语言切换标记（插了会被当普通字符念出来）。
     *    实际有效的手段是**在中文和英文之间补一个空格** ——
     *    模型靠它切分语言边界，就不会把「NASA的」当成一个整体的中文词去发音。
     */
    private val CJK_THEN_LATIN = Regex("""(?<=[\u4e00-\u9fff])(?=[A-Za-z])""")
    private val LATIN_THEN_CJK = Regex("""(?<=[A-Za-z])(?=[\u4e00-\u9fff])""")

    /**
     * 半角标点在**中文之后**时转成全角（中英混排里，中文句子用半角标点
     * 会让模型的停顿处理不一致）。
     * ⚠️ 只看前一个字符是不是中文，所以纯英文句子里的 , . ? 不受影响。
     */
    private val HALF_PUNCT_AFTER_CJK = Regex("""(?<=[\u4e00-\u9fff])([,;:!?])""")

    /**
     * 中日韩文字的字符区间（含「々〆〇」这几个符号区的字）。
     * 用在"中文字之间的空格要收掉"这条规则上。
     */
    private const val CJK_CLASS =
        "\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff\\u3005-\\u3007"

    /**
     * 中文字之间的空格。
     * ⚠️ 实测（本机跑同款模型、测波形）：文本里**一个空格会让模型停约 0.78 秒**，
     *    那是"句子之间"级别的长停顿，插在词中间极其刺耳。
     *    这些空格主要来自 PDF 抽取（数字两侧的缝）：
     *      "2024 年 1 月" → 转成中文后就成了 "二零二四年 一 月"
     *    所以最后要收掉。
     */
    private val CJK_SPACES = Regex(
        """(?<=[$CJK_CLASS])[ \t\u00A0\u3000]+(?=[$CJK_CLASS])"""
    )

    /** 中文标点前面的空格（"了 ，"这种） */
    private val SPACE_BEFORE_CJK_PUNCT = Regex(
        """[ \t\u00A0\u3000]+(?=[，。、；：！？…》）】」』])"""
    )

    /** 左括号后面的空格 */
    private val SPACE_AFTER_OPEN_PUNCT = Regex(
        """(?<=[（《「『【])[ \t\u00A0\u3000]+"""
    )

    /**
     * 中文标点**后面**的空格。
     * ⚠️ 加这条是因为"区间两侧加逗号"会造出「， 年」这种残留 ——
     *    空格同样会让模型停 0.78 秒（见 CJK_SPACES 的说明），不能留。
     */
    private val SPACE_AFTER_CJK_PUNCT = Regex("([，。、；：！？…》）】」』])[ \\t\\u00A0\\u3000]+")

    /** 把一段文本规范化成"念起来自然"的中文写法 */
    fun normalize(text: String): String {
        if (text.isBlank()) return text
        var s = text

        // ⓪ 先把"模型的盲区"处理掉：全角数字、罗马数字、不认识的特殊符号。
        //    这些不处理的话，要么整条数字规则失效（全角），要么被跳过/读乱（符号）。
        //
        // ⚠️ 〇 → 零：模型词表里没有「〇」这个字（详见 CN_DIGITS 的说明）。
        //    而正经的中文出版物里，"二〇二四年"恰恰是最常见的写法 ——
        //    不清掉的话，书里自带的〇同样会在朗读时断开。
        s = s.replace('〇', '零')
        s = FULLWIDTH_ALNUM.replace(s) { m ->
            // 全角 Ａ-Ｚ / ａ-ｚ / ０-９ 与半角相差固定偏移 0xFEE0
            (m.value[0] - 0xFEE0).toString()
        }
        if (s.any { it in ROMAN_DIGITS }) {
            val sb = StringBuilder(s.length)
            s.forEach { c -> sb.append(ROMAN_DIGITS[c] ?: c.toString()) }
            s = sb.toString()
        }
        if (s.any { it in UNIT_SYMBOLS }) {
            val sb = StringBuilder(s.length)
            s.forEach { c -> sb.append(UNIT_SYMBOLS[c] ?: c.toString()) }
            s = sb.toString()
        }

        // ① 日期：2024-03-11 → 二零二四年三月十一日
        s = DATE.replace(s) { m ->
            yearToCn(m.groupValues[1]) + "年" +
                numToCn(m.groupValues[2].toInt().toString()) + "月" +
                numToCn(m.groupValues[3].toInt().toString()) + "日"
        }

        // ② 时间：14:30 → 十四点三十分
        s = TIME.replace(s) { m ->
            numToCn(m.groupValues[1]) + "点" +
                if (m.groupValues[2] == "00") "整" else numToCn(m.groupValues[2]) + "分"
        }

        // ③ 区间两侧给气口（Leo 试听定的节奏，详见 RANGE_WORD_PAUSE 的说明）
        //    ⚠️ 必须排在下面的 RANGE 之前，否则 至 会被先换成"到"就看不见了
        s = RANGE_WORD_PAUSE.replace(s) { "，${it.groupValues[1]}，" }

        // ③.1 短横线区间也走同样的节奏：1990-2000 年 → ，一九九零到两千年
        //     ⚠️ 末尾的单位（年/月/天/页…）要一起带上，且**带上就不再加逗号** ——
        //     否则会念成"到两千 ，年"，单位被逗号甩到后面（预演时抓到的）。
        s = YEAR_RANGE.replace(s) { m -> rangeSpoken(yearToCn(m.groupValues[1]), yearToCn(m.groupValues[2]), m.groupValues[3]) }
        s = RANGE.replace(s) { m -> rangeSpoken(numToCn(m.groupValues[1]), numToCn(m.groupValues[2]), m.groupValues[3]) }
        s = RANGE_SPACED.replace(s) { m -> rangeSpoken(numToCn(m.groupValues[1]), numToCn(m.groupValues[2]), m.groupValues[3]) }

        // ⑤ 百分比：12.5% → 百分之十二点五（百分号被读成英文就是指这里）
        s = PERCENT.replace(s) { m -> "百分之" + numToCn(m.groupValues[1]) }

        // ⑥ 单位（PRD 点名的那几个）
        s = KMH.replace(s) { m -> "每小时" + numToCn(m.groupValues[1]) + "公里" }
        s = MPS.replace(s) { m -> "每秒" + numToCn(m.groupValues[1]) + "米" }
        s = CELSIUS.replace(s) { m -> numToCn(m.groupValues[1]) + "摄氏度" }

        // ⑦ 年份：2024 年 → 二零二四年（注意是"二零二四"不是"两千零二十四"）
        s = YEAR.replace(s) { m -> yearToCn(m.groupValues[1]) + "年" }

        // ⑧ 小数 / 编号：1.1 → 一点一（小标题编号就是这一类）
        s = DECIMAL.replace(s) { m ->
            numToCn(m.groupValues[1] + "." + m.groupValues[2])
        }

        // ⑨ 剩下的裸数字
        s = NUMBER.replace(s) { m -> numToCn(m.value) }

        // ⑩ 数学 / 关系符号
        s = s.replace("≥", "大于等于").replace("≤", "小于等于").replace("≈", "约等于")
            .replace("≠", "不等于").replace("×", "乘").replace("÷", "除").replace("±", "正负")
        s = PLUS.replace(s, "加")
        s = EQUAL.replace(s, "等于")

        // ⑪ 标点小修正（PRD FR-20 第 3 条的克制版）
        //    ⚠️ 这里只做"怎么改都更自然"的两条，**不去动模型对普通标点的处理**：
        //       "逗号短停、句号长停"这类本来就是模型做得不错的部分，
        //       而我们没有听感验证手段（得靠用户耳朵），乱调风险大于收益。
        s = ELLIPSIS.replace(s, "……")          // 英文省略号 ... → 中文……
        s = DUP_END_PUNCT.replace(s, "$1")      // "！！" / "。。。" → 只留一个

        // ⑫ 中英混读：在中文与英文之间补空格（帮模型切准语言边界）
        s = CJK_THEN_LATIN.replace(s, " ")
        s = LATIN_THEN_CJK.replace(s, " ")

        // ⑬ 中文之后的半角标点转全角，让停顿处理一致
        s = HALF_PUNCT_AFTER_CJK.replace(s) { m ->
            when (m.value[0]) {
                ',' -> "，"; ';' -> "；"; ':' -> "："; '!' -> "！"; else -> "？"
            }
        }

        // ⑭ 读音纠正词典（FR-23）**不在这里调用** ——
        //    它需要一个"开/关"的用户设置，而设置存在 SharedPreferences 里，
        //    归一化拿不到。所以由 PlaybackService 在归一化之后调用：
        //        PronunciationDict.apply(spoken)   ← 开关也在这里判断
        //    （放这里的另一个坏处：归一化是纯函数，混进读设置会让它没法单独测试）

        // ⑮ 最后收尾：把**中文字之间的空格**收掉。
        //    前面的规则会把阿拉伯数字变成中文，于是原本"数字两侧的缝"就变成了
        //    "中文字之间的空格"，而空格会让模型停 0.78 秒（见 CJK_SPACES 的说明）。
        //    ⚠️ 中英之间的空格**保留** —— 那个是帮模型切准语言边界的标记
        //       （对照实测：加不加它，波形时长与停顿位置完全一致，无害）。
        //    ⚠️ 这一步只作用在"送去合成的文本"上，屏幕上的原文和高亮都不受影响。
        s = CJK_SPACES.replace(s, "")
        s = SPACE_BEFORE_CJK_PUNCT.replace(s, "")
        s = SPACE_AFTER_CJK_PUNCT.replace(s, "$1")
        s = SPACE_AFTER_OPEN_PUNCT.replace(s, "")

        return s
    }

    // ── 数字 → 中文 ──────────────────────────────────────────────

    /**
     * 区间的朗读写法：`"，" + A + "到" + B + 尾巴`。
     *
     * ⚠️ 尾巴的处理是踩出来的：原文 "1990-2000 年" 里，年份区间和单位是分开的，
     *    如果无脑在区间后面加逗号，就会念成"一九九零到两千 ，年" ——
     *    单位被逗号甩到后面去。所以：**后面跟着单位时把单位带上，并且不再加逗号**。
     */
    private fun rangeSpoken(a: String, b: String, unit: String): String {
        val tail = if (unit.isBlank()) "，" else unit
        return "，" + a + "到" + b + tail
    }

    /** 四位以内的一段（1..9999）；"一十"要读成"十"才自然 */    private fun sectionToCn(sec: Int): String {
        val units = arrayOf("千", "百", "十", "")
        val ds = intArrayOf(sec / 1000 % 10, sec / 100 % 10, sec / 10 % 10, sec % 10)
        val sb = StringBuilder()
        var pendingZero = false
        for (i in ds.indices) {
            val d = ds[i]
            if (d == 0) {
                if (sb.isNotEmpty()) pendingZero = true
            } else {
                if (pendingZero) {
                    sb.append('零')
                    pendingZero = false
                }
                sb.append(CN_DIGITS[d]).append(units[i])
            }
        }
        val out = sb.toString()
        return if (out.startsWith("一十")) out.substring(1) else out
    }

    private fun intToCn(n: Long): String {
        if (n == 0L) return "零"
        val parts = ArrayList<Int>()
        var v = n
        while (v > 0) {
            parts.add((v % 10000).toInt())
            v /= 10000
        }
        val sb = StringBuilder()
        for (i in parts.indices.reversed()) {
            val sec = parts[i]
            if (sec == 0) continue
            // 一万零一：中间的段落不足四位数要补个"零"，否则听起来像"一万零一"漏字
            if (sb.isNotEmpty() && sec < 1000) sb.append('零')
            sb.append(sectionToCn(sec)).append(BIG_UNITS[i])
        }
        return sb.toString()
    }

    /** 逐位读（0 读作"零"）：用于年份和超长编号 */
    private fun digitsToCn(s: String): String {
        val sb = StringBuilder()
        for (c in s) if (c.isDigit()) sb.append(CN_DIGITS[c - '0'])
        return sb.toString()
    }

    /** 年份：整千年读"两千"，其余逐位读（2024 → 二零二四） */
    private fun yearToCn(y: String): String =
        if (y.length == 4 && y.endsWith("000")) {
            val k = y[0] - '0'
            (if (k == 2) "两" else CN_DIGITS[k].toString()) + "千"
        } else {
            digitsToCn(y)
        }

    /**
     * 数字串 → 中文。
     *
     * ⚠️ 长度决定读法，这是有意为之：
     *   · 4 位以内整体读：236 → 二百三十六（数量就该这么念）
     *   · 超过 4 位且末尾一串 0：整体读：1000000 → 一百万（金额类）
     *   · 其余逐位读：27001 → 二七零零一
     *     （这是标准/型号编号，"二万七千零一"反而是错的）
     */
    private fun numToCn(s: String): String {
        if (s.contains('.')) {
            val intPart = s.substringBefore('.')
            val fracPart = s.substringAfter('.')
            val head = if (intPart.length <= 4) {
                intPart.toLongOrNull()?.let { intToCn(it) } ?: digitsToCn(intPart)
            } else {
                digitsToCn(intPart)
            }
            return head + "点" + digitsToCn(fracPart)
        }
        if (s.length <= 4) {
            s.toLongOrNull()?.let { return intToCn(it) }
            return digitsToCn(s)
        }
        if (s.endsWith("000")) {
            s.toLongOrNull()?.let { return intToCn(it) }
        }
        return digitsToCn(s)
    }
}
