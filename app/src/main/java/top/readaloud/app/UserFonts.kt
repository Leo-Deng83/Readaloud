package top.readaloud.app

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **用户字体**：把自己电脑里的 .ttf / .otf 字体文件导进来用。
 *
 * ## 为什么需要它（Leo 提出"字体选项太少"）
 *
 * 手机系统里**只装了 2 款中文字体**（荣耀 9X 实测 `/system/fonts`：
 * NotoSansCJK=黑体、NotoSerifCJK=宋体，另有 HarmonyOS Sans 作系统默认），
 * **没有楷体、仿宋、圆体**。所以"主流中文字体"里大部分在这台手机上并不存在，
 * 硬列进菜单只会"点了没变化"，比不列更糟。
 *
 * 与其我们去打包字体文件（一个中文字体 10~20MB，而且**每个字体都有独立的授权问题**——
 * 方正字体就不能随意嵌入商用），不如**让用户自己导入**：
 * 想用楷体就把楷体文件丢进来，零体积、零授权风险、想要什么就有什么。
 *
 * ## 加载方式
 *
 * 用 Compose 的 [AndroidFont] 包一层 `Typeface`（Android 平台的字体）。
 * 已确认本项目的 Compose 版本（ui-text 1.12.1）里存在这个类。
 */
object UserFonts {

    private const val TAG = "UserFonts"

    /** 支持的字体文件后缀。ttf/otf 是 TrueType/OpenType，ttc 是字体集合 */
    private val EXTS = listOf("ttf", "otf", "ttc")

    /** 系统字体族（不在这里，去 [ReadingStyle.SysFont]） */
    const val SYSTEM_MARK = "__system__"

    private fun dir(context: Context): File =
        File(context.filesDir, ReadingStyle.USER_FONT_DIR).apply { mkdirs() }

    /** 已导入的字体文件列表（按文件名排，稳定的顺序） */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.extension.lowercase() in EXTS }
            ?.sortedBy { it.name } ?: emptyList()

    /**
     * 导入一个字体文件。
     *
     * @return 存进去的文件名；失败时 throw（调用方转成提示）
     */
    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in EXTS) {
            throw IllegalArgumentException("只支持 ttf / otf / ttc 字体文件，这个是 .$ext")
        }
        val target = File(dir(context), name)
        // 先写临时文件再改名：直接写正式文件，中途失败会留下半截字体
        //（半截字体加载时会被系统静默丢弃，表现为"导入成功但字体没变"，极难查）
        val tmp = File(target.parentFile, "$name.part")
        context.contentResolver.openInputStream(uri)?.use { i ->
            tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) }
        } ?: throw IllegalStateException("打不开这个文件（可能没有访问权限）")

        val size = tmp.length()
        if (size < 1024) {
            tmp.delete()
            throw IllegalStateException("这个文件只有 $size 字节，不像字体文件")
        }
        // 关键校验：Typeface 能不能真的把它解析成字体。
        // 有些 .ttf 是改名的别的格式，直接存进去会在用的时候静默失效。
        val ok = runCatching {
            Typeface.createFromFile(tmp)
        }.getOrNull()
        if (ok == null) {
            tmp.delete()
            throw IllegalStateException("这个文件不是有效的字体（解析失败）")
        }

        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        Log.i(TAG, "已导入字体：$name（$size 字节）")
        name
    }

    /** 删掉一个导入的字体（选中它的时候要先切回系统字体，否则正文会退回默认） */
    fun delete(context: Context, fileName: String): Boolean =
        File(dir(context), fileName).delete()

    /**
     * 取字体的 FontFamily。
     *
     * @param fileName 空字符串或 [SYSTEM_MARK] → 用系统默认（返回 null）
     * @return null 表示"用系统默认"，由调用方决定fallback
     */
    fun familyOf(context: Context, fileName: String): FontFamily? {
        if (fileName.isBlank() || fileName == SYSTEM_MARK) return null
        val f = File(dir(context), fileName)
        if (!f.isFile) return null
        return runCatching {
            // ⚠️ 这个 FontFamily(Typeface) 是顶层函数（不是AndroidFont 的构造器）
            FontFamily(Typeface.createFromFile(f))
        }.getOrNull()
    }

    /** 从 uri 查显示名（不能用 lastPathSegment，见 BookShelf 里的坑） */
    private fun displayName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null }
        }.getOrNull()
        // 兜底：自己起一个（导入的字体可能来自"最近文件"，没有 DISPLAY_NAME）
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "myfont.ttf"
        // 只取文件名部分，防止路径穿越（万一 DISPLAY_NAME 里带路径分隔符）
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
        // 中文名 + 空格没问题，但要把 Android 文件系统不允许的字符换掉
        return safe.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "myfont.ttf" }
    }
}