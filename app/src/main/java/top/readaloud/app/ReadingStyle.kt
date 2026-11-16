package top.readaloud.app

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * 正文的排版设置：字体、字号、配色。
 *
 * ## 为什么单独一个文件
 *
 * 这三项和"朗读设置"（语速/停顿/音色）是两类东西：那些影响**听**，
 * 这三项影响**看**。混在一起会让设置页越滚越长，也不好分组。
 *
 * ## 一个刻意的取舍：只用系统字体，不打包字体文件
 *
 * 想要"楷体/幼圆/苹方"这类中文字体，就得把字体文件塞进 APK ——
 * 一个中文字体动辄 5~15MB，而且**每个字体都有独立的授权问题**
 * （能不能嵌入 App、能不能商用，得逐个查许可证）。
 * 用系统自带字体则零成本、零授权风险、零体积。
 *
 * 代价是可选的字体少。实测这三款在中文下**确实有明显区别**：
 * · 系统默认 = 无衬线（黑体），屏上最清楚
 * · 衬线= 宋体/思源宋体，衬线字形，适合长文
 * · 等宽 = 等宽字体（中文会回退到默认字体，所以差别主要体现在英文/数字上）
 *
 * ## 配色为什么做成"方案"而不是单纯"字色"
 *
 * 只给"字色"选项其实不好用：夜里想要浅色字，就必须同时把背景改成深色，
 * 否则白底浅字根本看不清。所以每一档都是**字色 + 背景色成对给**，
 * 像别的听书软件那样按"日间 / 米黄 / 夜间"分档。
 */
object ReadingStyle {

    // ── 字体 ────────────────────────────────────────────────
    /**
     * **系统自带**的字体选项。
     *
     * ⚠️ 为什么只有这么几个（Leo 问过为什么选项太少，这里说清楚）：
     *   手机系统里**只装了 2 款中文黑体/宋体**（荣耀 9X 实测：
     *   `/system/fonts` 里是 NotoSansCJK=黑体、NotoSerifCJK=宋体，
     *   HarmonyOS Sans 是系统默认），**没有楷体、仿宋、圆体**。
     *   所以"主流中文字体"里大部分在这台手机上并不存在 ——
     *   列出来也只会"点了没变化"，那比不列更糟。
     *
     *   想用楷体/仿宋/任何字体，走 **[用户字体]**：
     *   把 .ttf/.otf 文件导入 App，选它即可（不打包、不涉及授权）。
     */
    enum class SysFont(val label: String, val hint: String, val family: FontFamily?) {
        DEFAULT("系统默认", "鸿蒙/黑体，最清楚", null),
        SERIF("宋体", "Noto Serif CJK，长文适合", FontFamily.Serif),
        MONO("等宽", "英文数字对齐，中文回退", FontFamily.Monospace);

        companion object {
            fun of(index: Int): SysFont = entries.getOrElse(index) { DEFAULT }
        }
    }

    /** 用户导入的字体：用文件名（去掉扩展名）当显示名 */
    fun userFontLabel(fileName: String): String =
        fileName.substringBeforeLast('.').ifBlank { "我的字体" }

    /** 用户字体目录名（存导入的字体文件） */
    const val USER_FONT_DIR = "fonts"

    /** 取当前选中的用户字体文件名（空=没选用户字体） */
    fun loadUserFont(prefs: SharedPreferences): String =
        prefs.getString(KEY_USER_FONT, "").orEmpty()

    fun saveUserFont(prefs: SharedPreferences, fileName: String) =
        prefs.edit().putString(KEY_USER_FONT, fileName).apply()

    // ── 字号 ────────────────────────────────────────────────
    /**
     * 字号：**连续值**，范围 1~99 sp（Leo 要求滑动条选择）。
     *
     * ⚠️ 为什么用 sp 而不是 dp：sp 会跟着系统"字体大小"设置一起缩放，
     *    老人把系统字体调大时 ours 也跟着变大 —— 这是无障碍要求。
     *
     * 上下限的由来：
     * · 下限 1sp：不是给人用的，是"我想把正文缩到最小看看版式"时的极限，
     *   再小就完全看不见字了（Compose 在极小字号下会拒绝绘制某些字形）。
     * · 上限 99sp：同样不是日常值，但**必须留着** ——
     *   视力障碍的用户需要 40~60sp 这种大到"一行两三字"的设置，
     *   如果按"正常人用不到"把它砍掉，就等于把无障碍选项也砍了。
     *
     * 默认 17sp（比 Material 的 bodyLarge 略大，中文阅读更舒服）。
     */
    const val MIN_SIZE_SP = 1f
    const val MAX_SIZE_SP = 99f
    const val DEFAULT_SIZE_SP = 17f

    fun clampSize(sp: Float): Float = sp.coerceIn(MIN_SIZE_SP, MAX_SIZE_SP)

    // ── 配色 ────────────────────────────────────────────────
    /**
     * 配色方案。
     *
     * @param light 字色（浅色背景下用）
     * @param dark  字色（深色背景下用）
     * @param lightBg 背景色（浅色）
     * @param darkBg  背景色（深色）
     */
    enum class Theme(
        val label: String,
        val light: Color,
        val dark: Color,
        val lightBg: Color,
        val darkBg: Color
    ) {
        FOLLOW("跟随系统", Color(0xFF1D2129), Color(0xFFE5E6EB), Color(0xFFFFFFFF), Color(0xFF15171A)),
        DAY("日间", Color(0xFF1D2129), Color(0xFFE5E6EB), Color(0xFFFFFFFF), Color(0xFF15171A)),
        SEPIA("米黄", Color(0xFF3B3226), Color(0xFFE8DFC8), Color(0xFFF6ECD9), Color(0xFF23201A)),
        NIGHT("夜间", Color(0xFF9AA0A6), Color(0xFFB9BDC3), Color(0xFF15171A), Color(0xFF15171A));

        companion object {
            fun of(index: Int): Theme = entries.getOrElse(index) { FOLLOW }
        }
    }

    // ── 存取 ────────────────────────────────────────────────

    private const val KEY_FONT = "read_font"
    private const val KEY_SIZE = "read_size"
    private const val KEY_THEME = "read_theme"
    private const val KEY_USER_FONT = "read_user_font"

    fun loadFont(prefs: SharedPreferences): SysFont =
        SysFont.of(prefs.getInt(KEY_FONT, 0).coerceIn(0, SysFont.entries.size - 1))

    fun saveFont(prefs: SharedPreferences, f: SysFont) =
        prefs.edit().putInt(KEY_FONT, f.ordinal).apply()

    fun loadSize(prefs: SharedPreferences): Float =
        clampSize(prefs.getFloat(KEY_SIZE, DEFAULT_SIZE_SP))

    fun saveSize(prefs: SharedPreferences, sp: Float) =
        prefs.edit().putFloat(KEY_SIZE, clampSize(sp)).apply()

    fun loadTheme(prefs: SharedPreferences): Theme =
        Theme.of(prefs.getInt(KEY_THEME, Theme.FOLLOW.ordinal).coerceIn(0, Theme.entries.size - 1))

    fun saveTheme(prefs: SharedPreferences, t: Theme) =
        prefs.edit().putInt(KEY_THEME, t.ordinal).apply()

    /**
     * 算出当前实际该用的颜色。
     *
     * @param systemDark 系统是不是深色模式（跟随系统档要用）
     */
    fun colors(theme: Theme, systemDark: Boolean): Pair<Color, Color> {
        val dark = if (theme == Theme.FOLLOW) systemDark else theme == Theme.NIGHT
        return if (dark) theme.dark to theme.darkBg else theme.light to theme.lightBg
    }

}
