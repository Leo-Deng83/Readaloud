package top.readaloud.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 播放列表里某一项对应的段落信息。
 *
 * 它被编码进 MediaItem 的 mediaId 字符串里，主界面读出来就知道"现在念到第几句"。
 *
 * @param boundsMs 段内**每句话的结束时刻**（毫秒，相对本段开头）。
 *                 这是合成完音频后从波形里实测出来的（见 detectSentenceBounds），
 *                 比按字数猜准得多。空数组表示没测出来，界面会退回按字数估算。
 */
class SegmentInfo(
    val groupIndex: Int,
    val startSentence: Int,
    val sentenceCount: Int,
    val durationMs: Int,
    val boundsMs: IntArray
)

/**
 * 让通知栏 / 耳机线控的「上一句 / 下一句」按钮**始终存在**的播放器包装。
 *
 * ⚠️ 为什么需要它（真机上量出来的问题）：
 *   ExoPlayer 判断"能不能切到下一句"的依据，是**队列里是否真的还有下一段**。
 *   而我们是边合成边播的：麒麟 710F 合成一段要二十秒、播放一段只要十几秒，
 *   所以播放位置**长期停在队列的最后一项**上。
 *   按 ExoPlayer 的规矩，这时候"下一句"这个命令会直接变成"不可用"，
 *   通知栏那一排按钮里，下一句就**时有时无**——
 *   用户看到的就是"播放工具条只剩播放和上一句两个按钮"。
 *
 *   这里把这两个命令强制标记成"永远可用"：
 *   MediaSession 于是会稳定地把 ⏮ ⏭ 画出来，不再闪变。
 *   如果按下时确实还没有下一段，ExoPlayer 会安静地什么都不做
 *   （不报错、不崩溃）——对一个"下一段还在合成中"的瞬间来说，这就是最合理的反应。
 *
 * 这是 Media3 官方为这类场景准备的写法（ForwardingSimpleBasePlayer）。
 */
private class SteadyNavPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): SimpleBasePlayer.State {
        val base = super.getState()
        return base.buildUpon()
            .setAvailableCommands(
                base.availableCommands.buildUpon()
                    .addAll(
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                    )
                    .build()
            )
            .build()
    }
}

/**
 * 后台朗读服务（S3 的核心）。
 *
 * 用 MediaSessionService（Media3）的好处：
 *   1. 它是**前台服务**，手机锁屏、切到别的 App，声音都不会被系统掐掉
 *   2. 它会自动在通知栏显示播放控制条（上一句/暂停/下一句），这是系统级待遇
 *   3. 耳机线控、蓝牙耳机、车机、手表这些也都能直接控制它
 *
 * 工作流程：
 *   读文件 → 切句 → 分组 → 用本地引擎合成 → 存成 WAV → 加进播放列表 → ExoPlayer 播
 *   播放的同时后台**继续合成后面的段落**（预取），所以听起来是连贯的。
 */
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_START = "top.readaloud.app.action.START"
        const val ACTION_STOP = "top.readaloud.app.action.STOP"
        const val ACTION_RESUME = "top.readaloud.app.action.RESUME"

        /** 上一句 / 下一句（界面上那两个按钮，也复用给通知栏的切段键） */
        const val ACTION_PREV = "top.readaloud.app.action.PREV"
        const val ACTION_NEXT = "top.readaloud.app.action.NEXT"

        /** 跳到指定的某一句（点正文里任意一句、点章节都用它） */
        const val ACTION_SEEK_SENTENCE = "top.readaloud.app.action.SEEK_SENTENCE"

        /**
         * 换书（界面换了一本 → 后台必须把上一本收干净）。
         *
         * ⚠️ 为什么必须有这个指令（v0.3.3 踩到的大 bug）：
         *   以前界面「载入另一本书」只换了它自己的数据，**后台毫不知情** ——
         *   currentUri、句子表、段落表、合成队列全都还是上一本。
         *   结果：界面显示新书、声音还在放旧书；点暂停再「继续」又是旧书
         *   （因为「继续」直接 play() 那个旧队列），更糟的是它把**旧书的进度**
         *   写进了**新书**的记录里，两本书的断点互相污染。
         *
         * 传参 uri = 即将切换到的那本书；跟当前这轮不是同一本就作废当前这轮。
         */
        const val ACTION_SWITCH_BOOK = "top.readaloud.app.action.SWITCH_BOOK"

        /** 设置 / 取消睡眠定时（界面那一排「睡眠」按钮走它） */
        const val ACTION_SLEEP = "top.readaloud.app.action.SLEEP"

        const val EXTRA_URI = "uri"
        const val EXTRA_FROM = "from"
        const val EXTRA_SENTENCE = "sentence"
        const val EXTRA_SLEEP_MODE = "sleep_mode"
        const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
        const val EXTRA_SLEEP_TARGET = "sleep_target"

        private const val TAG = "ReadaloudPlay"
        private const val NOTIFICATION_ID = 1001
        // 用 Media3 默认的渠道 id：播放时 Media3 会用它自己的渠道接管通知，
        // 两边保持一致，免得系统设置里出现两个重复的通知渠道
        private const val CHANNEL_ID = "default_channel_id"

        /** 静音占位段的 mediaId：界面上看到它就说明"第一段还在合成中" */
        const val ID_SILENCE = "@silence"

        const val PREF_PROGRESS = "readaloud_progress"
        const val PREF_SETTING = "readaloud_settings"
        private const val KEY_SPEAKER = "speaker"

        // ── 朗读设置：语速 + 停顿（S4-3）──────────────────────────────
        //
        // 两个设置都存进 SharedPreferences（和音色同一份），所以：
        //   · 界面和后台服务读的是同一份，不会两边不一致
        //   · App 关掉再打开、服务被系统回收重建，设置都还在
        private const val KEY_SPEED = "speed"
        private const val KEY_PAUSE = "pause_level"

        /** 多音字纠正词典开关（FR-23） */
        private const val KEY_POLYPHONE = "polyphone_dict"

        /** 语速的可调范围与步进（1.0 = 原速）。上下限再往外走，人声就开始发飘了 */
        const val MIN_SPEED = 0.6f
        const val MAX_SPEED = 2.0f
        const val SPEED_STEP = 0.1f

        /** 停顿档位序号 */
        const val PAUSE_COMPACT = 0
        const val PAUSE_NORMAL = 1
        const val PAUSE_RELAXED = 2

        /**
         * 停顿档位的具体数值。
         *
         * @param silenceScale    传给合成引擎，缩放**模型内部**的句间停顿（越小气口越短）
         * @param padEndBody      正文段落结尾额外补的静音（秒）
         * @param padStartChapter 章节标题段落开头额外补的静音（秒）
         * @param padEndChapter   章节标题段落结尾额外补的静音（秒）
         *
         * ⚠️ 为什么要有"档位"而不是让用户拖一个滑块？
         *    这个值最终体现在音频长度上，用户没法边拖边听（改一次要重新合成一段）。
         *    三档足够覆盖"想听快点/正常/想慢慢品"三种诉求，选起来没有负担。
         */
        class PauseStyle(
            val silenceScale: Float,
            val padEndBody: Float,
            val padStartChapter: Float,
            val padEndChapter: Float
        )

        private val PAUSE_STYLES = arrayOf(
            PauseStyle(0.4f, 0.05f, 0.15f, 0.30f),   // 紧凑：一口气往下读，很少停顿
            PauseStyle(0.8f, 0.12f, 0.25f, 0.50f),   // 标准：默认，句子之间留一个自然气口
            PauseStyle(1.3f, 0.25f, 0.50f, 1.00f)    // 舒缓：慢慢念，章节之间明显停顿
        )

        /** 界面用来设置语速 */
        fun saveSpeed(prefs: SharedPreferences, speed: Float) =
            prefs.edit().putFloat(KEY_SPEED, speed).apply()

        fun loadSpeed(prefs: SharedPreferences): Float =
            prefs.getFloat(KEY_SPEED, 1.0f).coerceIn(MIN_SPEED, MAX_SPEED)

        /** 界面用来设置停顿档位 */
        fun savePauseLevel(prefs: SharedPreferences, level: Int) =
            prefs.edit().putInt(KEY_PAUSE, level).apply()

        fun loadPauseLevel(prefs: SharedPreferences): Int =
            prefs.getInt(KEY_PAUSE, PAUSE_NORMAL).coerceIn(0, PAUSE_STYLES.size - 1)

        /** 取某个停顿档位的数值 */
        fun pauseStyleOf(level: Int): PauseStyle =
            PAUSE_STYLES[level.coerceIn(0, PAUSE_STYLES.size - 1)]

        /**
         * 多音字纠正词典（FR-23）的开关。
         * 默认开 —— 表里每一条都用模型词表机械校验过注音与声调，念音必然是对的；
         * 但如果 Leo 觉得某几条听着别扭，可以整表关掉回到原样。
         */
        fun savePolyphone(prefs: SharedPreferences, on: Boolean) =
            prefs.edit().putBoolean(KEY_POLYPHONE, on).apply()

        fun loadPolyphone(prefs: SharedPreferences): Boolean =
            prefs.getBoolean(KEY_POLYPHONE, true)

        /**
         * 段尾标点 → 停顿倍率（PRD FR-20 第 3 条"标点停顿映射"）。
         *
         * ⚠️ 只作用于**段与段之间**。句子内部（逗号、顿号）的停顿是合成模型
         *    自己的事，我们不去动 —— 没有听感验证手段时，动得越多越容易变糟。
         *    这里真正有用的是"以分号/破折号/省略号收尾"的段（并列句、列表项）：
         *    那种地方给足气口，才不像被硬生生掐断。
         */
        fun pauseFactorFor(tail: Char?): Float = when (tail) {
            '。', '！', '？', '.', '!', '?' -> 1.0f      // 句子结束 → 完整气口
            '…', '—', '；', ';' -> 0.75f                // 中停
            '，', '、', ',', '：', ':' -> 0.45f          // 短停
            else -> 0.8f                                // 没有标点收尾
        }

        // ── 睡眠定时（S4-5）──────────────────────────────────────────
        //
        // 三种状态：关 / 按分钟 / 念完本章后停。
        //
        // ⚠️ 为什么定时器必须放在**服务**里，而不是界面上（这是本项最关键的一条）？
        //    听书时用户往往早就锁屏了，界面的组合（Composition）虽然还活着，
        //    但一旦系统把 Activity 回收掉，界面上的一切就都没了。
        //    定时器放在界面里，锁屏久了会"到点不停"。
        //    服务是真正一直在后台活着的那一层，放这里才靠谱。
        private const val KEY_SLEEP_MODE = "sleep_mode"
        private const val KEY_SLEEP_UNTIL = "sleep_until"
        private const val KEY_SLEEP_TARGET = "sleep_target"
        private const val KEY_SLEEP_MINUTES = "sleep_minutes"

        /** 睡眠定时的状态值 */
        const val SLEEP_OFF = 0
        const val SLEEP_TIME = 1
        const val SLEEP_CHAPTER = 2

        /**
         * 到点后的淡出时长（毫秒）。
         * 太短（0.5 秒）会"啪"地断掉，半梦半醒时挺吓人；
         * 太长（十几秒）又会让人以为"怎么还在响"。4 秒是比较舒服的过渡。
         */
        const val SLEEP_FADE_MS = 4000L

        /** 界面上的分钟档位（分钟）。20 分钟是 PRD 里写明的验收场景 */
        val SLEEP_PRESETS = intArrayOf(20, 30, 60)

        fun saveSleepMode(prefs: SharedPreferences, mode: Int) =
            prefs.edit().putInt(KEY_SLEEP_MODE, mode).apply()

        fun loadSleepMode(prefs: SharedPreferences): Int = prefs.getInt(KEY_SLEEP_MODE, SLEEP_OFF)

        /** 按时长档：结束的墙钟时刻（毫秒时间戳） */
        fun saveSleepUntil(prefs: SharedPreferences, until: Long) =
            prefs.edit().putLong(KEY_SLEEP_UNTIL, until).apply()

        fun loadSleepUntil(prefs: SharedPreferences): Long = prefs.getLong(KEY_SLEEP_UNTIL, 0L)

        /** 按章节档：念到第几句就算这一章结束（下一章标题所在的那一句） */
        fun saveSleepTarget(prefs: SharedPreferences, sentence: Int) =
            prefs.edit().putInt(KEY_SLEEP_TARGET, sentence).apply()

        fun loadSleepTarget(prefs: SharedPreferences): Int =
            prefs.getInt(KEY_SLEEP_TARGET, Int.MAX_VALUE)

        /** 按时长档：用户选了多少分钟（只用来显示） */
        fun saveSleepMinutes(prefs: SharedPreferences, minutes: Int) =
            prefs.edit().putInt(KEY_SLEEP_MINUTES, minutes).apply()

        fun loadSleepMinutes(prefs: SharedPreferences): Int = prefs.getInt(KEY_SLEEP_MINUTES, 0)

        private fun clearSleepPrefs(prefs: SharedPreferences) {
            prefs.edit()
                .putInt(KEY_SLEEP_MODE, SLEEP_OFF)
                .putLong(KEY_SLEEP_UNTIL, 0L)
                .remove(KEY_SLEEP_TARGET)
                .apply()
        }

        /**
         * 真实段落的 mediaId 编码：
         *   g:<段号>:<起始句>:<句数>:<总时长毫秒>:<每句结束时刻，用 - 连接>
         *
         * 例：g:4:11:3:13700:4650-7360-13700
         *     表示第 4 段，从第 11 句起共 3 句，整段 13.7 秒，
         *     第 1 句念到 4.65s、第 2 句到 7.36s、第 3 句到段尾。
         *
         * ⚠️ 为什么不用播放列表下标定位（踩过）？
         *   列表最前面有一段静音占位，下标跟真实段落编号差 1。
         *   界面直接拿 currentMediaItemIndex 去查段落数组，
         *   会整体错位一段 —— 高亮指错地方、断点续听存的也是错的位置。
         *   把信息写进 mediaId 之后，界面读到什么就是什么，不会再错。
         *
         * ⚠️ 为什么要带上每句的结束时刻（踩过）？
         *   一开始图省事，界面是按"每句话的字数比例"去猜播到第几句的。
         *   实测下来根本不靠谱：中文标点不发音、各句语速也不一样，
         *   猜出来的位置会**跑在真实朗读的前面**（用户反馈"高亮已经到下一句了"）。
         *   现在改成合成完直接从音频波形里把句子边界测出来，一起塞进 mediaId。
         */
        fun buildMediaId(group: Int, start: Int, count: Int, durationMs: Long, bounds: IntArray): String {
            val b = if (bounds.isEmpty()) "" else bounds.joinToString("-")
            return "g:$group:$start:$count:$durationMs:$b"
        }

        /** 解析 mediaId；不是真实段落就返回 null（比如静音占位） */
        fun parseMediaId(id: String?): SegmentInfo? {
            if (id == null || !id.startsWith("g:")) return null
            val parts = id.split(":")
            if (parts.size != 6) return null
            return try {
                val bounds = if (parts[5].isEmpty()) IntArray(0)
                else parts[5].split("-").map { it.toInt() }.toIntArray()
                SegmentInfo(
                    groupIndex = parts[1].toInt(),
                    startSentence = parts[2].toInt(),
                    sentenceCount = parts[3].toInt(),
                    durationMs = parts[4].toInt(),
                    boundsMs = bounds
                )
            } catch (_: NumberFormatException) {
                null
            }
        }

        /** 界面用来设置音色 */
        fun saveSpeaker(prefs: SharedPreferences, speaker: Int) =
            prefs.edit().putInt(KEY_SPEAKER, speaker).apply()

        /** 断点续听：查某本书上次读到第几句（-1 表示没读过） */
        fun loadProgress(prefs: SharedPreferences, uri: String): Int = prefs.getInt(uri, -1)

        /** 断点续听：清除某本书的进度 */
        fun clearProgress(prefs: SharedPreferences, uri: String) =
            prefs.edit().remove(uri).apply()

        /**
         * 发一条"停止"指令给后台服务。
         * 界面统一走这里，不要用 stopService（原因见 stopReading 的注释）。
         */
        fun requestStop(context: android.content.Context) {
            runCatching {
                context.startService(
                    Intent(context, PlaybackService::class.java).apply { action = ACTION_STOP }
                )
            }
        }

        /**
         * 发一条"继续"指令给后台服务。
         *
         * ⚠️ 为什么"继续"不直接在界面调 MediaController.play()（踩过）？
         *   当播放器正好卡在**段尾**（上一段播完了、下一段还在合成，中间这几秒没声音），
         *   这时调 play()，ExoPlayer 会把**当前这一段从头再播一遍**，
         *   而不是接着往下走 —— 用户按了"继续"却听到刚念过的内容重复一次。
         *   交给服务处理则能分情况：已经有下一段就跳到下一段；还没合成好就静静等，
         *   新段落一到位自动接上（见 ensurePlaying）。
         */
        fun requestResume(context: android.content.Context) {
            runCatching {
                context.startService(
                    Intent(context, PlaybackService::class.java).apply { action = ACTION_RESUME }
                )
            }
        }

        /** 上一句 / 下一句。与通知栏的切段键同一条路，行为一致。 */
        fun requestStep(context: android.content.Context, forward: Boolean) {
            send(context, if (forward) ACTION_NEXT else ACTION_PREV)
        }

        /**
         * 跳到指定的某一句（点正文、点章节走这里）。
         *
         * ⚠️ 为什么不直接在界面 seekTo（踩过）？
         *   界面只知道"要跳哪一句"，不知道这句所在的段**有没有合成过**。
         *   已合成的段可以毫秒级切过去；没合成的只能重新起一轮（要等十几秒）。
         *   这个判断得由服务来做（它手上才有队列和段落表）。
         *   把书名(uri)一起带上，是为了防止"用户换了文件、队列里还是旧书"时跳错。
         */
        fun requestSeekSentence(context: android.content.Context, uri: String, sentence: Int) {
            send(context, ACTION_SEEK_SENTENCE) {
                putExtra(EXTRA_URI, uri)
                putExtra(EXTRA_SENTENCE, sentence)
            }
        }

        /**
         * 换书：作废当前这一轮朗读，等界面发新指令。
         *
         * ⚠️ 只作废"还没开始播"的那部分 —— 队列和断点都**保留**，
         *    用户切回上一本书时还能原处接着听（不必重新合成十几秒）。
         *    只在"这一轮的内容和当前队列对不上"时才清队列（见 switchBook）。
         */
        fun requestSwitchBook(context: android.content.Context, uri: String) {
            send(context, ACTION_SWITCH_BOOK) {
                putExtra(EXTRA_URI, uri)
            }
        }

        private fun send(
            context: android.content.Context,
            action: String,
            extra: (Intent.() -> Unit)? = null
        ) {
            runCatching {
                context.startService(
                    Intent(context, PlaybackService::class.java).apply {
                        this.action = action
                        extra?.invoke(this)
                    }
                )
            }
        }

        // ── 睡眠定时的三个指令（界面点那排按钮时调）──────────────────
        //
        // ⚠️ 为什么界面上点一下要绕一圈发给服务，而不是自己往设置里写个数？
        //   因为"谁来倒计时、到点谁来收尾"这个决定权在服务手上。
        //   界面只表达意图（"我要 20 分钟后停"），具体怎么计时、
        //   什么时候淡出、断点怎么存，都由服务统一处理 —— 只有一份逻辑，不会两边打架。

        /** 按分钟定时 */
        fun requestSleepMinutes(context: android.content.Context, minutes: Int) {
            send(context, ACTION_SLEEP) {
                putExtra(EXTRA_SLEEP_MODE, SLEEP_TIME)
                putExtra(EXTRA_SLEEP_MINUTES, minutes)
            }
        }

        /**
         * 念完本章后停。
         * @param targetSentence 下一章标题所在的那一句（界面算好后传进来，
         *                       因为只有界面手上有章节表）
         */
        fun requestSleepChapter(context: android.content.Context, targetSentence: Int) {
            send(context, ACTION_SLEEP) {
                putExtra(EXTRA_SLEEP_MODE, SLEEP_CHAPTER)
                putExtra(EXTRA_SLEEP_TARGET, targetSentence)
            }
        }

        /** 关闭睡眠定时 */
        fun requestSleepOff(context: android.content.Context) {
            send(context, ACTION_SLEEP) {
                putExtra(EXTRA_SLEEP_MODE, SLEEP_OFF)
            }
        }
    }

    private var player: ExoPlayer? = null
    /** 交给 MediaSession 的包装播放器（保持引用，别被回收） */
    private var sessionPlayer: Player? = null
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var kokoro: KokoroTts? = null

    private var sentences: List<Sentence> = emptyList()
    private var groups: List<TtsGroup> = emptyList()
    private var currentUri: String? = null

    /**
     * **这一轮朗读是从哪本书开始的**（起跑时记下，中途不变）。
     *
     * ⚠️ 为什么要和 [currentUri] 分成两个：
     *   换书判断必须用"起跑时那本"，不能用 currentUri ——
     *   doReading 一载入就把 currentUri 改成新书了，等合成循环真跑起来时
     *   两者已经一样，"是不是换了书"永远判成 false，协程停不下来（踩过）。
     *   readingUri 到下一轮 startReading 才更新。
     */
    private var readingUri: String? = null
    private var nextGroup = 0
    private var stopRequested = false

    /**
     * "用户按了继续，但下一段还没合成好，先等着" 的标记。
     *
     * 用在播放器**卡在段尾**的时候：这时不能直接 play()（会把当前这段从头重播一遍），
     * 所以先记个意图，等新段落到队列里再由 ensurePlaying 接上。
     */
    private var resumePending = false

    /**
     * 「这一轮起播后，要在段内往前挪到第几句」（-1 = 不用挪）。
     *
     * ⚠️ 为什么需要它：
     *   合成的最小单位是**段**，不是句。用户点了第 9 句时，如果那一段还没合成过，
     *   我们只能从**包含它的那一段**开头开始合成 —— 播放器一接上就先念段首那一句
     *   （第 9 句若在本段第 3 句，用户会先听到第 7 句），跟他点的不一致。
     *   所以段一合成好、播放器接上之后，还要在段内按实测的句子边界挪到那一句。
     *   （段内边界是合成时从波形里量出来的，挪过去是毫秒级、不用重新合成。）
     */
    private var pendingSeekSentence = -1

    /**
     * 当前的停顿档位。
     *
     * ⚠️ 每合成一段之前都会重新读一次设置，所以用户改完停顿，
     *    **正在念的这段不变、下一段开始生效** —— 不需要重合成、不影响听感连续性。
     */
    private var pauseStyle = PAUSE_STYLES[PAUSE_NORMAL]

    /**
     * 睡眠定时的倒计时任务（只有"按分钟"档才有；章节档靠换段事件触发）。
     */
    private var sleepJob: Job? = null

    private lateinit var sessionActivity: PendingIntent

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this).build().apply {
            // ── 音频焦点（PRD FR-19，P0）──────────────────────────────
            // 「音频焦点」是安卓的一套规矩：同一时刻只允许一个 App 出声，
            // 别人要用喇叭时得先跟系统"申请"，系统会通知先来的那位让位。
            //
            // 第二个参数 handleAudioFocus = true 之后，ExoPlayer 会自动帮我们做全套：
            //   · 来电话 / 微信语音  → 收到"永久失去焦点"   → 自动暂停
            //   · 导航播报 / 短提示音 → 收到"暂时失去焦点"   → 自动暂停
            //   · 通话结束 / 提示音播完 → 焦点还回来       → **自动接着播**（不会重新开始）
            //   · 别的音乐 App 开始播 → 我们自动停，声音不打架
            //
            // contentType 声明成"语音"（而不是"音乐"）是有意为之：
            // 系统由此知道这是人声朗读 —— 导航播报时会用更合适的方式处理
            // （例如把我们的音量压低而不是直接掐断）。
            //
            // ⚠️ 不设置这个的话，来电话时 App 会**继续读**，跟通话声音叠在一起（S3 漏掉的一项）。
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )

            // ── 拔耳机 / 蓝牙断开 → 自动暂停 ────────────────────────────
            // 这是系统的一个标准事件（"音频通路变得嘈杂"）。
            // 不处理的话，拔掉耳机的瞬间声音会从手机外放突然炸出来 ——
            // 在图书馆、地铁上非常尴尬。
            // 注意：这里只暂停、不会自动恢复（这是安卓的通用礼仪，别的播放器也一样）。
            setHandleAudioBecomingNoisy(true)

            // ── 息屏时保持 CPU 醒着 ────────────────────────────────────
            // WAKE_MODE_LOCAL 表示"只保证本机 CPU 不休眠，不需要网络"。
            // 为什么需要：手机息屏后系统会让 CPU 进入省电状态，而我们后台还在
            // 吭哧吭哧合成音频；CPU 一睡，合成就断了 —— 表现就是"锁屏听一会儿卡住"。
            // ExoPlayer 只在真正播放期间持有这把锁，暂停时自动释放，不会白耗电。
            // 权限（WAKE_LOCK）是 Media3 自带的，我们不额外声明。
            setWakeMode(C.WAKE_MODE_LOCAL)

            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // 每换一段就把进度记下来 —— 这就是「断点续听」。
                    // 用 mediaId 解析，不再用下标（下标含静音占位，会错位）
                    val info = parseMediaId(mediaItem?.mediaId) ?: return
                    saveProgress(info.startSentence)

                    // 睡眠定时（章节档）：播到下一章的标题段 = 这一章念完了 → 收工。
                    // ⚠️ 判的是"标题段"而不是"第几段"：章节标题在分组时一定独立成段
                    //    （见 TextFileReader.groupForTts），所以这个判断不受分段粗细影响。
                    val sleepPrefs = getSharedPreferences(PREF_SETTING, MODE_PRIVATE)
                    if (loadSleepMode(sleepPrefs) == SLEEP_CHAPTER &&
                        info.startSentence >= loadSleepTarget(sleepPrefs)
                    ) {
                        fadeOutAndPause("本章已念完")
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    // 暂停时撤掉"等待续播"的意图，否则等下一段合成好，
                    // ensurePlaying 会把它自己唤醒（用户按的暂停就白按了）。
                    //
                    // ⚠️ 但要区分是谁按的暂停（踩过）：
                    //   只有**用户主动暂停**才撤意图。
                    //   来电 / 导航播报抢占焦点时系统也会让我们停（reason = AUDIO_FOCUS_LOSS），
                    //   那种情况属于"被迫让位"，焦点还回来系统会自动继续 ——
                    //   要是把意图清了，就再也接不上了，用户会发现"挂完电话不接着读了"。
                    if (!playWhenReady &&
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
                    ) {
                        resumePending = false
                    }
                }
            })
        }

        // 点通知栏时回到主界面
        sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 交给控制器的不是裸的 ExoPlayer，而是包装过的一层（原因见 SteadyNavPlayer）
        sessionPlayer = SteadyNavPlayer(player!!)
        session = MediaSession.Builder(this, sessionPlayer!!)
            .setSessionActivity(sessionActivity)
            .build()
        addSession(session!!)

        // 服务被系统回收过、这会儿又重建起来了：把还生效的睡眠定时接回来。
        // （"按分钟"档存的是结束时刻，所以服务中途死掉再活过来也能算准）
        rearmSleepFromPrefs()

        // ⚠️ 这里**故意不调用 startForeground()**。
        //    因为主界面一打开就会连接本服务（Media3 的标准做法：buildAsync 会绑定服务），
        //    如果在这里就进前台，用户什么都还没做，通知栏就会凭空多出一条
        //    "正在准备朗读…"，很奇怪。
        //    真正需要进前台是在收到 ACTION_START（用户点了朗读）那一刻，见 onStartCommand。
        Log.i(TAG, "服务已创建，等待指令")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 建立通知渠道 + 立刻进入前台 */
    private fun enterForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "阅听朗读", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台朗读时的播放控制" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification())
    }

    /** 占位通知：先告诉用户"正在准备"，也满足系统对前台服务的要求 */
    private fun buildPlaceholderNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("阅听 Readaloud")
            .setContentText("正在准备朗读…")
            .setContentIntent(sessionActivity)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // ⚠️ 前台服务的硬性要求：从 startForegroundService() 算起几秒内
                //    必须调用 startForeground()，否则系统直接判 ANR 把服务杀掉，
                //    而且日志里看不出崩溃（特别难查，踩过）。
                //    而我们合成第一段要二十多秒，等不起 —— 所以这里立刻进前台。
                //    通知 id 用 1001（Media3 默认的那个），后面 Media3 会接管同一条通知，
                //    不会出现两条。
                enterForeground()
                val uri = intent.getStringExtra(EXTRA_URI)
                val from = intent.getIntExtra(EXTRA_FROM, 0)
                if (uri != null) startReading(Uri.parse(uri), from)
            }
            ACTION_RESUME -> resumePlayback()
            ACTION_STOP -> stopReading()
            ACTION_PREV -> stepSentence(-1)
            ACTION_NEXT -> stepSentence(+1)
            ACTION_SEEK_SENTENCE -> {
                val uri = intent.getStringExtra(EXTRA_URI)
                val idx = intent.getIntExtra(EXTRA_SENTENCE, -1)
                if (uri != null && idx >= 0) seekToSentence(Uri.parse(uri), idx)
            }
            ACTION_SWITCH_BOOK -> {
                switchBook(intent.getStringExtra(EXTRA_URI))
            }
            ACTION_SLEEP -> {
                setSleep(
                    mode = intent.getIntExtra(EXTRA_SLEEP_MODE, SLEEP_OFF),
                    minutes = intent.getIntExtra(EXTRA_SLEEP_MINUTES, 0),
                    target = intent.getIntExtra(EXTRA_SLEEP_TARGET, Int.MAX_VALUE)
                )
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 「继续」：恢复播放（服务端实现，界面只管发指令）。
     *
     * ⚠️ 为什么由服务来决定"从哪儿接"？
     *   播放器有可能正卡在**段尾**（上一段播完了、下一段还在合成，中间这几秒是静音）。
     *   这种状态下直接调 play()，ExoPlayer 会把**当前这一段从头再播一遍**，
     *   而不是接着往下走 —— 用户按了「继续」却听到刚念过的内容重复一次（实测踩到过）。
     *   所以分两种情况处理：
     *     · 队列里已经有下一段 → 直接跳到下一段接着播
     *     · 还没有（还在合成中）→ 只记下"等会儿接着播"的意图，
     *       新段落一进队列，ensurePlaying 就会自动接上
     */
    private fun resumePlayback() {
        val p = player ?: return
        if (p.mediaItemCount == 0) return
        if (p.playbackState == Player.STATE_ENDED) {
            val next = p.currentMediaItemIndex + 1
            if (next < p.mediaItemCount) {
                p.seekTo(next, 0L)
                p.play()
            } else {
                resumePending = true
                Log.i(TAG, "已记下继续意图，等下一段合成好自动接着播")
            }
        } else {
            p.play()
        }
    }

    // ────────────────────── 跳转（上一句 / 下一句 / 跳到某一句）──────────────────────
    //
    // 这几件事都必须在服务里做，因为只有它同时掌握三样东西：
    //   · 播放队列（哪些段已经合成好、在下标几）
    //   · 每段的句子边界（mediaId 里，合成时从波形实测出来的）
    //   · 完整的句子表（能判断"是不是到头了"）
    //
    // 为什么能精确到"句"而不是只能"段"？
    //   合成时已经把每句话在本段音频里的结束时刻测出来写进 mediaId 了，
    //   所以段内某一句的起始时刻可以直接查出来，seek 过去即可 —— 毫秒级，不用重新合成。

    /** 现在播到第几句（看本段播到第几毫秒，对着句子边界查） */
    private fun currentSentenceIndex(p: Player, info: SegmentInfo): Int {
        val bounds = info.boundsMs
        if (bounds.size != info.sentenceCount) return info.startSentence
        val pos = p.contentPosition
        for (i in bounds.indices) {
            if (pos < bounds[i]) return info.startSentence + i
        }
        return info.startSentence + info.sentenceCount - 1
    }

    /** 某一句在**本段音频里**的起始毫秒数（段内相对位置） */
    private fun sentenceStartMs(info: SegmentInfo, sentenceIndex: Int): Long {
        val rel = sentenceIndex - info.startSentence
        if (rel <= 0) return 0L
        val bounds = info.boundsMs
        return if (bounds.size == info.sentenceCount) bounds[rel - 1].toLong()
        // 没测出边界就按句数均分（兜底，总比不动强）
        else info.durationMs.toLong() * rel / info.sentenceCount.coerceAtLeast(1)
    }

    /** 找"第 group 段"在播放队列里的下标（-1 表示这段还没合成好） */
    private fun findItemIndexForGroup(group: Int): Int {
        val p = player ?: return -1
        for (i in 0 until p.mediaItemCount) {
            if (parseMediaId(p.getMediaItemAt(i).mediaId)?.groupIndex == group) return i
        }
        return -1
    }

    /**
     * 上一句（delta = -1）/ 下一句（delta = +1）。
     *
     * 「上一句」的规则跟大多数播放器一样，免得用户困惑：
     *   · 这一句已经念了一会儿（超过 2 秒）→ 回到**本句开头**（重听这句）
     *   · 刚切过来还没念两句        → 再往上跳**一句**
     */
    private fun stepSentence(delta: Int) {
        val p = player ?: return
        if (p.mediaItemCount == 0 || sentences.isEmpty()) return

        val info = parseMediaId(p.currentMediaItem?.mediaId)
        if (info == null) {
            // 还停在静音占位上（第一段没合成好）。按"上一句"就退回第一段开头。
            if (delta < 0) {
                val idx = findItemIndexForGroup(0)
                if (idx >= 0) p.seekTo(idx, 0L)
            }
            return
        }

        val cur = currentSentenceIndex(p, info)
        if (delta > 0) {
            val target = cur + 1
            if (target >= sentences.size) return          // 已经是最后一句
            jumpSentence(target, allowRestart = false)
        } else {
            val startMs = sentenceStartMs(info, cur)
            val target = if (p.contentPosition - startMs > 2000L) cur else cur - 1
            if (target < 0) {
                p.seekTo(p.currentMediaItemIndex, 0L)     // 已经是全书第一句，回本段开头
                return
            }
            jumpSentence(target, allowRestart = false)
        }
    }

    /**
     * 点正文某一句 / 点章节：跳到那一段。
     *
     * 和「上一句/下一句」的区别：这里**允许重新起一轮**。
     * 因为用户是明确要去一个可能很远的章节，等一等是合理的；
     * 而按"上一句/下一句"只是想微调，不该让整本书重新合成。
     */
    private fun seekToSentence(uri: Uri, sentence: Int) {
        val p = player ?: return
        if (sentence < 0) return

        // ⚠️⚠️ 这里**绝不能**先用"服务端此刻的句子表"去夹取句号（踩过，用户报的 bug）：
        //    服务刚被创建、还没读过这本书时 sentences 是空的，
        //    上界 (size-1).coerceAtLeast(0) 就成了 0 —— 用户点第 800 句，
        //    递下去却变成第 0 句，于是"第一次点后面的句子却从头念"，
        //    等这轮念起来、服务端有句子表了，再点一次才跳对。
        //    正解：句号原样传给 startReading，由 doReading 在**载入文件之后**、
        //    拿到真实句子表时再夹取（那时 size 才是对的）。
        //    「换了书」也一样 —— 旧书的句子表去夹新书的句号，同样是错的。
        Log.i(
            TAG,
            "点句请求：第 ${sentence + 1} 句（服务端当前 ${sentences.size} 句，" +
                "同一本书=${uri.toString() == currentUri}）"
        )

        // 换了书 / 服务还没载入过内容 → 老实重新起一轮
        if (uri.toString() != currentUri || groups.isEmpty() || sentences.isEmpty()) {
            enterForeground()
            startReading(uri, sentence)
            return
        }
        val target = sentence.coerceIn(0, sentences.size - 1)
        jumpSentence(target, allowRestart = true, restartUri = uri)
    }

    /**
     * 真正把播放位置挪到第 target 句。
     *
     * @param allowRestart 目标所在的段还没合成时，是否允许重新起一轮。
     *                     false（上一句/下一句）：只停在当前段末尾，
     *                       等后台把下一段合成好，ensurePlaying 会自然接上 —— 不打断正在跑的一轮。
     *                     true（点句子/点章节）：重新起一轮（用户明确要去那里，愿意等）。
     */
    private fun jumpSentence(target: Int, allowRestart: Boolean, restartUri: Uri? = null) {
        val p = player ?: return
        // 用户又做了一次明确的跳转/步进 → 丢掉"起播后要挪到某句"的旧意图，
        // 否则等那一段合成好，会把用户刚点的位置又拽回上一次点的句子。
        pendingSeekSentence = -1
        val g = groups.indexOfLast { it.startSentence <= target }.coerceAtLeast(0)
        val itemIdx = findItemIndexForGroup(g)

        if (itemIdx < 0) {
            if (allowRestart && restartUri != null) {
                enterForeground()
                startReading(restartUri, target)
                return
            }
            // 目标段还在合成中：停在当前段末尾，等它接上
            val cur = parseMediaId(p.currentMediaItem?.mediaId)
            if (cur != null && p.currentMediaItemIndex >= 0 && target > cur.startSentence) {
                p.seekTo(p.currentMediaItemIndex, cur.durationMs.toLong())
            }
            Log.i(TAG, "第 ${target + 1} 句所在段还没合成好，先停在段尾等它")
            return
        }

        val info = parseMediaId(p.getMediaItemAt(itemIdx).mediaId)
        val posMs = if (info != null) sentenceStartMs(info, target) else 0L
        p.seekTo(itemIdx, posMs)
        // ⚠️ 保持播放/暂停状态不变：暂停中按"下一句"只是移动位置（方便连续翻找），
        //    不会突然自己响起来；正在播则接着播。
        if (p.playWhenReady) {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
        Log.i(
            TAG, "跳到第 ${target + 1} 句（第 ${g + 1} 段，段内 ${posMs}ms，" +
                "播放状态 ${if (p.playWhenReady) "继续" else "保持暂停"}）"
        )
    }

    private fun startReading(uri: Uri, from: Int) {
        // 换书了：「念完本章后停」是对**上一本**说的，换一本就作废（否则可能刚开念就停）
        val sleepPrefs = getSharedPreferences(PREF_SETTING, MODE_PRIVATE)
        if (loadSleepMode(sleepPrefs) == SLEEP_CHAPTER && uri.toString() != currentUri) {
            clearSleepPrefs(sleepPrefs)
            Log.i(TAG, "换了书，章节档睡眠定时已作废")
        }
        job?.cancel()
        stopRequested = false
        resumePending = false
        // 这一轮想从"哪一句"听起：段合成好之后由 applyPendingSeek 在段内精确落位。
        // （ACTION_START 传 0 时它就是 0，等于段首，applyPendingSeek 会直接放过）
        pendingSeekSentence = from
        // 起跑书名先记下来（换书判断要用，见 readingUri 的说明）
        readingUri = uri.toString()
        job = scope.launch {
            val p = player ?: return@launch
            currentUri = uri.toString()
            // 立刻掐掉上一轮的声音，免得"刚点了新位置，旧内容还在响"
            runCatching { p.pause() }

            // ⚠️ 整个流程都要兜住异常：读文件失败、模型加载失败等等，
            //    否则协程里抛出未捕获异常会直接崩掉整个进程（表现为闪退）。
            try {
                doReading(uri, from, p)
            } catch (t: Throwable) {
                Log.e(TAG, "朗读失败", t)
            }
        }
    }

    private suspend fun doReading(uri: Uri, from: Int, p: ExoPlayer) {
        // ⚠️ 起跑第一件事：确认这一轮没被"换书"作废过。
        //    switchBook 是在**载入文件之前**发到的（界面那边先解析好才发指令），
        //    所以这里必须再看一眼，否则会出现
        //    "作废标记刚被新一轮 startReading 清掉 → 旧协程复活 → 两本一起念"。
        if (stopRequested) {
            Log.i(TAG, "这一轮已被换书作废，直接退出（当前界面的书：$uri）")
            return
        }
        // 1) 读文件、切句、认章节、分段（在后台线程做，避免卡界面）
        //
        // ⚠️⚠️ 必须走和界面**同一个入口**（BookReader），这是踩过大坑的地方：
        //    这里原本是直接调 TextFileReader.read 读纯文本的。换成 EPUB 之后
        //    就成了"服务端把 zip 二进制当文本解码"—— 切出来全是乱码，
        //    合成、念出来的是一串怪声；而界面上显示的却是正确的中文
        //    （界面走 EpubReader）。同一个文件被两边各读了一遍，还读出两种东西。
        //
        //    顺带还统一了章节识别：界面用书里的 nav/NCX 目录、服务端用正则猜，
        //    两边章节不一样 → 分组就不一样 → mediaId 里的"第几段"两边各说各话，
        //    高亮和跳句会整体错位。
        val content = withContext(Dispatchers.IO) { BookReader.load(this@PlaybackService, uri) }
        if (content == null) {
            Log.e(TAG, "文件读不出来：$uri")
            return
        }
        sentences = content.sentences
        groups = content.groups

        // ⚠️ 句号必须**等到这里**（真实句子表已就绪）才夹取。
        //    调用方在"服务端还没读过这本书"时拿到的是空表，那时夹取会把句号压成 0
        //    （用户症状：第一次点后面的句子，却从头开始念；再点一次才对）。
        val fromClamped = from.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
        // ⚠️ 必须用 indexOfLast：点段落中间的某一句时，要落到「包含这一句」的那一段。
        //    用 indexOfFirst 会跳到下一段，把这一段剩下的内容整段跳过（踩过）。
        nextGroup = groups.indexOfLast { it.startSentence <= fromClamped }.coerceAtLeast(0)
        // 日志里带上首段开头 —— 排查"念出来的东西不对"时，光看句数是不够的：
        // 之前服务端把 EPUB 的 zip 二进制当文本解码，乱码切出来的句数
        // 恰好和正常切句接近，只看数字根本发现不了。直接看一眼内容最靠谱。
        Log.i(
            TAG,
            "共 ${sentences.size} 句，分成 ${groups.size} 段，从第 ${nextGroup + 1} 段开始" +
                "；首段开头：${groups.firstOrNull()?.text?.take(24)}"
        )

        // 2) 准备本地引擎
        val engine = kokoro ?: KokoroTts(this@PlaybackService).also { kokoro = it }
        if (!engine.isReady()) {
            val r = engine.load { }
            if (r.isFailure) {
                Log.e(TAG, "模型加载失败", r.exceptionOrNull())
                return
            }
        }
        val settingPrefs = getSharedPreferences(PREF_SETTING, MODE_PRIVATE)
        val speaker = settingPrefs.getInt(KEY_SPEAKER, KokoroTts.DEFAULT_SPEAKER)
        // 语速 / 停顿也一并读出来 —— 服务刚被系统回收后重建时，全靠这里恢复
        pauseStyle = pauseStyleOf(loadPauseLevel(settingPrefs))
        val savedSpeed = loadSpeed(settingPrefs)

        // 3) 清掉上次的临时音频
        val dir = File(cacheDir, "tts").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }

        p.clearMediaItems()
        // 语速：新造的播放器默认是 1.0 倍，这里把用户存的设置补上。
        // （服务被系统回收后重建、或者刚打开 App 开始听，都会走到这一步）
        p.setPlaybackSpeed(savedSpeed)
        p.playWhenReady = true

        // 4) 先塞一段 0.8 秒静音并立刻开播。
        //    目的：让播放列表**马上非空** —— 界面靠"列表里有没有内容"判断
        //    "朗读是不是在进行中"。合成第一段要二十多秒，没有它的话
        //    这二十多秒界面会显示成"没在播"，用户会以为点了没反应。
        val silence = File(dir, "silence.wav")
        withContext(Dispatchers.IO) {
            WavWriter.write(silence, FloatArray((24000 * 0.8).toInt()), 24000)
        }
        p.addMediaItem(
            MediaItem.Builder()
                .setUri(Uri.fromFile(silence))
                .setMediaId(ID_SILENCE)
                .build()
        )
        p.prepare()
        p.play()
        Log.i(TAG, "播放器已启动（静音占位，随后接真实内容）")

        // 5) 后台接着合成后面的段落，每合成一段就确保播放器在播
        while (!stopRequested && nextGroup < groups.size) {
            // ⚠️ 用户按了暂停（或刚从暂停恢复）时先别合成。
            //    合成很吃 CPU（麒麟 710F 上一段要十几秒），暂停了还在后台猛算，
            //    手机又热又费电，用户会觉得"明明按了暂停怎么还卡"。
            //    playWhenReady 就是播放栏那个播放/暂停键的意图：
            //    为 true 时才继续往下合成；恢复播放后这里会自动接着算。
            //    （resumePending 表示"用户按了继续、只是还没内容可接"，那也要接着合成）
            if (!p.playWhenReady && !resumePending) {
                delay(300)
                continue
            }
            synthesizeNext(engine, dir, speaker)
            ensurePlaying(p)
            // 段接上之后再挪到用户点的那一句（点段首那一句时这步什么也不做）
            applyPendingSeek(p)
            delay(50)
        }
        if (!stopRequested) {
            ensurePlaying(p)
            Log.i(TAG, "全部段落都已合成，共 ${p.mediaItemCount} 项")
        }
    }

    /**
     * 保证播放器确实在出声。
     *
     * ⚠️ 踩过三个坑：
     *   1. 合成比播放慢，播放器常常把已合成的部分播完就进入"播完"状态，
     *      等新段落加进来它**不会自己接着播** —— 表现就是"队列里明明有内容，就是没声音"。
     *      所以每加一段都要检查一下状态，必要时把它叫醒。
     *   2. 但如果无条件跳到最后一段，等全部合成完、最后一段也播完之后，
     *      它会被**从头再播一遍**。所以只在"确实有没播过的新段"时才跳过去。
     *   3. **用户按了暂停就绝不能叫醒它**。playWhenReady == false 代表
     *      "我按了暂停/停止"，这时如果还去 play()，暂停会被后台悄悄取消掉，
     *      听起来就是"明明按了暂停，过一会儿自己又响了"（界面按钮也会跟着乱跳）。
     */
    private fun ensurePlaying(p: ExoPlayer) {
        if (p.mediaItemCount == 0) return

        if (p.playbackState == Player.STATE_ENDED) {
            // 播放器停在段尾。如果队列尾部又攒了新内容，就从**紧接着的下一段**接着播。
            val next = p.currentMediaItemIndex + 1
            if (next < p.mediaItemCount && (p.playWhenReady || resumePending)) {
                // ⚠️ 必须挑"紧接着的下一段"，不能跳到最后一段：
                //    暂停期间可能已经攒了好几段没播，直接跳过去会把中间那些**整段漏掉**。
                // ⚠️ 也要先看用户有没有暂停：暂停时队列变长，不该把它自己唤醒。
                p.seekTo(next, 0L)
                p.play()
                resumePending = false
                Log.i(TAG, "播完了但已有新内容，接着播第 $next 项")
            }
            // 没有新内容就静静等。（这里硬要 play() 会把最后一段重播一遍，是个 bug）
            return
        }

        if (p.isPlaying) return
        if (!p.playWhenReady && !resumePending) return
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
        resumePending = false
    }

    /**
     * 把「这一轮想从哪句听起」落到**段内**的精确位置。
     *
     * 见 pendingSeekSentence 的说明：合成单位是段，用户点的句子若在本段中间，
     * 光靠"从这一段开始播"会先多念前几句。这里在段合成好、播放器接上之后，
     * 用 mediaId 里实测的句子边界挪过去。
     *
     * 什么时候什么也不做：
     *   · 没记着目标句（上一句/下一句那种微调、或者已经挪过了）
     *   · 目标句所在的段还没合成好 → 留着，等后面的循环再来
     *   · 目标句正好是段首那一句 → 本来就在这儿，不必 seek（省掉一次无谓的跳转）
     */
    private fun applyPendingSeek(p: ExoPlayer) {
        val target = pendingSeekSentence
        if (target < 0) return
        val g = groups.indexOfLast { it.startSentence <= target }.coerceAtLeast(0)
        val itemIdx = findItemIndexForGroup(g)
        if (itemIdx < 0) return
        val info = parseMediaId(p.getMediaItemAt(itemIdx).mediaId) ?: return
        pendingSeekSentence = -1
        if (target - info.startSentence <= 0) return
        val posMs = sentenceStartMs(info, target)
        p.seekTo(itemIdx, posMs)
        Log.i(
            TAG, "起播定位：第 ${target + 1} 句（本段 ${info.sentenceCount} 句，段内 ${posMs}ms）"
        )
    }

    /** 合成接下来的一段，存成 WAV 并加进播放列表（mediaId 里带上每句话的边界） */
    private suspend fun synthesizeNext(engine: KokoroTts, dir: File, speaker: Int) {
        if (nextGroup >= groups.size) return
        val idx = nextGroup
        val g = groups[idx]

        // ⚠️ 每合成一段前重新读一次停顿设置：用户在听的过程中改了档位，
        //    正在念的这段感受不到，**下一段开始就用新设置**（这是约定好的行为）。
        val style = pauseStyleOf(loadPauseLevel(getSharedPreferences(PREF_SETTING, MODE_PRIVATE)))
        pauseStyle = style

        // 多音字纠正词典的开关（FR-23）—— 同样每段重读，用户改了设置下一段就生效
        val polyphoneOn = loadPolyphone(getSharedPreferences(PREF_SETTING, MODE_PRIVATE))

        // 语速固定按原速合成（见 KokoroTts.synthesize 的说明）——
        // 变速交给播放器做，这样已经在队列里的音频也能跟着变速，不用重合成。
        //
        // ⚠️ 合成前先做正文归一化（PRD FR-20）：把阿拉伯数字、百分比、单位、日期时间
        //    等转成中文读法。模型读阿拉伯数字时用的是非中文音素规则，音调会明显别扭
        //    （用户反馈："1.1"这种小标题要么被跳过、要么读成"1，1"，"%"被读成英文 percent）。
        // ⚠️ 只改**送去合成的这份文本**：界面上显示的句子仍旧是原文，
        //    而且归一化不改变句子划分，所以高亮 / 跳句 / 断点全都不受影响。
        val spoken0 = TextNormalizer.normalize(g.text)
        // 多音字纠正词典（FR-23）：默认开着，可在设置页整表关掉。
        // ⚠️ 放在归一化**之后**：词典里的替换是纯中文词，数字/标点规则不会跟它打架。
        val spoken = if (polyphoneOn) PronunciationDict.apply(spoken0) else spoken0
        // ⚠️ 前 3 段把**全文**记下来（实际朗读文本 + 原文对照）：
        //    "送进合成器的到底是什么"只能从日志看。以前只打前 32 个字 ——
        //    结果数字被拦腰截断、模型不认识的字这类问题在日志里完全看不出来，
        //    只能等用户戴上耳机听出来（吃过这个亏）。
        if (idx < 3) {
            Log.i(TAG, "第 ${idx + 1} 段【朗读文本】${spoken.take(240)}")
            if (spoken != g.text) Log.i(TAG, "第 ${idx + 1} 段【原文对照】${g.text.take(240)}")
        } else if (spoken != g.text) {
            Log.i(TAG, "第 ${idx + 1} 段朗读文本已规范化：${g.text.take(24)} → ${spoken.take(32)}")
        }
        val audio = engine.synthesize(spoken, speaker, silenceScale = style.silenceScale)
        if (audio == null) {
            Log.e(TAG, "第 ${idx + 1} 段合成失败，跳过")
            nextGroup++
            return
        }

        val startSentence = g.startSentence
        val endSentence = (groups.getOrNull(idx + 1)?.startSentence ?: sentences.size)
            .coerceAtMost(sentences.size)
        val count = (endSentence - startSentence).coerceAtLeast(1)

        // 段落首尾补的静音：数值来自「停顿」档位（章节标题前后更明显）
        // ⚠️ 正文段的**结尾停顿再按标点分级**（PRD FR-20 第 3 条"标点停顿映射"）：
        //    ⚠️ 句子内部（逗号顿号）的停顿是模型自己的事，我们不掺和 ——
        //       这里只调"两段之间"的气口，别把句子里的节奏也搅乱。
        //       真正有用的是"以分号/破折号/省略号收尾"的段（列表项、并列句），
        //       那种地方给足气口才不像被硬生生掐断。
        val tail = g.text.trimEnd().lastOrNull()
        val padStart = if (g.isChapter) style.padStartChapter else 0f
        val padEnd = if (g.isChapter) style.padEndChapter
        else style.padEndBody * pauseFactorFor(tail)
        val padStartMs = (padStart * 1000).toInt()
        val seconds = audio.samples.size.toFloat() / audio.sampleRate + padStart + padEnd
        val durationMs = (seconds * 1000).toLong()

        // 从音频波形里实测每句话的结束时刻 —— 界面高亮准不准全靠它
        val counts = IntArray(count) { i ->
            sentences.getOrNull(startSentence + i)?.text?.length ?: 1
        }
        val bounds = withContext(Dispatchers.Default) {
            detectSentenceBounds(
                audio.samples, audio.sampleRate, counts, padStartMs, durationMs.toInt()
            )
        }

        val file = File(dir, "seg_$idx.wav")
        withContext(Dispatchers.IO) {
            WavWriter.write(
                file, audio.samples, audio.sampleRate,
                padStartSec = padStart, padEndSec = padEnd
            )
        }

        player?.addMediaItem(
            MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setMediaId(buildMediaId(idx, startSentence, count, durationMs, bounds))
                .build()
        )
        Log.i(
            TAG, "已合成 ${idx + 1}/${groups.size} 段（含 $count 句，" +
                "句子边界 ${if (bounds.isEmpty()) "未测出" else bounds.joinToString("/") + " ms"}）"
        )
        nextGroup++

        // ⚠️⚠️ 必须在**每个副作用之后**重新检查"这一轮是否已作废"（v0.3.3 自检时发现）：
        //   合成一段要十几秒，期间换书指令根本插不进来（switchBook 只是把
        //   stopRequested 置 true，要等这一段做完、循环回到头部才看得到）。
        //   那一段虽然已经合成好，但它属于**上一本书**：
        //     · 留在队列里 → 换书后点「继续」会把上一本的内容放出来（正是这个 bug）
        //     · 段切换时还会触发 saveProgress → 把上一本的断点写进这本书的记录
        //   所以这里：作废了就立刻把这十几秒的成果扔掉，并删掉临时 wav。
        if (stopRequested) {
            nextGroup = idx          // 下一轮（真正属于新书的那轮）会重新设它
            Log.i(TAG, "这一轮已被换书作废，丢弃刚合成的第 ${idx + 1} 段（不计入队列）")
            runCatching { player?.removeMediaItem(player!!.mediaItemCount - 1) }
            runCatching { file.delete() }
        }
    }

    /**
     * 从合成出来的音频里，实测出**每句话的结束时刻**。
     *
     * 为什么需要它（踩过）？
     *   界面一开始是按"每句话的字数比例"去猜播到第几句的，结果根本不准：
     *   中文标点不发音、各句语速也不一样，猜出来的位置会**跑在真实朗读前面**
     *   —— 用户反馈"正在朗读的句子没高亮，高亮已经到下一句了"。
     *
     *   好在 Kokoro 合成时，句子之间（甚至逗号处）会留下约 1 秒的停顿，
     *   在波形上非常清楚。把这些停顿找出来，再按各句字数比例对号入座，
     *   就能得到每句话在音频里真实的结束时刻。
     *
     * @param samples    合成出的原始音频（还没加首尾静音）
     * @param counts     段内每句话的字数
     * @param padStartMs 这段音频开头补的静音长度
     * @param totalMs    整段音频的完整时长（含首尾静音）
     * @return 每句话的结束时刻；空数组表示没能测准，界面会退回按字数估算
     */
    private fun detectSentenceBounds(
        samples: FloatArray,
        sampleRate: Int,
        counts: IntArray,
        padStartMs: Int,
        totalMs: Int
    ): IntArray {
        if (counts.size < 2 || samples.isEmpty() || totalMs <= 0) return IntArray(0)

        val win = sampleRate / 100                 // 10 毫秒一个分析窗口
        if (win <= 0 || samples.size < win * 5) return IntArray(0)
        val frames = samples.size / win

        // 1) 每个窗口算一次音量，顺便记下峰值
        val rms = FloatArray(frames)
        var peak = 0f
        for (f in 0 until frames) {
            val base = f * win
            var sum = 0.0
            for (i in 0 until win) {
                val v = samples[base + i].toDouble()
                sum += v * v
            }
            val r = kotlin.math.sqrt(sum / win).toFloat()
            rms[f] = r
            if (r > peak) peak = r
        }
        if (peak <= 1e-6f) return IntArray(0)
        val threshold = peak * 0.015f              // 低于峰值 1.5% 算静音（实测阈值）

        // 2) 收集所有「有声音」的区间
        val voiceStart = ArrayList<Int>()
        val voiceEnd = ArrayList<Int>()
        var startFrame = -1
        for (f in 0 until frames) {
            val voiced = rms[f] >= threshold
            if (voiced && startFrame < 0) {
                startFrame = f
            } else if (!voiced && startFrame >= 0) {
                voiceStart.add(padStartMs + startFrame * 10)
                voiceEnd.add(padStartMs + f * 10)
                startFrame = -1
            }
        }
        if (startFrame >= 0) {                     // 结尾还在响（没有收尾静音）
            voiceStart.add(padStartMs + startFrame * 10)
            voiceEnd.add(totalMs)
        }
        if (voiceEnd.size < 2) return IntArray(0)  // 一个停顿都没找到，测不准

        // 3) 记下"累计有声时长 → 该段结束时刻"，供下面吸附用
        val cumVoice = IntArray(voiceEnd.size)
        var c = 0
        for (i in voiceEnd.indices) {
            c += voiceEnd[i] - voiceStart[i]
            cumVoice[i] = c
        }
        val totalVoice = cumVoice.last()
        if (totalVoice <= 0) return IntArray(0)

        // 4) 按字数比例算出每句"应该读掉多少有声时长"，再吸附到最接近的真实停顿上
        val totalChars = counts.sum().coerceAtLeast(1)
        val out = IntArray(counts.size)
        var accChars = 0
        var prevEnd = 0
        for (i in counts.indices) {
            accChars += counts[i]
            if (i == counts.size - 1) {            // 最后一句一直延伸到整段结束
                out[i] = totalMs
                continue
            }
            val target = totalVoice.toDouble() * accChars / totalChars
            var bestIdx = -1
            var bestDiff = Double.MAX_VALUE
            for (k in cumVoice.indices) {
                if (voiceEnd[k] <= prevEnd) continue     // 边界只能往前走
                val diff = kotlin.math.abs(cumVoice[k] - target)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestIdx = k
                }
            }
            out[i] = if (bestIdx >= 0) voiceEnd[bestIdx] else prevEnd
            prevEnd = out[i]
        }
        return out
    }

    // ────────────────────── 睡眠定时（S4-5）──────────────────────
    //
    // 用户听着睡着之后，App 要自己把声音收掉。两种收尾时机：
    //   · 按分钟   —— 到点（真实时间）就收，中途暂停也照样走（用户选定的行为）
    //   · 本章后停 —— 念完当前这一章再收，章节标题是独立的一段，所以"播到下一章的标题"
    //                 就等于"上一章念完了"
    //
    // 收尾动作统一定为「淡出 → 暂停」，**不清队列**：
    //   音量在 4 秒内渐弱到静音，然后再暂停。队列不清，所以早上点「继续」
    //   是真的秒接上（不用重新合成）；断点也顺手记牢，App 重开也能接着听。
    //
    // ⚠️ 为什么不用 stopReading() 彻底停掉？
    //   stopReading 会清空播放队列，而队列本身就是"这次听到哪儿"的断点。
    //   清掉之后想接着听就得重新合成十几秒 —— 睡醒后第一件事是等合成，体验很差。

    /** 界面点了那排睡眠按钮 → 由服务统一落地并接管计时 */
    private fun setSleep(mode: Int, minutes: Int, target: Int) {
        val prefs = getSharedPreferences(PREF_SETTING, MODE_PRIVATE)
        sleepJob?.cancel()
        sleepJob = null
        when (mode) {
            SLEEP_TIME -> {
                val endAt = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
                saveSleepMode(prefs, SLEEP_TIME)
                saveSleepUntil(prefs, endAt)
                saveSleepMinutes(prefs, minutes)
                sleepJob = scope.launch { sleepCountdown(endAt) }
                Log.i(TAG, "睡眠定时：${minutes} 分钟后停（到点时刻 $endAt）")
            }
            SLEEP_CHAPTER -> {
                saveSleepMode(prefs, SLEEP_CHAPTER)
                saveSleepTarget(prefs, target)
                // 章节档不需要计时协程 —— 念到下一章的标题段时由播放器事件触发
                Log.i(TAG, "睡眠定时：念完本章后停（播到第 ${target + 1} 句处收尾）")
            }
            else -> {
                clearSleepPrefs(prefs)
                Log.i(TAG, "睡眠定时：已关闭")
            }
        }
    }

    /**
     * 「按分钟」档的倒计时。
     *
     * ⚠️ 算的是**墙钟时间**（用结束时刻减去当前时刻），不是"累加播放了多少秒"。
     *    好处：中途接电话、手动暂停都不影响，20 分钟就是 20 分钟后停；
     *    服务被系统回收后重建也能算准（存的是绝对时刻，不是剩余秒数）。
     */
    private suspend fun sleepCountdown(endAt: Long) {
        while (true) {
            if (System.currentTimeMillis() >= endAt) {
                fadeOutAndPause("定时到点")
                return
            }
            delay(500)
        }
    }

    /** 服务重建时把还生效的「按分钟」定时接回来（已经过期的就顺手清掉） */
    private fun rearmSleepFromPrefs() {
        val prefs = getSharedPreferences(PREF_SETTING, MODE_PRIVATE)
        if (loadSleepMode(prefs) != SLEEP_TIME) return
        val endAt = loadSleepUntil(prefs)
        if (endAt <= System.currentTimeMillis()) {
            clearSleepPrefs(prefs)
            return
        }
        sleepJob = scope.launch { sleepCountdown(endAt) }
        Log.i(TAG, "睡眠定时：服务重建后已接回，剩余 ${(endAt - System.currentTimeMillis()) / 1000} 秒")
    }

    /**
     * 到点收尾：4 秒淡出 → 暂停 → 记断点。
     *
     * 音量必须在**主线程**改（ExoPlayer 的规矩：所有调用都要在创建它的那个线程上），
     * 所以这里用 scope（主线程调度器）起协程，delay 也不会卡住界面。
     */
    private fun fadeOutAndPause(reason: String) {
        sleepJob?.cancel()
        sleepJob = null
        clearSleepPrefs(getSharedPreferences(PREF_SETTING, MODE_PRIVATE))
        scope.launch {
            val p = player ?: return@launch
            val steps = 24
            val stepMs = (SLEEP_FADE_MS / steps).coerceAtLeast(1L)
            for (i in steps downTo 0) {
                runCatching { p.volume = i / steps.toFloat() }
                delay(stepMs)
            }
            // ⚠️ 先把断点记牢：用户这回听到哪一句，下次要能原处接上。
            //    正常情况换段时已经存过了，这里再存一次是因为定时可能在**段中间**触发，
            //    段内又往前听了好几句，得按当前这一句更新。
            val info = parseMediaId(p.currentMediaItem?.mediaId)
            if (info != null && sentences.isNotEmpty()) {
                saveProgress(currentSentenceIndex(p, info))
            }
            runCatching { p.pause() }
            // 音量复位，否则下次点「继续」是静音的（会让人以为坏了）
            runCatching { p.volume = 1f }
            Log.i(TAG, "睡眠定时到点（$reason）：已淡出并暂停，队列与断点都保留")
        }
    }

    /**
     * 彻底停止：取消合成、清空播放队列、撤掉通知。
     *
     * ⚠️ 界面上**已经没有这个按钮了**（用户反馈用不上）。现在只有一种情况会走到这里：
     *    用户把 App 从后台任务列表里划掉（onTaskRemoved）。
     *
     * ⚠️ 这里**刻意不调用 stopSelf()**。原因（踩过）：
     *   一旦服务被销毁，MediaSession 会跟着释放，主界面手里的 MediaController
     *   就**永久失联**了 —— 之后界面上所有按钮、高亮、状态全都读不到东西。
     *   表现就是"界面点停止没用、按钮乱跳、播放控制条只剩两个按钮"。
     *   所以这里只：取消合成任务 → 清空播放列表 → 撤掉通知。
     *   服务本身留在后台待命（占用极小），控制器连接就一直是通的。
     *
     * ⚠️ 注意它和「暂停」的区别：暂停**不清队列**，所以还能原处接着听；
     *    这里把队列清了，下次只能按"断点续听"重新起一轮（要重新合成）。
     */
    private fun stopReading() {
        stopRequested = true
        resumePending = false
        pendingSeekSentence = -1
        job?.cancel()
        job = null
        player?.let { p ->
            runCatching {
                p.pause()
                p.stop()
                p.clearMediaItems()
            }
        }
        // 撤掉前台通知（服务退出前台，但进程还在，控制器不会断）
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        Log.i(TAG, "已停止朗读")
    }

    /**
     * 换书：界面换了一本书 → 后台把这一轮的合成循环**作废**。
     *
     * ⚠️ 为什么必须做（v0.3.3 踩到的大 bug，真机实测复现）：
     *   以前界面「载入另一本书」只换它自己的数据，后台毫不知情，
     *   于是 currentUri / sentences / groups / 合成队列全都还停在上一本：
     *     · 界面显示新书，声音还在放旧书
     *     · 点「暂停」再「继续」又是旧书（「继续」直接 play() 旧队列）
     *     · 旧书的进度被写进新书的记录里，两本书断点互相污染
     *
     * ⚠️ 判断"是不是换了书"必须用 [readingUri]（这一轮**开始时**的书），
     *    不能用 [currentUri]：doReading 一进来就把 currentUri 改成新书了，
     *    等合成循环真正跑起来时两者已经一样，判断会永远为"没换书"，
     *    协程就停不下来（这是第一版修法踩的坑）。
     *
     * 只作废合成、**保留队列与断点**：用户切回上一本书还能原处接着听。
     * 已经作废的那一轮不会自己复活 —— [doReading] 每轮开头都会检查这个标记。
     */
    private fun switchBook(newUri: String?) {
        val old = readingUri
        Log.i(TAG, "界面换书：$old → $newUri")
        if (newUri != null && old != null && newUri != old) {
            // 这一轮念的不是新书 → 作废（stopRequested 只由下一轮 startReading 复位）
            stopRequested = true
            pendingSeekSentence = -1
            // ⚠️ 必须**立刻静音**（v0.3.3 自检时发现，Leo 也反馈过）：
            //   换书只作废"还在合成"的部分，**已经合成好、正在播的那几段不受影响** ——
            //   于��界面已经换成新书了，声音却还在放旧书（按钮也还显示着「暂停」）。
            //   这里主动暂停：换书 = 不再听这一本。
            //   ⚠️ 用 pause 而不是 stop（stop 会清队列、断点也难保）：
            //   队列是"听到哪儿"的凭据，保留着，切回上一本书还能原处接着听。
            runCatching {
                player?.let { p ->
                    val info = parseMediaId(p.currentMediaItem?.mediaId)
                    if (info != null && sentences.isNotEmpty()) {
                        saveProgress(currentSentenceIndex(p, info))
                    }
                    p.pause()
                }
            }
        }
        // currentUri 保持不变：队列里还是上一本的内容，它就是"现在在放什么"的凭据。
        // 彻底清掉交给下一轮 startReading（它会 clearMediaItems + 重设 currentUri）。
    }

    /** 用户把 App 从后台任务列表划掉 → 停止朗读 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopReading()
        super.onTaskRemoved(rootIntent)
    }

    private fun saveProgress(sentenceIndex: Int) {
        val uri = currentUri ?: return
        getSharedPreferences(PREF_PROGRESS, MODE_PRIVATE)
            .edit().putInt(uri, sentenceIndex).apply()
        Log.i(TAG, "进度已保存：第 ${sentenceIndex + 1} 句")
    }

    override fun onDestroy() {
        job?.cancel()
        player?.release()
        session?.release()
        kokoro?.release()
        scope.cancel()
        super.onDestroy()
    }
}
