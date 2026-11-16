package top.readaloud.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地（离线）语音合成引擎 —— 本项目真正的核心。
 *
 * 和系统 TTS 的区别：
 *   系统 TTS 用的是手机厂商给的引擎，好不好听、能不能离线都由厂商说了算；
 *   这个引擎是**装在我们 App 里面**的（模型文件随 App 发布），
 *   不管什么手机声音都一样，而且**绝对不联网**。
 *
 * 底层：sherpa-onnx（Apache-2.0）+ Kokoro-82M 的 int8 量化版。
 */
class KokoroTts(private val context: Context) {

    companion object {
        private const val TAG = "KokoroTts"
        /** 模型文件在 app/src/main/assets/kokoro/ 下面 */
        private const val ASSET_DIR = "kokoro"
        /** 解压到手机上的目标目录名 */
        private const val LOCAL_DIR = "kokoro"
        /** 每次模型换版本就改这个数字，会自动重新解压 */
        private const val MODEL_VERSION = "1"
        /**
         * 音色编号对照（按模型里 voices 的排序）：
         *   0 = af_maple  美式英语女声 ← 用它会听起来像"外国人念中文"
         *   1 = af_sol    英语女声
         *   2 = bf_vale   英式英语女声
         *   3 = zf_001    **中文女声**（默认，就是 S0 试听认可的那个声音）
         *   4 = zf_002    中文女声 2
         *  59 = zm_010    中文男声
         * 一共 103 个，其余可在界面上试。
         */
        const val DEFAULT_SPEAKER = 3
    }

    private var tts: OfflineTts? = null

    /**
     * 模型词表里认识的汉字。
     *
     * ⚠️ 为什么要在运行时留着这份表（用户验收反馈换来的）：
     *   Kokoro 的中文词表（lexicon-zh.txt）里**没有「〇」**，而我们的归一化曾经
     *   把年份 2024 读成"二〇二四" —— 模型遇到词表外的字会在**朗读中间断开**，
     *   听感是"至20，24年12月"这种怪停顿（实测：多出 2.3 秒、两处 0.78 秒死寂）。
     *   这种问题以前只能靠人耳发现，代价是"改一版—装机—戴上耳机"一轮。
     *   现在合成前扫一遍：有词表外的汉字就在日志里点名，
     *   以后同类回归一跑日志就能看见，不用等人听出来。
     */
    private var knownChars: Set<Char>? = null

    /** 已经报过的字（避免每合成一段就刷一遍日志） */
    private val reportedUnknown = HashSet<Char>()

    fun isReady() = tts != null

    /**
     * 模型最终所在的目录：**App 私有内部目录**
     * /data/data/top.readaloud.app/files/kokoro
     *
     * ⚠️ 为什么必须是内部目录（踩过两次坑才搞明白）：
     *  1. 引擎底层是 C++ 代码。它读 sdcard 上的文件时需要存储权限，
     *     而我们为了「权限最小化 + 不联网」根本没申请存储权限，
     *     结果 C++ 打不开文件 → 直接 abort → 表现为**闪退**（连报错都没有）。
     *  2. 内部私有目录是 App 自己的地盘，C++ 也能随便读，**不需要任何权限**。
     *  3. 所以流程是：先用 Kotlin 把模型拷贝进内部目录，再把内部路径交给引擎。
     */
    private fun modelDir(): File = File(context.filesDir, LOCAL_DIR)

    /**
     * 外部目录：/sdcard/Android/data/top.readaloud.app/files/kokoro
     * 只有开发时才用 —— 因为可以用 adb push 直接往里传模型，方便调试。
     */
    private fun externalDir(): File? {
        val base = context.getExternalFilesDir(null) ?: return null
        return File(base, LOCAL_DIR)
    }

    /** 检查模型文件是否齐全（内部目录优先，其次看外部待导入目录） */
    fun checkModel(): String {
        val inner = modelDir()
        val need = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-zh.txt")
        val missing = need.filter { !File(inner, it).exists() }
        if (missing.isEmpty()) return "模型已就绪（内部目录）"
        val ext = externalDir()
        val inExt = ext != null && need.all { File(ext, it).exists() }
        return if (inExt) "发现待导入模型：${ext.absolutePath}"
        else "缺少模型文件：${missing.joinToString("、")}"
    }

    /** 把外部目录里的模型搬进内部目录（App 自己拷，所以有权限） */
    private fun importFromExternal(onProgress: (String) -> Unit) {
        val ext = externalDir() ?: return
        val inner = modelDir()
        if (!File(ext, "model.int8.onnx").exists()) return
        onProgress("正在导入模型到私有目录…")
        inner.mkdirs()
        copyDir(ext, inner)
        Log.i(TAG, "导入完成：${inner.absolutePath}")
    }

    private fun copyDir(from: File, to: File) {
        val kids = from.listFiles() ?: return
        to.mkdirs()
        for (k in kids) {
            val dst = File(to, k.name)
            if (k.isDirectory) {
                copyDir(k, dst)
            } else if (!dst.exists() || dst.length() != k.length()) {
                k.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            }
        }
    }

    /**
     * **Full 变体**首次运行时：把安装包里的模型释放到内部目录。
     *
     * ⚠️ 为什么必须有这一步（S7 才发现的坑）：
     *   模型有 200MB，放在 assets 里，而且 **C++ 引擎读不了 assets**，
     *   必须先由 Kotlin 拷到 filesDir（引擎唯一能读的地方）。
     *   之前 ASSET_DIR 这个常量一直声明着却没人调用 ——
     *   也就是 Full 变体"装完其实用不了"：模型在包里，却没人释放它。
     *
     * Lite 变体的包里没有 assets，这一步 list 返回空、什么都不做，
     * 于是自动落到 [importFromExternal] 那条路（用户自己放一份）。
     */
    private fun importFromAssets(onProgress: (String) -> Unit) {
        val dir = modelDir()
        if (File(dir, "model.int8.onnx").exists()) return
        onProgress("正在释放内置模型（约 200MB，只需一次）…")
        val t0 = System.currentTimeMillis()
        // ⚠️ 这行日志是排查出来的：v0.3.3 首次装 release 时，包里明明有模型
        //    （unzip 能数出 370 个 kokoro 文件），真机却报"未找到模型"，
        //    而所有外层日志都看不出 assets 到底读到了什么。释放前后各打一次。
        Log.i(
            TAG,
            "准备释放内置模型：assets/$ASSET_DIR 顶层内容=" +
                (runCatching { context.assets.list(ASSET_DIR) }.getOrNull()
                    ?.joinToString(",") { it.take(24) } ?: "<读不到>")
        )
        copyAssets(ASSET_DIR, dir)
        Log.i(
            TAG,
            "内置模型释放：耗时 ${(System.currentTimeMillis() - t0) / 1000} 秒，" +
                "目标目录内容=${dir.list()?.size ?: -1} 项，" +
                "model.int8.onnx 存在=${File(dir, "model.int8.onnx").exists()}"
        )
    }

    /**
     * 递归拷贝 assets 里的一个目录。
     *
     * ⚠️⚠️ 这里踩过一个极其隐蔽的坑（v0.3.3 首次装 release 才暴露）：
     *   `context.assets.list()` 对**空子目录**返回的是**空数组**，而不是 null。
     *   老代码写的是「空数组 = 这里其实是个文件」，于是去 open
     *   `"kokoro/dict/README.md/README.md"` —— 路径被拼了两遍，必然失败。
     *   后果是**整个 dict 目录（以及它下面所有词表）一个文件都没释放出来**，
     *   而外层只报"未找到模型"，完全看不出是这个原因。
     *   （之前没暴露是因为手机上早就有一份从电脑推过去的模型，压根没走释放流程。）
     *
     * 正确做法：先明确判断"这是不是一个目录"，再决定拷文件还是递归；
     * 拿不准就两种都试一次（文件打不开就当目录递归），并把失败原因记进日志。
     */
    private fun copyAssets(path: String, to: File) {
        val children = runCatching { context.assets.list(path) }.getOrNull()
        if (children == null || children.isEmpty()) {
            // 可能是"一个文件"，也可能是"一个空目录"。先按文件试。
            val asFile = runCatching {
                context.assets.open(path).use { i ->
                    to.parentFile?.mkdirs()
                    to.outputStream().use { o -> i.copyTo(o) }
                }
            }
            if (asFile.isSuccess) return
            // 当文件打不开 → 确实是空目录，建出来就行（里面的内容本来也没有）
            to.mkdirs()
            if (asFile.exceptionOrNull() !is java.io.FileNotFoundException) {
                Log.w(TAG, "释放失败：$path（${asFile.exceptionOrNull()?.message}）")
            }
            return
        }
        to.mkdirs()
        for (name in children) copyAssets("$path/$name", File(to, name))
    }

    /**
     * 加载模型。很慢（要解压 + 读取一百多 MB），必须在后台线程调用。
     * @param onProgress 回调进度文字（注意：是在后台线程回调的，调用方要自己切回主线程）
     */
    suspend fun load(onProgress: (String) -> Unit = {}): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = modelDir()
                if (!File(dir, "model.int8.onnx").exists()) {
                    // 内部还没有 → 先试安装包内置的（Full 变体），
                    // 再试用户自己放的那份（Lite 变体 / 开发期从存储导入）
                    importFromAssets(onProgress)
                    if (!File(dir, "model.int8.onnx").exists()) {
                        importFromExternal(onProgress)
                    }
                }
                if (!File(dir, "model.int8.onnx").exists()) {
                    throw IllegalStateException(
                        "未找到模型。这个安装包是 Lite 版（不含模型），" +
                            "请把模型文件夹放到 ${externalDir()?.absolutePath} 后重新打开 App"
                    )
                }

                onProgress("正在加载模型…（首次约需十几秒）")
                val kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, "model.int8.onnx").absolutePath,
                    voices = File(dir, "voices.bin").absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    // ⚠️ espeak-ng 数据目录（**多语言前端，Kokoro 必需**）。
                    //    ⚠️ 目录不存在就传空字符串，**绝不能传一个不存在的路径**：
                    //       引擎初始化时会去打开这个目录里的 phontab/phonindex，
                    //       找不到就失败（实测：传空字符串会得到
                    //       "Invalid OfflineTtsConfig: failed to create native OfflineTts"）。
                    //
                    //    ⚠️⚠️ **它不能被移除**（2026-10-06 实测）：
                    //       曾以为"中文走 lexicon + jieba，不需要 espeak"，
                    //       于是从安装包里删掉 espeak-ng-data（省 18MB，还能规避
                    //       espeak-ng 是 GPL-3.0 的许可证问题）——
                    //       结果引擎**直接创建失败**，中文也念不出来。
                    //       真相是：Kokoro 用 espeak-ng 做**多语言前端**，
                    //       `lang = "cmn"` 只是它的一个 voice；lexicon-zh.txt 是补充，不是替代。
                    //       所以这个 GPL-3.0 的数据只能保留，许可证问题按"如实披露"处理
                    //       （见 docs/第三方组件与许可证.md 第四节）。
                    dataDir = File(dir, "espeak-ng-data")
                        .takeIf { it.isDirectory }?.absolutePath ?: "",
                    lexicon = File(dir, "lexicon-zh.txt").absolutePath,
                    // ⚠️ 语言代码必须是 "cmn"（espeak-ng 里普通话的代号）。
                    //    写成 "zh" 会报 Failed to set eSpeak-ng voice，
                    //    因为发音库里没有叫 "zh" 的语音。
                    lang = "cmn",
                    dictDir = File(dir, "dict").absolutePath,
                    lengthScale = 1.0f
                )
                val model = OfflineTtsModelConfig(
                    kokoro = kokoro,
                    numThreads = 4,      // 这台手机是 8 核，多给线程能明显加快合成
                    debug = false,
                    provider = "cpu"     // 手机没有独立显卡，只能 CPU
                )
                val config = OfflineTtsConfig(
                    model = model,
                    ruleFsts = "",
                    ruleFars = "",
                    maxNumSentences = 1,
                    // ⚠️ 这里只是"兜底值"，真正生效的是每次合成时传的 GenerationConfig
                    //    （见 synthesize 的 silenceScale 参数，界面上「停顿」档位就是调它）。
                    //    注意引擎自己的默认值是 0.2，而我们早期一路写成 1.0 ——
                    //    相当于把模型自带的句间停顿放大了好几倍，这就是"章节名后停顿太久"的主因。
                    silenceScale = 0.8f
                )
                // ⚠️ 关键：模型路径是**绝对路径**时，assetManager 必须传 null！
                //    否则引擎会把它当成"安装包里的相对路径"去找，找不到直接 abort（闪退）。
                //    （引擎自己在日志里提示的：please set assetManager to null）
                tts = OfflineTts(null, config)
                loadKnownChars(dir)
                Log.i(TAG, "模型加载完成：采样率=${tts?.sampleRate()}，音色数=${tts?.numSpeakers()}")
                Unit
            }
        }

    /** 把词表里出现的字收进内存（68k 行、两万来个字，几百 KB，换来的是一整类 bug 的可见性） */
    private fun loadKnownChars(dir: File) {
        val f = File(dir, "lexicon-zh.txt")
        if (!f.exists()) return
        val set = HashSet<Char>(24000)
        runCatching {
            f.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    // 词表每行长这样：「一 ㄧ 1」—— 第一个字段是字（也可能是词组）
                    val head = line.substringBefore(' ').substringBefore('\t').trim()
                    for (c in head) if (LineBreaks.isCjk(c)) set.add(c)
                }
            }
        }
        knownChars = set
        Log.i(TAG, "中文词表：认识 ${set.size} 个汉字（用来提前发现模型不认识的字）")
    }

    /**
     * 找出文本里"模型词表不认识"的汉字。
     * 只查汉字区间 —— 标点、英文、数字走的是另一条通路（espeak-ng），不算不认识。
     */
    private fun unknownCjkChars(text: String): List<Char> {
        val known = knownChars ?: return emptyList()
        if (known.isEmpty()) return emptyList()
        val bad = ArrayList<Char>()
        for (c in text) {
            if (!LineBreaks.isCjk(c)) continue
            if (c in known) continue
            if (reportedUnknown.add(c)) bad.add(c)
        }
        return bad
    }

    /**
     * 合成一段文字。
     *
     * @param speaker     音色编号
     * @param speed       语速。⚠️ 我们**不用它**（固定 1.0）：听书时调语速改成在播放端
     *                    变速（ExoPlayer.setPlaybackSpeed），好处是调了立刻生效、
     *                    已经合成好的段落也能跟着变，不用重新合成一遍。
     * @param silenceScale 句间停顿的缩放系数 —— **越小停顿越短**。
     *                    这是模型内部"句子之间留多长气口"的旋钮，
     *                    界面上「停顿：紧凑/标准/舒缓」三档就是它 + 段首尾补的静音。
     */
    suspend fun synthesize(
        text: String,
        speaker: Int = DEFAULT_SPEAKER,
        speed: Float = 1.0f,
        silenceScale: Float = 0.8f
    ): GeneratedAudio? = withContext(Dispatchers.IO) {
        val t = tts ?: return@withContext null
        // 合成前先体检：有模型不认识的汉字就点名（否则只会表现为"读一半断一下"，很难查）
        val bad = unknownCjkChars(text)
        if (bad.isNotEmpty()) {
            Log.w(
                TAG,
                "⚠️ 朗读文本里有模型不认识的 ${bad.size} 个字「${bad.joinToString("")}」" +
                    " —— 这类字会让朗读在中间断开，应当在 TextNormalizer / 读音词典里换掉"
            )
        }
        runCatching {
            // 用 generateWithConfig（而不是简版的 generate），才能每次单独指定停顿长度 ——
            // 这样界面上改停顿，从下一段开始就生效，不需要重建引擎。
            t.generateWithConfig(
                text,
                GenerationConfig(silenceScale = silenceScale, speed = speed, sid = speaker)
            )
        }.getOrNull()
    }

    fun release() {
        tts?.release()
        tts = null
    }
}

/**
 * 连续 PCM 播放器。
 *
 * ⚠️ 为什么改掉原来的写法：
 *   原来每句话都新建一个 AudioTrack 播完就销毁，
 *   句与句之间会出现"咔哒"爆音（上一句还没播完就被掐断）。
 *   现在改成：**整段朗读只用一个 AudioTrack**，
 *   合成出一句就往里追加写入，声音是连续的一条流，不会有接缝噪音。
 *
 * 这是 S2/S3 的过渡方案，后面会换成 ExoPlayer（才能支持后台播放、耳机线控）。
 */
class PcmPlayer(private val sampleRate: Int) {

    private var track: AudioTrack? = null
    private var writtenFrames = 0

    /** 句与句之间插入的静音长度（秒）—— 也顺便让朗读有自然停顿 */
    private val gapSeconds = 0.15f
    /** 每句首尾的淡入淡出长度（秒）—— 消除"咔哒"爆音的关键 */
    private val fadeSeconds = 0.03f

    fun start() {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        // 缓冲开大到 3 秒：合成跟不上时，缓冲区越大越不容易"断流"，
        // 而断流（underrun）正是残余咔哒声的一个来源
        val buffer = maxOf(minBuffer, sampleRate * 4 * 3)
        track = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(buffer)
            .build()
            .apply { play() }
        writtenFrames = 0
    }

    /**
     * 追加一句音频。
     * 做了两件消除爆音的事：
     *   1. 首尾各 20 毫秒淡入淡出 —— 声音从 0 平滑升起、平滑落下，
     *      不会有突然的电平跳变（那种跳变就是"咔哒"声）
     *   2. 句尾补 150 毫秒静音 —— 让句子之间有自然停顿
     */
    fun feed(audio: GeneratedAudio) {
        val t = track ?: return
        val s = audio.samples
        val n = s.size
        val fade = (sampleRate * fadeSeconds).toInt().coerceAtMost(n / 2)
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            s[i] *= g                 // 开头淡入
            s[n - 1 - i] *= g         // 结尾淡出
        }
        t.write(s, 0, n, AudioTrack.WRITE_BLOCKING)
        writtenFrames += n

        val gap = FloatArray((sampleRate * gapSeconds).toInt())
        t.write(gap, 0, gap.size, AudioTrack.WRITE_BLOCKING)
        writtenFrames += gap.size
    }

    /** 插入一段额外的静音（章节之间用，让停顿更明显） */
    fun pause(seconds: Float) {
        val t = track ?: return
        val gap = FloatArray((sampleRate * seconds).toInt())
        t.write(gap, 0, gap.size, AudioTrack.WRITE_BLOCKING)
        writtenFrames += gap.size
    }

    /** 等真正播完（看播放头走到哪了），而不是靠猜时间 */
    fun drain() {
        val t = track ?: return
        while (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
            if (t.playbackHeadPosition >= writtenFrames) break
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
    }

    fun stop() {
        track?.let {
            try {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                it.flush()      // 丢弃还没播的残留数据，避免下次串音
                it.stop()
            } catch (_: Exception) {}
            it.release()
        }
        track = null
        writtenFrames = 0
    }
}
