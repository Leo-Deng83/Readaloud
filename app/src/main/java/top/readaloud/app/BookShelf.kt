package top.readaloud.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

/**
 * 书架里的一本书。
 *
 * ⚠️ 注意这里**不存阅读进度**。
 *    进度（读到第几句）一直由 PlaybackService 存在它自己的那份设置里
 *    （见 PREF_PROGRESS），那是"播放"这件事的状态，服务端才是唯一权威。
 *    书架只负责"有哪些书"，显示进度时去问服务要 —— 两边就不会打架。
 *
 * @param uri            文件的地址（SAF 授权已经"持久化"过，重启 App 后仍然能读）
 * @param title          书名（取文件名去掉扩展名）
 * @param totalSentences 全书多少句（载入时算出来的）
 * @param totalGroups    分成了多少个朗读单元（一段 = 一次合成）
 * @param lastOpenedAt   最后一次打开的时间，书架按它倒序排（最近听的排前面）
 */
class Book(
    val uri: String,
    val title: String,
    val totalSentences: Int,
    val totalGroups: Int,
    val lastOpenedAt: Long
)

/**
 * 书架的数据层。
 *
 * ⚠️ 为什么用键值存储 + JSON，而不是 Room 数据库（有意为之）：
 *   书架这点数据量（几十本书）用数据库属于杀鸡用牛刀。
 *   而 Room 会带来一个**代码生成器**（注解处理器），
 *   每次编译都要多跑一道工序 —— 在这台 6.9GB 内存的开发机上不划算。
 *   等以后真要做「书签 / 笔记 / 多表关联」时再升级，那时数据迁移也不难
 *   （每本书的 uri 是稳定主键）。
 *
 * 存储形态：整份书单序列化成一个 JSON 数组，存在一个键里。
 * 简单、原子、一次读写搞定，坏掉了也只是这一份书单，不会牵连别的设置。
 */
object BookShelf {

    private const val PREF = "readaloud_shelf"
    private const val KEY_BOOKS = "books"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 读出全部书，按"最后打开时间"从新到旧排 */
    fun all(prefs: SharedPreferences): List<Book> {
        val raw = prefs.getString(KEY_BOOKS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Book(
                    uri = o.getString("uri"),
                    title = o.optString("title", "未命名"),
                    totalSentences = o.optInt("totalSentences", 0),
                    totalGroups = o.optInt("totalGroups", 0),
                    lastOpenedAt = o.optLong("lastOpenedAt", 0L)
                )
            }.sortedByDescending { it.lastOpenedAt }
        }.getOrDefault(emptyList())
        // ⚠️ 用 getOrDefault 兜底：万一存储里的 JSON 坏了（手改、异常退出写了一半），
        //    书架显示成空的，而不是让整个 App 崩在启动那一刻。
    }

    private fun save(prefs: SharedPreferences, books: List<Book>) {
        val arr = JSONArray()
        books.forEach { b ->
            arr.put(
                JSONObject().apply {
                    put("uri", b.uri)
                    put("title", b.title)
                    put("totalSentences", b.totalSentences)
                    put("totalGroups", b.totalGroups)
                    put("lastOpenedAt", b.lastOpenedAt)
                }
            )
        }
        prefs.edit().putString(KEY_BOOKS, arr.toString()).apply()
    }

    /**
     * 加入书架 / 更新已有记录。
     *
     * 按 uri 去重 —— 同一本书重复导入不会在书架上出现两张卡片。
     */
    fun upsert(prefs: SharedPreferences, book: Book) {
        val list = all(prefs).toMutableList()
        val i = list.indexOfFirst { it.uri == book.uri }
        if (i >= 0) list[i] = book else list.add(book)
        save(prefs, list)
    }

    /**
     * 标记"这本书刚被打开过"（书架排序用）。
     * 只动时间戳，不动书名和句数 —— 这样点开一本书不会顺手把它别的字段覆盖掉。
     */
    fun touch(prefs: SharedPreferences, uri: String) {
        val list = all(prefs).toMutableList()
        val i = list.indexOfFirst { it.uri == uri }
        if (i < 0) return
        val b = list[i]
        list[i] = Book(b.uri, b.title, b.totalSentences, b.totalGroups, System.currentTimeMillis())
        save(prefs, list)
    }

    /** 从书架移除一本书（只删记录，不动源文件，也不动进度） */
    fun remove(prefs: SharedPreferences, uri: String) {
        save(prefs, all(prefs).filterNot { it.uri == uri })
    }

    /**
     * 取文件在系统里的完整显示名（带扩展名），例如「三体.epub」。
     *
     * ⚠️ 为什么不能直接用 uri.lastPathSegment（踩过）：
     *   通过系统文件选择器拿到的地址长这样
     *     content://com.android.externalstorage.documents/document/primary%3ABooks%2F三体.txt
     *   它的 lastPathSegment 是 "primary:Books/三体.txt" —— 直接当书名会带一串路径。
     *   正确做法是问系统要 DISPLAY_NAME，它才是用户在文件管理器里看到的那个名字。
     */
    fun displayName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null }
        }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
        return name?.takeIf { it.isNotBlank() } ?: "未命名"
    }

    /** 文件扩展名（小写，不带点）。EPUB / PDF / TXT 的分派靠它 */
    fun fileExtension(context: Context, uri: Uri): String =
        displayName(context, uri).substringAfterLast('.', "").lowercase()

    /** 书名：文件显示名去掉扩展名（三体.txt → 三体；.txt / .epub / .pdf 都处理） */
    fun displayTitle(context: Context, uri: Uri): String {
        val name = displayName(context, uri)
        return name.substringBeforeLast('.').ifBlank { name }
    }

    /**
     * 源文件还在不在。
     *
     * 为什么要查：书本记录里存的是一个"地址"，而文件可能已经
     * 被用户在文件管理器里删了、或者存储卡拔了。
     * 这时候书架要能标出来（PRD FR-04 的"源文件缺失标识"），
     * 而不是等用户点进去才莫名其妙地失败。
     *
     * ⚠️ 会真的去开一下文件流，是 IO 操作，必须在后台线程调。
     */
    fun exists(context: Context, uri: String): Boolean = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false
    }.getOrDefault(false)
}
