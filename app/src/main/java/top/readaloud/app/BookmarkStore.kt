package top.readaloud.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条书签。
 *
 * @param sentenceIndex 打在第几句（**用句号定位**，不用字符偏移——文件重排就废了）
 * @param createdAt     什么时候打的（毫秒时间戳）
 * @param note          当时的句子原文（**冗余存一份**，理由见下面）
 */
class Bookmark(
    val sentenceIndex: Int,
    val createdAt: Long,
    val note: String
)

/**
 * 书签的数据层。
 *
 * ## 为什么照抄书架那套（SharedPreferences + JSON），不上 Room
 *
 * 理由和 [BookShelf] 完全一样：这点数据量用数据库是杀鸡用牛刀，
 * 而 Room 的代码生成器会拖慢每次构建。存储形态是
 * "**每本书一个键，值是 JSON 数组**"——按 uri 分开存，
 * 这样删一本书的书签不影响别的书，也不会因为某一本书的数据坏了而整本 App 崩掉。
 *
 * ## 为什么要冗余存一份句子原文（note）
 *
 * 书签列表要在**不重新解析文件**的前提下显示"这一句是什么"。
 * 如果只存句号，那么打开书签页就得先把整本书重新解析一遍才能显示内容 ——
 * 几十本书的用户点一下书签要等好几秒，体验很差。
 * 存一份原文（截断到 60 字）换来"秒开"，这个冗余非常划算。
 * 句子原文变了也不影响跳转 —— **跳转永远以 sentenceIndex 为准**。
 */
object BookmarkStore {

    private const val PREF = "readaloud_bookmarks"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun key(uri: String) = "bm_$uri"

    /** 读出某本书的全部书签，按打书签的先后排序 */
    fun of(prefs: SharedPreferences, uri: String): List<Bookmark> {
        val raw = prefs.getString(key(uri), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Bookmark(
                    sentenceIndex = o.optInt("i", 0),
                    createdAt = o.optLong("t", 0L),
                    note = o.optString("n", "")
                )
            }.sortedBy { it.sentenceIndex }
            // ⚠️ 兜底：存储坏了就当没书签，不要让 App 崩在启动那一刻
        }.getOrDefault(emptyList())
    }

    private fun save(prefs: SharedPreferences, uri: String, list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(
                JSONObject().apply {
                    put("i", b.sentenceIndex)
                    put("t", b.createdAt)
                    put("n", b.note)
                }
            )
        }
        prefs.edit().putString(key(uri), arr.toString()).apply()
    }

    /**
     * 加一个书签。
     *
     * ⚠️ 同一个句号只留一条：连点两次不会产生两条重复书签
     *    （真的会有人手抖连点，重复条目在列表里很烦）。
     */
    fun add(prefs: SharedPreferences, uri: String, sentenceIndex: Int, note: String) {
        val list = of(prefs, uri).toMutableList()
        if (list.any { it.sentenceIndex == sentenceIndex }) return
        list.add(
            Bookmark(
                sentenceIndex = sentenceIndex,
                createdAt = System.currentTimeMillis(),
                note = note.take(60)
            )
        )
        save(prefs, uri, list)
    }

    /** 删一条（按句号定位） */
    fun remove(prefs: SharedPreferences, uri: String, sentenceIndex: Int) {
        save(prefs, uri, of(prefs, uri).filterNot { it.sentenceIndex == sentenceIndex })
    }

    /**
     * 某句话上有没有书签（界面用来把按钮显示成"已添加"状态）。
     *
     * 列表不长，直接扫一遍就行，不用建索引。
     */
    fun has(prefs: SharedPreferences, uri: String, sentenceIndex: Int): Boolean =
        of(prefs, uri).any { it.sentenceIndex == sentenceIndex }

    /**
     * 判断某本书有没有书签（决定要不要在书籍标题旁边显示个小标记）。
     */
    fun count(prefs: SharedPreferences, uri: String): Int = of(prefs, uri).size
}