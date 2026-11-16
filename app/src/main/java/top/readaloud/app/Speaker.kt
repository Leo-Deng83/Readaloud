package top.readaloud.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 朗读器：把文字交给手机系统自带的语音引擎去念。
 *
 * 这是 PRD 里的 L1 兜底层（系统 TTS）。
 * 注意：系统 TTS 用的是手机厂商预置的语音引擎，好不好听由厂商决定；
 * 我们真正想要的离线高音质朗读，会在 S2 用自带的 Kokoro 引擎实现。
 *
 * @param onSentenceChanged 每开始念一句，就通知一次（用来在屏幕上高亮）
 * @param onStateChanged    朗读状态变化（准备中 / 可用 / 出错）
 * @param onPlayingChanged  开始朗读 / 停止 / 念完时通知，界面用它决定按钮亮不亮
 */
class Speaker(
    context: Context,
    private val onSentenceChanged: (Int) -> Unit,
    private val onStateChanged: (String) -> Unit,
    private val onPlayingChanged: (Boolean) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var sentences: List<String> = emptyList()
    private var index = 0
    private var playing = false

    /**
     * 当前这一次朗读的唯一编号。
     *
     * ⚠️ 为什么需要它（踩过）？暂停或跳句时会调 tts.stop()，系统**事后**还会补一个
     *    "念完了"的回调回来。要是不加判断，这个迟到的回调会被当成"这句念完了"，
     *    直接把位置往前推一句 —— 表现就是"点「上一句」，它却往前跳"。
     *    所以每次开口都带一个唯一编号，只有编号对得上才认。
     */
    private var currentUtterance: String? = null
    private var seq = 0

    /**
     * 当前语速（S4-3）。
     *
     * ⚠️ 必须在这里存一份：TextToSpeech 是**异步**初始化的，
     *    界面在"引擎还没就绪"时调 setSpeechRate 是无效的（会被丢掉）。
     *    所以先记下来，等 onInit 回调里 ready 之后再真正设进去。
     */
    private var speechRate = 1.0f

    init {
        onStateChanged("正在启动语音引擎…")
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 设置成中文。返回值可能是「缺语音数据」或「不支持」，要检查一下
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                if (ready) {
                    tts?.setSpeechRate(speechRate)
                    onStateChanged("语音引擎就绪 ✅（系统 TTS）")
                } else {
                    onStateChanged("⚠️ 手机上没有可用的中文语音，请到系统设置里安装")
                }
            } else {
                onStateChanged("⚠️ 语音引擎启动失败")
            }
            // 系统 TTS 念完一句会回调这里
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val t = utteranceId ?: return
                    mainHandler.post {
                        if (t == currentUtterance) onSentenceChanged(index)
                    }
                }

                override fun onDone(utteranceId: String?) = handleFinish(utteranceId)
                override fun onError(utteranceId: String?) = handleFinish(utteranceId)
                @Deprecated("旧接口，保留空实现即可")
                override fun onError(utteranceId: String?, errorCode: Int) = handleFinish(utteranceId)
            })
        }
    }

    /** 从第 index 句开始，一句一句往下念 */
    fun start(all: List<String>, fromIndex: Int) {
        sentences = all
        index = fromIndex
        playing = true
        mainHandler.post { onPlayingChanged(true) }
        speakCurrent()
    }

    private fun speakCurrent() {
        if (!playing || !ready || index >= sentences.size) {
            if (index >= sentences.size) {
                playing = false
                mainHandler.post { onPlayingChanged(false) }
            }
            return
        }
        val token = "u${++seq}"
        currentUtterance = token
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, token)
        }
        // QUEUE_FLUSH = 把之前没念完的清掉，只念这一句
        tts?.speak(sentences[index], TextToSpeech.QUEUE_FLUSH, params, token)
    }

    /**
     * 收到"念完了 / 出错了"的回调。
     * 只有编号对得上（确实是当前这一句）才往下走，迟到的回调一律忽略（原因见 currentUtterance）。
     */
    private fun handleFinish(utteranceId: String?) {
        val t = utteranceId ?: return
        mainHandler.post {
            if (t != currentUtterance) return@post
            currentUtterance = null
            next()
        }
    }

    /**
     * 跳到第 index 句（界面上「上一句 / 下一句」和点正文某一句都用它）。
     *
     * 系统 TTS 没法"从半句中间接着念"，所以跳转就是掐掉当前这句、从目标句开头念起。
     * 不改变播放/暂停状态 —— 暂停中按「上一句」只是挪个位置，不会自己响起来。
     */
    fun seekTo(index: Int) {
        if (sentences.isEmpty()) return
        this.index = index.coerceIn(0, sentences.size - 1)
        mainHandler.post { onSentenceChanged(this.index) }
        if (playing) {
            currentUtterance = null          // 让旧那次的迟到回调失效
            tts?.stop()
            mainHandler.post { speakCurrent() }
        }
    }

    /** 一句念完了，接着念下一句 */
    private fun next() {
        if (!playing) return
        index++
        if (index >= sentences.size) {
            playing = false
            mainHandler.post {
                onPlayingChanged(false)
                onStateChanged("朗读结束")
            }
            return
        }
        mainHandler.post { speakCurrent() }
    }

    /**
     * 暂停（跟播放栏的暂停键一个意思）。
     *
     * 系统 TTS 没法"从半句中间接着念"，所以只能把当前这句掐掉，
     * 记住句号，下次 resume 时从**这一句的开头**重念。
     * 对听书来说完全够用（一句也就几秒钟）。
     */
    fun pause() {
        if (!playing) return
        playing = false
        tts?.stop()
        mainHandler.post { onPlayingChanged(false) }
    }

    /** 从暂停处接着念 */
    fun resume() {
        if (playing || sentences.isEmpty()) return
        playing = true
        mainHandler.post { onPlayingChanged(true) }
        mainHandler.post { speakCurrent() }
    }

    /** 手上有没有正在念的内容（界面用它决定「继续」还是「朗读」） */
    fun hasContent() = sentences.isNotEmpty()

    /**
     * 改语速（S4-3）。
     *
     * 系统 TTS 的 setSpeechRate 会**立刻**作用于后面要念的内容，
     * 所以正在念的这一句不会中途变速，下一句开始用新语速 ——
     * 和本地引擎那边（播放器变速、当前句立即生效）略有差别，但都符合直觉。
     */
    fun setSpeed(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
    }

    fun stop() {
        playing = false
        tts?.stop()
        mainHandler.post { onPlayingChanged(false) }
    }

    fun isPlaying() = playing

    /** 退出时记得释放，否则会占着系统资源 */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
