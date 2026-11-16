package top.readaloud.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ReadaloudUI"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 调试入口：可以用 adb 直接指定一本书打开，方便做自动化验证，例如
        //   adb shell am start -n top.readaloud.app/.MainActivity \
        //       --es openfile "file:///sdcard/Android/data/top.readaloud.app/files/test.txt"
        // 平时从桌面图标进是拿不到这个参数的，不影响正常使用。
        val autoOpen = intent?.getStringExtra("openfile")
        setContent { ReadaloudApp(autoOpen) }
    }
}

@Composable
fun ReadaloudApp(autoOpen: String? = null) {
    val context = LocalContext.current

    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var sentences by remember { mutableStateOf(emptyList<Sentence>()) }
    var chapters by remember { mutableStateOf(emptyList<Chapter>()) }
    var groups by remember { mutableStateOf(emptyList<TtsGroup>()) }
    var currentIndex by remember { mutableIntStateOf(-1) }

    /**
     * "用户刚点过、但音频还没念到"的那一句（-1 = 没有）。
     *
     * ⚠️ 为什么要有它：
     *   点一句之后，后台要重新解析、重新合成，头几十秒是没有声音的
     *   （本地引擎首次还要加载模型）。这段时间如果让高亮照旧去跟播放器，
     *   它会跟着"还在响的旧内容"跑，甚至完全没有高亮 ——
     *   用户回头就不知道自己刚才点的是哪一句了（Leo 反馈的就是这个）。
     *   所以点下去立刻把高亮钉在他点的地方，等**真的念到那一句**再交还给常规跟随。
     */
    var pendingHighlight by remember { mutableIntStateOf(-1) }
    var engine by remember { mutableStateOf("kokoro") }
    var speaker by remember { mutableIntStateOf(KokoroTts.DEFAULT_SPEAKER) }
    var controller by remember { mutableStateOf<MediaController?>(null) }

    // 提示文字（"载入完成…"）和实时状态（"朗读中…"）分开存，
    // 免得两个来源互相覆盖，看起来一闪一闪的
    var hint by remember { mutableStateOf("请先选择一个 TXT 文件") }
    var live by remember { mutableStateOf("") }

    // ⚠️ 界面的「正在朗读」只有一个来源：后台播放服务本身（通过 MediaController 读回来）。
    //    以前是自己维护一个 playing 变量，结果通知栏改了播放状态、界面却不知道，
    //    两边各说各话 —— 按钮乱跳、"停止"按不动就是这么来的。
    //
    //    现在拆成几个含义不同的事实，按钮才做得对：
    //      queueActive   = 队列里还有内容（这本书还在，随时能接着听）
    //      wantPlay      = 播放器的"播放意图"（等价于播放栏按的是播放还是暂停）
    //      playbackEnded = 播放器已经播到头了
    //      curGroup      = 当前播的是第几段（取 mediaId 里的段号，跟队列下标无关）
    var queueActive by remember { mutableStateOf(false) }

    /**
     * **当前播放队列是为哪本书起的**（界面自己记账，null = 还没起过）。
     *
     * ⚠️ 为什么必须有它（v0.3.3 踩到的大 bug）：
     *   后台的队列不会因为界面换书而自动变 —— 换完书它还装着上一本的音频。
     *   而「继续 / 上一句 / 下一句」都是直接 play() 这个队列，
     *   于是用户听到的永远是上一本。判断"队列是不是当前这本书的"就成了刚需。
     *
     * 为什么不用"队列长度 vs 本书段数"去比：合成是**一段段攒**的，
     * 刚开始朗读时队列长度必然小于总段数，会把正常状态误判成"不是这本书的"。
     * 界面是唯一会发起朗读的一方（书名 uri 它最清楚），所以自己记账最准。
     */
    var queueUri by remember { mutableStateOf<String?>(null) }

    /** 队列属于当前这本书？（空队列不算 —— 空队列本来就该重新起一轮） */
    fun queueMatchesBook(): Boolean =
        queueUri != null && fileUri != null && queueUri == fileUri.toString()
    var wantPlay by remember { mutableStateOf(false) }
    var playbackEnded by remember { mutableStateOf(false) }
    var curGroup by remember { mutableIntStateOf(-1) }
    var systemPlaying by remember { mutableStateOf(false) }

    // ⚠️ 「整本读完了」必须两个条件都满足：播放器播到头 **而且** 已经停在最后一段。
    //    因为两段之间等合成时，播放器也会短暂地"播到头" ——
    //    那时如果误判成"读完了"，用户一按继续就会从第一段重新来一遍。
    //    段号从 mediaId 里取，界面自己也知道总共有几段（同一套切分规则算出来的）。
    val finishedAll = playbackEnded && curGroup >= 0 &&
        groups.isNotEmpty() && curGroup >= groups.size - 1

    // ⚠️ 按钮显示「暂停」还是「继续」，看的是**播放意图**，不是"此刻有没有出声"。
    //    合成下一段的那几秒本来就没声音，但用户并没有按暂停，按钮就该一直是「暂停」——
    //    以前用 isPlaying 判断，按钮就会在两段之间不停闪（用户反馈的"按钮闪变"）。
    val playing = if (engine == "system") systemPlaying
    else queueActive && wantPlay && !finishedAll

    val progressPrefs = remember {
        context.getSharedPreferences(PlaybackService.PREF_PROGRESS, Context.MODE_PRIVATE)
    }
    val settingPrefs = remember {
        context.getSharedPreferences(PlaybackService.PREF_SETTING, Context.MODE_PRIVATE)
    }

    // —— S4-3：语速 / 句间停顿 ——
    //    两个设置的初值都从上次存的读回来，所以关掉 App 再打开还是老样子。
    //    语速：1.0 是原速，范围 0.6~2.0（再快就听不清、再慢会拖得像卡带）
    //    停顿：0=紧凑 1=标准 2=舒缓
    var speed by remember { mutableFloatStateOf(PlaybackService.loadSpeed(settingPrefs)) }
    // ── 正文排版（字体 / 字号 / 配色）───────────────────────
    //设置一改就往 Compose 里写，改完立刻生效（读的是下面 var，改 var 就重绘）
    var readFont by remember {
        mutableStateOf<ReadingStyle.SysFont>(ReadingStyle.loadFont(settingPrefs))
    }
    var readSizeSp by remember { mutableFloatStateOf(ReadingStyle.loadSize(settingPrefs)) }
    var readTheme by remember { mutableStateOf(ReadingStyle.loadTheme(settingPrefs)) }
    // 选中的用户字体（空 = 用系统字体）；导入/删除后要刷新列表
    var userFontFile by remember { mutableStateOf(ReadingStyle.loadUserFont(settingPrefs)) }
    var userFonts by remember { mutableStateOf(emptyList<String>()) }
    // 跟随系统档要跟着系统深浅色实时变，所以这行本身就是状态
    val systemDark = isSystemInDarkTheme()
    var pauseLevel by remember { mutableIntStateOf(PlaybackService.loadPauseLevel(settingPrefs)) }

    // —— S4-5：睡眠定时 ——
    //    ⚠️ 界面这里**只负责显示和发指令**，计时与收尾全在后台服务里
    //       （原因见 PlaybackService.setSleep 的注释：锁屏后界面靠不住）。
    //       所以这几个值每秒从设置里同步一次 —— 服务那边把定时清掉了，
    //       界面最多 1 秒后就会跟着变回「关」，不会显示成还在倒计时。
    var sleepMode by remember { mutableIntStateOf(PlaybackService.loadSleepMode(settingPrefs)) }
    var sleepUntil by remember { mutableStateOf(PlaybackService.loadSleepUntil(settingPrefs)) }
    var sleepMinutes by remember { mutableIntStateOf(PlaybackService.loadSleepMinutes(settingPrefs)) }
    var sleepLeft by remember { mutableStateOf("") }
    // 控制器重连时要补一次语速设置，而这个 effect 只在 controller 变化时跑，
    // 直接用 speed 会读到旧值 —— 所以用 rememberUpdatedState 拿最新的
    val speedNow = rememberUpdatedState(speed)

    // —— 书架数据（S4-4）+ 底部导航状态（S5-0）——
    //    tab 只表示"现在看哪一页"，跟播放没有半点关系：
    //    切页不打断朗读，所以调完设置切回来，声音一直是连续的。
    //    ⚠️ 从书架点开一本书会自动切到「正在听」（见 loadFile）。
    var tab by remember { mutableStateOf(AppTab.SHELF) }

    // ── 书签（Leo 提的需求）────────────────────────────────────
    //
    // ⚠️ 换书必须刷新：书签是**按书的 uri 存**的，
    //    不换就会看到上一本书的书签（那属于"界面显示与实际数据不一致"，
    //    和 v0.3.3 那个"换书还在播旧书"是同一类错误，务必别踩）。
    val bookmarkPrefs = remember { BookmarkStore.prefs(context) }
    var bookmarks by remember { mutableStateOf(emptyList<Bookmark>()) }
    // 书签按钮的可用性依赖"当前有没有高亮句"，所以要跟着它变
    var bookmarkOnCurrent by remember { mutableStateOf(false) }

    fun refreshBookmarks() {
        val u = fileUri?.toString()
        bookmarks = if (u == null) emptyList() else BookmarkStore.of(bookmarkPrefs, u)
        val idx = currentIndex
        bookmarkOnCurrent = u != null && idx >= 0 && BookmarkStore.has(bookmarkPrefs, u, idx)
    }
    var books by remember { mutableStateOf(emptyList<Book>()) }
    // 源文件已经不在的书（uri 集合）—— 书架上要明确标出来
    var missingFiles by remember { mutableStateOf(emptySet<String>()) }
    val shelfPrefs = remember { BookShelf.prefs(context) }

    // 进 App 先读一遍书架
    LaunchedEffect(Unit) { books = BookShelf.all(shelfPrefs) }

    // 查每本书的源文件还在不在（PRD FR-04 的"源文件缺失标识"）。
    // ⚠️ 这是真的去开一下文件流，属于 IO，必须切到 IO 线程 ——
    //    否则书多了会把界面卡住。
    LaunchedEffect(books) {
        if (books.isEmpty()) {
            missingFiles = emptySet()
            return@LaunchedEffect
        }
        missingFiles = withContext(Dispatchers.IO) {
            books.filterNot { BookShelf.exists(context, it.uri) }.map { it.uri }.toSet()
        }
    }

    val systemTts = remember(context) {
        Speaker(
            context,
            onSentenceChanged = { i -> currentIndex = i },
            onStateChanged = { s -> hint = s },
            onPlayingChanged = { b -> systemPlaying = b }
        )
    }
    DisposableEffect(Unit) { onDispose { systemTts.shutdown() } }

    // 载入是异步的（大文件解析要好几秒，不能占着主线程），
    // loading 用来挡住"解析还没完又点了一本"的重复操作
    var loading by remember { mutableStateOf(false) }
    // 载入失败的原因（PRD 要求"失败要给路"）。⚠️ 必须用弹窗而不是状态文字：
    // 解析失败时我们**不会切到「正在听」页**，而状态文字只在那一页显示 ——
    // 用户在书架上点了一本扫描版 PDF，会什么都看不到，以为软件没反应。
    var loadError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 正文实际用的字色与背景色（"跟随系统"档随系统深浅色自动切）
    val (readFg, readBg) = remember(readTheme, systemDark) {
        ReadingStyle.colors(readTheme, systemDark)
    }
    // ⚠️ 用户字体优先于系统字体；用户字体加载失败（文件被删/损坏）自动退回系统字体，
    //    不能让正文变成"没有字体"的状态。
    val readFontFamily = remember(readFont, userFontFile) {
        UserFonts.familyOf(context, userFontFile) ?: readFont.family
    }

    /**
     * 载入一本书：读文字 → 切句 → 识别章节 → 分组 → 读回上次的进度。
     *
     * 载入成功会自动把这本书记进书架（同一本重复导入只更新那一张卡片），
     * 然后切到阅读页。失败时界面停在原地 —— 比如书架上那本书的源文件
     * 已经被用户在文件管理器里删掉了，这时不该白跳一次页。
     *
     * ⚠️ 解析必须放在后台线程（协程 + IO），不能像以前那样在主线程直接读：
     *    PRD 要求 10MB 的 EPUB 在 4 秒内解析完，这个耗时放在主线程
     *    足够让界面卡死（ANR）。所以这里是"发出去就不管"的异步写法。
     *
     * @param onLoaded 解析成功后的回调（调试入口用它"打开即开念"）。
     *                  ⚠️ 这里不能直接调用下面的 startReading ——
     *                  Kotlin 的局部函数只能引用**已经声明过**的，
     *                  而 startReading 在本函数之后才声明，所以用回调把它传进来。
     */
    fun loadFile(uri: Uri, onLoaded: (() -> Unit)? = null) {
        if (loading) return
        loading = true
        hint = "正在解析这本书…"
        scope.launch {
            val ext = withContext(Dispatchers.IO) { BookShelf.fileExtension(context, uri) }

            // ⚠️ 必须走统一入口 BookReader —— 界面和播放服务要拿到**同一份**
            //    文本 / 切句 / 章节 / 分段。以前两边各读各的：服务端只认纯文本，
            //    读 .epub 就是拿 zip 二进制当文本解，念出来全是怪声（用户反馈过）。
            // ⚠️ 也要兜住读文件本身的异常：文件被删/被移动时解析会抛，
            //    不接住就是一次闪退（用户看到的是"点一下书架就没了"）。
            val content = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                runCatching {
                    BookReader.load(context, uri) { done, total ->
                        // 进度回调是在 IO 线程上发生的，改界面文字得回主线程
                        scope.launch { hint = "正在解析 PDF…已读 $done / $total 页" }
                    }
                }.getOrNull()
            }
            loading = false
            if (content == null) {
                loadError = when (ext) {
                    "epub" -> "这个 EPUB 读不出文字 —— 文件可能损坏，或者正文全是图片。"
                    "pdf" -> "这个 PDF 抽不出文字 —— 多半是扫描影印版（整页都是图片），" +
                        "暂时没法朗读。换一个带文字层（能选中文字）的版本试试。"
                    else -> "这本书读不出内容 —— 源文件可能已经被删除或移动了。"
                }
                return@launch
            }

            val list = content.sentences
            val chaps = content.chapters
            val gs = content.groups
            val chapterSource = when (content.tocSource) {
                "nav" -> "目录来自书内的 nav"
                "ncx" -> "目录来自书内的 NCX"
                "heading" -> "书里没带目录，按正文标题分章"
                "spine" -> "书里没带目录，按文件顺序分章"
                // PDF 三级降级：书签 → 目录页 → 版式认标题
                "bookmark" -> "目录来自 PDF 内嵌书签"
                "tocpage" -> "PDF 没书签，目录重建自目录页"
                "layout" -> "PDF 既没书签也没目录页，按标题字号重建"
                "none" -> "这本 PDF 没书签也没有目录页，暂时只能整本顺读"
                else -> ""
            }

            fileUri = uri
            sentences = list
            chapters = chaps
            groups = gs
            fileName = content.title
            // 换书了：上本书"钉住的高亮"作废（句号是上一本的，留着会乱指）
            pendingHighlight = -1

            // ⚠️⚠️ **必须通知后台"换书了"**（v0.3.3 踩到的大 bug，真机实测复现）：
            //   不通知的话，后台毫不知情 —— 它的 currentUri、句子表、段落表、
            //   合成队列全都还停在**上一本**。于是：
            //     · 界面显示新书，声音还在放旧书
            //     · 点「暂停」再「继续」又是旧书（「继续」直接 play() 旧队列）
            //     · 旧书的断点被写进新书的记录，两本书互相污染
            //   这条指令让后台作废当前那轮合成（队列与断点保留，切回去还能接着听）。
            PlaybackService.requestSwitchBook(context, uri.toString())
            refreshBookmarks()   // 换书 → 书签要换成新书的（见上面 bookmarkOnCurrent 的说明）

            // ⚠️ 系统 TTS 引擎同样要通知：它不知道"换书了"，会一直念上一本
            //   （和本地引擎当年同一个毛病，只是这条路用得少、容易被漏掉）
            if (engine == "system" && systemTts.hasContent()) systemTts.stop()

            // 「念完本章后停」是对**上一本**说的，换书就作废（否则新书刚开念就停）
            // ⚠️ 两档都要关，不只是章节档：按分钟的档是"墙钟到点"，换书后它照样会
            //   倒计时然后把（已经是新书的）界面收尾、淡出暂停 —— 一样是错的。
            if (queueUri != null && queueUri != uri.toString()) {
                val sp = settingPrefs
                if (PlaybackService.loadSleepMode(sp) != PlaybackService.SLEEP_OFF) {
                    PlaybackService.requestSleepOff(context)
                    sleepMode = PlaybackService.SLEEP_OFF
                    sleepUntil = 0L
                }
            }

            // 自动加入书架（书架按"最后打开时间"排序，所以刚打开的这本会排到最前面）
            BookShelf.upsert(
                shelfPrefs,
                Book(uri.toString(), content.title, list.size, gs.size, System.currentTimeMillis())
            )
            books = BookShelf.all(shelfPrefs)
            tab = AppTab.NOW

            // 断点续听：看看这本书以前读到哪了
            val last = PlaybackService.loadProgress(progressPrefs, uri.toString())
            if (last in list.indices) {
                currentIndex = last
                hint = "共 ${list.size} 句，上次读到第 ${last + 1} 句，点「继续」接着听"
            } else {
                currentIndex = -1
                hint = buildString {
                    append("共 ${list.size} 句")
                    if (chaps.isNotEmpty()) {
                        append("，识别到 ${chaps.size} 个章节")
                        if (chapterSource.isNotEmpty()) append("（$chapterSource）")
                    } else {
                        append("，没识别到章节")
                    }
                    append("。点任意一句可从那里开始听")
                }
            }

            // 调试入口（adb 带 openfile 启动）要求"打开就开念"
            onLoaded?.invoke()
        }
    }

    /** 从书架点开一本书：先把它在书架里"置顶"，再载入 */
    fun openBook(b: Book) {
        BookShelf.touch(shelfPrefs, b.uri)
        books = BookShelf.all(shelfPrefs)
        loadFile(Uri.parse(b.uri))
    }

    // —— 选文件 ——
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) loadFile(uri) }

    // —— 模型导入（Lite 版用）：系统目录选择器 ——
    //
    // ⚠️ 必须持久化 URI 授权：用户选完文件夹，系统只给**一次**临时的读权限，
    //    App 重启后就失效 → 下次要模型时又得重选一遍。
    //    takePersistableUriPermission 把它变成长期授权（用户重启手机也还在）。
    var modelStatus by remember { mutableStateOf(KokoroTts(context).checkModel()) }
    var modelImporting by remember { mutableStateOf(false) }
    var modelImportHint by remember { mutableStateOf("") }

    // —— 导入字体文件（ttf / otf）——
    //
    // ⚠️ 用 OpenDocument 而不是自己拼路径：Android 11 起分区存储，
    //    只有用户在**系统界面里亲自选过**的文件我们才有权读。
    // 导入后直接选中它 —— 用户刚点的就是想用这个字体。
    val pickFontFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        modelImporting = true
        modelImportHint = "正在导入字体…"
        scope.launch {
            val r = runCatching { UserFonts.import(context, uri) }
            modelImporting = false
            modelImportHint = r.fold(
                onSuccess = { name ->
                    userFontFile = name
                    ReadingStyle.saveUserFont(settingPrefs, name)
                    userFonts = UserFonts.list(context).map { it.name }
                    "已导入并启用：${ReadingStyle.userFontLabel(name)}"
                },
                onFailure = { "字体导入失败：${it.message ?: it.javaClass.simpleName}" }
            )
            Log.i(TAG, "字体导入：$modelImportHint")
        }
    }

    val pickModelDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 长期授权（拿不到也不致命：这一次导入照样能做完）
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        modelImporting = true
        modelImportHint = "正在准备…"
        scope.launch {
            // 进度回调在 IO 线程 → 必须切回主线程才能改界面（Compose 状态只能主线程改）
            val result = runCatching {
                ModelImporter.import(context, File(context.filesDir, "kokoro"), uri) {
                    modelImportHint = it
                }
            }
            modelImporting = false
            modelImportHint = result.fold(
                onSuccess = { "导入完成 ✅ $it" },
                onFailure = { "导入失败：${it.message ?: it.javaClass.simpleName}" }
            )
            modelStatus = KokoroTts(context).checkModel()
            Log.i(TAG, "模型导入结果：$modelImportHint")
        }
    }

    // —— 连上后台播放服务（Media3 的标准做法）——
    //    带断线重连：后台服务被系统回收后（低内存手机上很常见），
    //    界面手里的控制器会断开，这时自动重新连一次，
    //    否则所有按钮和高亮都会读不到状态、看起来像"坏了"。
    var connectAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(connectAttempt) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(c: MediaController) {
                    if (controller != null) {
                        controller = null
                        queueActive = false
                        // ⚠️ 控制器断了 = 服务那边（连同队列）已经没了。
                        //    这里必须把"队列归属"也清掉，否则界面会以为
                        //    队列还在，结果「继续」play() 一个空队列（按了没反应）。
                        queueUri = null
                        wantPlay = false
                        playbackEnded = false
                        curGroup = -1
                        live = ""
                        connectAttempt++
                    }
                }
            })
            .buildAsync()
        future.addListener(
            { runCatching { future.get() }.onSuccess { controller = it } },
            ContextCompat.getMainExecutor(context)
        )
    }

    // —— 通知权限（Android 13 起要用户点头）——
    // 不给的话后台播放的通知控制条出不来，前台服务还会被系统当成
    // "没显示通知"而回收，听起来就是"播一会儿自己停了"。
    val askNotify = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 同意不同意都继续，不影响朗读本身 */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // —— 盯着后台服务：队列还在不在、是不是正在出声 ——
    // 不管是界面按钮、还是通知栏按钮改动了播放状态，这里都会收到通知，
    // 所以界面永远跟播放器一致，不会再出现"两边状态打架"。
    /** 把播放器的真实状态搬进界面（按钮、状态文字、是否读完都靠它） */
    fun syncFromPlayer(p: Player) {
        queueActive = p.mediaItemCount > 0
        wantPlay = p.playWhenReady
        playbackEnded = p.playbackState == Player.STATE_ENDED
        // mediaId 里带着真实段号；拿到的是静音占位（不是真实段落）就记 -1
        curGroup = PlaybackService.parseMediaId(p.currentMediaItem?.mediaId)?.groupIndex ?: -1
    }

    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                syncFromPlayer(player)
            }
        }
        c.addListener(listener)
        syncFromPlayer(c)
        // 控制器重连后补一次语速：后台服务若是被系统回收后重建的，
        // 播放器速度会退回 1.0 倍，而界面上显示的还是用户选的值，得给它对齐
        runCatching { c.setPlaybackSpeed(speedNow.value) }
        try {
            awaitCancellation()
        } finally {
            c.removeListener(listener)
        }
    }

    // —— 高亮 + 状态文字 ——
    LaunchedEffect(controller, groups, sentences, engine, speaker, speed) {
        val c = controller ?: return@LaunchedEffect
        while (isActive) {
            if (engine != "system") {
                // 顺手把播放器状态同步进来（比只在事件回调里同步更实时）
                syncFromPlayer(c)
                // ⚠️ 这里要**现算**，不能直接用外面那个 finishedAll 变量：
                //    外面那个值是"组合那一刻"算出来的，协程里读会一直是旧的。
                val doneAll = c.playbackState == Player.STATE_ENDED && curGroup >= 0 &&
                    groups.isNotEmpty() && curGroup >= groups.size - 1
                live = when {
                    c.mediaItemCount == 0 -> ""
                    doneAll -> "这本已读完 — 点「继续」可以重头再听"
                    c.isPlaying -> "朗读中（音色 #$speaker，${formatSpeed(speed)}）"
                    // 队列里还有内容、但没出声：要么是用户按了暂停，
                    // 要么是下一段还在合成。靠 playWhenReady 区分这两件事。
                    c.playWhenReady -> "正在合成下一段…（这会儿没声音是正常的）"
                    else -> "已暂停 — 点「继续」从这儿接着听"
                }
                // 用 mediaId 里的段落信息定位。
                // ⚠️ 不能再用 currentMediaItemIndex 去查段落数组 ——
                //    播放列表最前面有一段静音占位，下标跟真实段落差 1，会整体错位（踩过）。
                val info = PlaybackService.parseMediaId(c.currentMediaItem?.mediaId)
                if (info != null && sentences.isNotEmpty()) {
                    val idx = sentenceAtPosition(c, info, sentences)
                    // 用户点的那一句还没念到 → 高亮留在那儿别动（见 pendingHighlight 的说明）
                    if (pendingHighlight >= 0 && idx != pendingHighlight) {
                        // 故意什么都不做
                    } else {
                        // 念到了（或本来就没钉着）→ 交还给常规跟随
                        if (pendingHighlight >= 0) pendingHighlight = -1
                        if (idx != currentIndex) {
                            Log.i(
                                TAG,
                                "高亮 -> 第 ${idx + 1} 句（播放到 ${c.contentPosition}ms，" +
                                    "本段边界 ${if (info.boundsMs.isEmpty()) "无" else info.boundsMs.joinToString("/") + "ms"}）"
                            )
                        }
                        currentIndex = idx
                    }
                }
            }
            delay(120)
        }
    }

    // 已导入的字体列表（启动时读一次；导入/删除后手动刷新）
    LaunchedEffect(Unit) {
        userFonts = UserFonts.list(context).map { it.name }
    }

    // 播放进度每换一个句子就刷新一次"收藏"按钮的状态（已收藏 / 未收藏），
    // ⚠️ 用LaunchedEffect 跟着 currentIndex 走，而不是在每个回调里手动调 ——
    //    这样点句、跳章、上一句/下一句、书签跳转…所有改currentIndex 的路径
    //    都自动被覆盖，不会漏掉某一条（漏一条就表现为"按钮状态和实际不一致"）。
    LaunchedEffect(currentIndex) {
        val u = fileUri?.toString()
        bookmarkOnCurrent = u != null && currentIndex >= 0 &&
            BookmarkStore.has(bookmarkPrefs, u, currentIndex)
    }

    // —— 高亮自动滚动 ——
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex) {
        if (currentIndex < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return@LaunchedEffect
        val first = visible.first().index
        val last = visible.last().index
        // 只在高亮跑出可视范围时才滚动 —— 用户手动翻页时不会被打断拽回去
        if (currentIndex < first || currentIndex > last) {
            listState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0))
        }
    }

    // 音色变化时存下来，后台服务读的是同一份设置
    LaunchedEffect(speaker) { PlaybackService.saveSpeaker(settingPrefs, speaker) }

    // 语速：存下来 **并且立刻**作用到正在播放的播放器上。
    // ⚠️ 这是"播放器变速"，不是重新合成 —— 所以正念着的那句马上就变快/变慢，
    //    队列里已经合成好的段落也一并跟着变，零等待、不用重来。
    LaunchedEffect(speed) {
        PlaybackService.saveSpeed(settingPrefs, speed)
        runCatching { controller?.setPlaybackSpeed(speed) }
    }

    // 停顿：只存设置。后台服务会在**合成下一段**时读它，
    // 所以是"从下一段开始生效"—— 正在念的这段不会中途变，也不用重合成。
    LaunchedEffect(pauseLevel) {
        PlaybackService.savePauseLevel(settingPrefs, pauseLevel)
    }

    // 多音字纠正词典（FR-23）：只存设置，后台服务在合成时读它
    var polyphoneOn by remember { mutableStateOf(PlaybackService.loadPolyphone(settingPrefs)) }
    LaunchedEffect(polyphoneOn) {
        PlaybackService.savePolyphone(settingPrefs, polyphoneOn)
    }

    // 睡眠定时：每秒从设置里同步一次（服务是唯一权威），顺便算好"还剩多久"。
    // ⚠️ 服务到点收尾、或者服务被回收后重建，都是写在这份设置里；
    //    界面不自己维护倒计时，就不会出现"界面还在数、声音已经停了"的错位。
    LaunchedEffect(Unit) {
        while (isActive) {
            val mode = PlaybackService.loadSleepMode(settingPrefs)
            val until = PlaybackService.loadSleepUntil(settingPrefs)
            sleepMode = mode
            sleepUntil = until
            sleepMinutes = PlaybackService.loadSleepMinutes(settingPrefs)
            sleepLeft = if (mode == PlaybackService.SLEEP_TIME && until > 0) {
                formatSleepLeft(until - System.currentTimeMillis())
            } else ""
            delay(1000)
        }
    }

    // 系统 TTS 那边也要跟着改语速，两个引擎行为保持一致
    LaunchedEffect(speed, engine) {
        if (engine == "system") systemTts.setSpeed(speed)
    }

    /**
     * 「本章后停」的收尾位置 = 下一章标题所在的那一句。
     *
     * ⚠️ 为什么由界面算好再交给服务？
     *   章节表只有界面这一份（服务那边只有分组信息）。
     *   谁手上有数据谁去算，算完把"第几句"这个明确坐标交出去，服务照着办就行。
     *
     * 章节标题在分组时一定独立成段，所以"播到这一句"就等于"上一章念完了"。
     */
    fun nextChapterBoundary(): Int {
        val cur = currentIndex.coerceAtLeast(0)
        return chapters.firstOrNull { it.startSentence > cur }?.startSentence ?: sentences.size
    }

    /**
     * 从第 from 句开始朗读。
     *
     * 这是"重新起一轮"才走的路：会清掉服务里旧的队列、重新合成。
     * 单纯的暂停/继续**不要**走这里（那会白等一次合成），
     * 见下面的 pauseReading / resumePlayback —— 它们直接操控播放器，
     * 队列和已合成的音频都留着，所以是"原处接着响"。
     */
    fun startReading(from: Int) {
        if (sentences.isEmpty()) return
        val f = from.coerceIn(0, sentences.size - 1)
        hint = "正在准备第 ${f + 1} 句…（首次要等十几秒加载引擎）"
        if (engine == "system") {
            systemTts.start(sentences.map { it.text }, f)
            return
        }
        val uri = fileUri ?: return
        // ⚠️ 登记"这个队列是为哪本书起的"——后台的队列换书时不会自动变，
        //    界面必须自己记账，后面「继续 / 上一句 / 下一句」都要靠它判断
        //    队列里的内容是不是当前这本书的（见 queueUri 的说明）。
        queueUri = uri.toString()
        val intent = Intent(context, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_START
            putExtra(PlaybackService.EXTRA_URI, uri.toString())
            putExtra(PlaybackService.EXTRA_FROM, f)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * 暂停（等价于播放栏的暂停键）。
     *
     * ⚠️ 这里**只是暂停**，不清队列、不销毁服务。
     *    队列里已经合成好的音频全都留着，所以「继续」是**从断点原处接着响**，
     *    不需要重新合成、不用等。（以前用的是"停止"，会把队列整个清空，
     *    结果继续时只能从头合成这一整段 —— 就是用户反馈的那个问题。）
     */
    fun pauseReading() {
        if (engine == "system") systemTts.pause() else controller?.pause()
        hint = "已暂停"
    }

    /**
     * 继续 / 开始朗读（等价于播放栏的播放键）。
     *
     * 分三种情况，顺序很重要：
     *   1. 队列里还有内容 → 直接 play()，从暂停处接着响（零等待）
     *   2. 队列已播到末尾 → 说明整本读完了，重新从头开始
     *   3. 队列是空的（服务被系统回收过 / 换了新文件）→ 从断点重新起一轮
     */
    fun resumePlayback() {
        if (engine == "system") {
            if (systemTts.hasContent()) systemTts.resume()
            else startReading(if (currentIndex in sentences.indices) currentIndex else 0)
            return
        }
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            // 队列是空的（服务被系统回收过 / 刚换了新书）→ 从断点重新起一轮
            startReading(if (currentIndex in sentences.indices) currentIndex else 0)
            return
        }
        // ⚠️⚠️ **队列里的音频是不是"当前这本书"的？**（v0.3.3 踩到的大 bug）
        //   队列非空就 play() 是错的：换书之后队列里装的还是**上一本**的音频，
        //   play() 出来就是"界面显示新书、声音在放旧书"，再点暂停再继续还是旧书。
        //
        //   判据用 [queueUri]（界面自己记账：这个队列是为哪本书起的），
        //   **不要**拿"队列长度 vs 本书段数"去比 —— 合成是**一段段攒**的，
        //   刚开始朗读时队列长度必然小于总段数，会把功能误判成"不是这本书的"。
        if (!queueMatchesBook()) {
            Log.i(TAG, "队列不是当前这本书的（队列属于 $queueUri）→ 重新起一轮，不 play 旧队列")
            startReading(if (currentIndex in sentences.indices) currentIndex else 0)
            return
        }
        // 已经停在最后一段的末尾 → 整本读完了，再按就是"重头再听一遍"
        val doneAll = c.playbackState == Player.STATE_ENDED && curGroup >= 0 &&
            groups.isNotEmpty() && curGroup >= groups.size - 1
        if (doneAll) {
            startReading(0)
            return
        }
        // 队列里还有内容 → 恢复播放，**不重新合成**，从暂停处接着听。
        // ⚠️ 播放器正卡在段尾时（上一段播完了、下一段还在合成，中间几秒是静音）
        //    不能直接 play()：实测 ExoPlayer 会把当前这一段**从头再播一遍**，
        //    用户按了「继续」却听到刚念过的内容重复一次。
        //    所以交给后台服务判断"从哪儿接"（见 PlaybackService.resumePlayback）。
        if (c.playbackState == Player.STATE_ENDED) {
            PlaybackService.requestResume(context)
        } else {
            c.play()
        }
        hint = "正在朗读…"
    }

    /**
     * 上一句。
     *
     * 系统 TTS 直接在本地挪（它本来就是一句一句念的）；
     * 本地引擎交给后台服务 —— 它手上才有"每句话在音频里的真实位置"，
     * 所以能精确跳到上句开头（而不是只能倒退一整段）。
     */
    fun prevSentence() {
        // 用户改主意、自己翻页了 → 松开刚才钉住的高亮
        pendingHighlight = -1
        if (engine == "system") {
            systemTts.seekTo(currentIndex - 1)
            return
        }
        // ⚠️ 同 openSentence：队列不是这本书的就别跳（会跳进另一本书）
        if (!queueMatchesBook()) {
            startReading(if (currentIndex in sentences.indices) currentIndex else 0)
            return
        }
        PlaybackService.requestStep(context, forward = false)
    }

    /** 下一句。跟「上一句」对称，也是交给服务端精确跳。 */
    fun nextSentence() {
        pendingHighlight = -1
        if (engine == "system") {
            systemTts.seekTo(if (currentIndex < 0) 0 else currentIndex + 1)
            return
        }
        if (!queueMatchesBook()) {
            startReading(if (currentIndex in sentences.indices) currentIndex else 0)
            return
        }
        PlaybackService.requestStep(context, forward = true)
    }

    /**
     * 点正文里的某一句 / 点某个章节：从那一句开始听。
     *
     * ⚠️ 这里以前是"直接重新起一轮"，也就是每次点都要把整段重新合成、等十几秒。
     *    现在一律交给后台服务判断：
     *      · 这一段合成好了 → 毫秒级切过去（听到哪句点哪句，立刻响）
     *      · 还没合成 / 服务刚被系统回收过 → 才重新起一轮
     *    所以"跳到后面的章节"仍然要等，但"在已听过的部分里翻找"是秒响的。
     *
     * ⚠️ 为什么不在界面自己判断（踩过）？
     *    界面能看到的只有"控制器手里的队列有多长"，而控制器可能在重连的一瞬间读不到东西，
     *    于是明明该秒切的跳转被误判成"什么都没有"、白白重新合成一遍。
     *    队列和段落表本来就只有服务端清楚，决策权交给它最准。
     */
    fun openSentence(i: Int) {
        if (sentences.isEmpty()) return
        val idx = i.coerceIn(0, sentences.size - 1)
        // ⚠️ 点下一句之前先确认"现在这个队列是不是这本书的"。
        //   换书之后后台还抱着上一本的队列，直接跳就是跳到**另一本书**里去。
        //   对不上就先替用户把这本书起一轮（等价于"点了朗读"）。
        if (engine != "system" && !queueMatchesBook()) {
            startReading(idx)
            return
        }
        // ⚠️ 点下去就先高亮这一句（原因见 pendingHighlight）：
        //    本地引擎要重新解析 + 合成，头几十秒既没声音也没播放位置，
        //    不先标记的话用户回头不知道自己点了哪句。
        currentIndex = idx
        if (engine == "system") {
            // 系统 TTS 秒开，且每句都有 onSentenceChanged 回调 → 不用钉，交给它跟
            pendingHighlight = -1
            systemTts.start(sentences.map { it.text }, idx)
            return
        }
        val uri = fileUri ?: return
        pendingHighlight = idx
        hint = "正在跳到第 ${idx + 1} 句…"
        PlaybackService.requestSeekSentence(context, uri.toString(), idx)
    }

    // 调试入口：带 openfile 参数启动时，自动载入并开念（用于自动化验证）
    LaunchedEffect(autoOpen) {
        if (autoOpen != null) loadFile(Uri.parse(autoOpen)) { startReading(0) }
    }

    // ── 底部三 Tab 骨架（S5-0）─────────────────────────────────────
    // 三个页面各司其职，一次只显示一个：
    //    书架   = 选书 / 导入 / 管理
    //    正在听 = 纯听书（三键 + 章节条 + 正文）
    //    设置   = 调一次就长期有效的参数（引擎 / 音色 / 语速 / 停顿 / 睡眠）
    //
    // ⚠️ 切 Tab 只是换显示，"正在听"这一页本身**不碰播放器** ——
    //    切到设置调完语速再切回来，朗读一秒都不会断。
    //    这也是"睡眠定时"敢放设置页的原因：挂上之后切走，后台服务照样计时。
    MaterialTheme(colorScheme = lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // 解析大文件要几秒，给个明确的"在忙"提示，别让用户以为点了没反应
            if (loading) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("正在解析这本书") },
                    text = { Text("大文件需要几秒钟，稍等一下") },
                    confirmButton = { }
                )
            }
            // 载入失败：明确告诉用户为什么打不开、下一步能做什么
            loadError?.let { msg ->
                AlertDialog(
                    onDismissRequest = { loadError = null },
                    title = { Text("打不开这本书") },
                    text = { Text(msg) },
                    confirmButton = {
                        TextButton(onClick = { loadError = null }) { Text("知道了") }
                    }
                )
            }
            Scaffold(
                containerColor = Color(0xFFF6F7F9),
                bottomBar = { BottomTabs(tab) { tab = it } }
            ) { pad ->
                Box(modifier = Modifier.fillMaxSize().padding(pad)) {

                    // 读"实时状态"的几项在切换前先算好，三个页面共用同一份判断，
                    // 免得各写一遍、日后改漏一处（按钮显示什么、能不能跳句都靠它）
                    val hasBook = sentences.isNotEmpty()
                    val canStep = if (engine == "system") hasBook else queueActive
                    val mainLabel = when {
                        playing -> "暂停"
                        queueActive || currentIndex in sentences.indices -> "继续"
                        else -> "朗读"
                    }
                    // 状态行 = 正常的朗读状态 + 睡眠定时提醒。
                    // 睡眠控件搬到设置页了，正在听页用这一行小字告诉你"定时还挂着"。
                    val statusText = buildString {
                        append(live.ifEmpty { hint })
                        when (sleepMode) {
                            PlaybackService.SLEEP_TIME -> {
                                if (isNotEmpty()) append("　·　")
                                append("睡眠 $sleepMinutes 分钟后停")
                                if (sleepLeft.isNotEmpty()) append("（$sleepLeft）")
                            }
                            PlaybackService.SLEEP_CHAPTER -> {
                                if (isNotEmpty()) append("　·　")
                                append("睡眠：念完本章后停")
                            }
                        }
                    }

                    when (tab) {
                        AppTab.SHELF -> ShelfScreen(
                            books = books,
                            missing = missingFiles,
                            progressOf = { u -> PlaybackService.loadProgress(progressPrefs, u) },
                            onOpen = { b -> openBook(b) },
                            onDelete = { b ->
                                // 只从书架移除这一张卡片：源文件不动，阅读进度也留着 ——
                                // 以后要是再导入同一个文件，还能接着上次的句子听。
                                BookShelf.remove(shelfPrefs, b.uri)
                                books = BookShelf.all(shelfPrefs)
                            },
                            onImport = { pickFile.launch(arrayOf("*/*")) }
                        )

                        AppTab.NOW -> NowScreen(
                            fileName = fileName,
                            sentences = sentences,
                            chapters = chapters,
                            currentIndex = currentIndex,
                            mainLabel = mainLabel,
                            canStep = canStep,
                            hasBook = hasBook,
                            statusText = statusText,
                            listState = listState,
                            onPrev = { prevSentence() },
                            onNext = { nextSentence() },
                            onToggle = { if (playing) pauseReading() else resumePlayback() },
                            onOpenSentence = { i -> openSentence(i) },
                            onGotoShelf = { tab = AppTab.SHELF },
                            fontFamily = readFontFamily,
                            fontSizeSp = readSizeSp,
                            textColor = readFg,
                            pageBg = readBg,
                            bookmarked = bookmarkOnCurrent,
                            canBookmark = hasBook && currentIndex >= 0,
                            onToggleBookmark = {
                                val u = fileUri?.toString()
                                val i = currentIndex
                                if (u == null || i < 0) {
                                    hint = "先点一句正文，再来收藏"
                                } else if (BookmarkStore.has(bookmarkPrefs, u, i)) {
                                    // 再点一次 = 取消收藏（比只能加不能删好用）
                                    BookmarkStore.remove(bookmarkPrefs, u, i)
                                    hint = "已取消收藏"
                                } else {
                                    BookmarkStore.add(
                                        bookmarkPrefs, u, i,
                                        sentences.getOrNull(i)?.text.orEmpty()
                                    )
                                    hint = "已收藏第 ${i + 1} 句"
                                }
                                refreshBookmarks()
                            }
                        )

                        AppTab.TOC -> TocScreen(
                            fileName = fileName,
                            chapters = chapters,
                            currentIndex = currentIndex,
                            bookmarks = bookmarks,
                            hasBook = hasBook,
                            onOpenSentence = { i ->
                                openSentence(i)
                                // 点目录/书签要自动回到正文页，
                                // 否则"跳过去了但看不到正文"，用户会以为没生效
                                // （别的听书软件也都是这个行为）
                                tab = AppTab.NOW
                            },
                            onAddBookmark = {
                                val u = fileUri?.toString()
                                val i = currentIndex
                                if (u == null || i < 0) {
                                    hint = "先在正文页点一句，再来打书签"
                                } else {
                                    BookmarkStore.add(
                                        bookmarkPrefs, u, i,
                                        sentences.getOrNull(i)?.text.orEmpty()
                                    )
                                    refreshBookmarks()
                                    hint = "已在第 ${i + 1} 句打了书签"
                                }
                            },
                            onRemoveBookmark = { i ->
                                val u = fileUri?.toString() ?: return@TocScreen
                                BookmarkStore.remove(bookmarkPrefs, u, i)
                                refreshBookmarks()
                                hint = "已删除书签"
                            }
                        )

                        AppTab.SETTINGS ->                         SettingsScreen(
                            context = context,
                            engine = engine,
                            onEngine = { engine = it },
                            speaker = speaker,
                            onSpeaker = { speaker = it },
                            speed = speed,
                            onSpeed = { speed = it },
                            pauseLevel = pauseLevel,
                            onPauseLevel = { pauseLevel = it },
                            polyphoneOn = polyphoneOn,
                            onPolyphone = { polyphoneOn = it },
                            sleepMode = sleepMode,
                            sleepMinutes = sleepMinutes,
                            sleepLeft = sleepLeft,
                            hasChapters = chapters.isNotEmpty(),
                            onSleepOff = {
                                sleepMode = PlaybackService.SLEEP_OFF
                                sleepUntil = 0L
                                PlaybackService.requestSleepOff(context)
                            },
                            onSleepMinutes = { m ->
                                sleepMode = PlaybackService.SLEEP_TIME
                                sleepMinutes = m
                                PlaybackService.requestSleepMinutes(context, m)
                            },
                            onSleepChapter = {
                                if (chapters.isEmpty()) {
                                    hint = "这本没识别到章节，换用分钟档吧"
                                } else {
                                    sleepMode = PlaybackService.SLEEP_CHAPTER
                                    PlaybackService.requestSleepChapter(context, nextChapterBoundary())
                                }
                            },
                            onImport = { pickFile.launch(arrayOf("*/*")) },
                            modelStatusText = modelStatus,
                            modelImporting = modelImporting,
                            modelImportHint = modelImportHint,
                            onPickModel = { pickModelDir.launch(null) },
                            readFont = readFont,
                            onFont = { f ->
                                readFont = f
                                ReadingStyle.saveFont(settingPrefs, f)
                            },
                            readSizeSp = readSizeSp,
                            onSizeSp = { sp ->
                                readSizeSp = sp
                                ReadingStyle.saveSize(settingPrefs, sp)
                            },
                            userFonts = userFonts,
                            userFontFile = userFontFile,
                            onUserFont = { f ->
                                userFontFile = f
                                ReadingStyle.saveUserFont(settingPrefs, f)
                            },
                            onImportFont = { pickFontFile.launch(arrayOf("*/*")) },
                            onDeleteFont = { f ->
                                UserFonts.delete(context, f)
                                if (userFontFile == f) {
                                    userFontFile = ""
                                    ReadingStyle.saveUserFont(settingPrefs, "")
                                }
                                userFonts = UserFonts.list(context).map { it.name }
                            },
                            readTheme = readTheme,
                            onTheme = { th ->
                                readTheme = th
                                ReadingStyle.saveTheme(settingPrefs, th)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「正在听」页 —— 纯听书的地方。
 *
 * 只留最常用的三样：三键（上一句 / 暂停继续 / 下一句）、章节条、正文。
 * 引擎、音色、语速、停顿、睡眠这些"调一次就长期不动"的参数全在设置页，
 * 免得正文老被一堆控件挤得只剩几行。
 *
 * ⚠️ 这一页只是"显示器"，所有动作都通过回调交给上层：
 *    暂停/继续要动播放器、跳句要问后台服务 —— 播放状态的家底在服务那儿，
 *    界面自己揣一份迟早会两边对不上（这是踩过的坑）。
 */
@Composable
private fun NowScreen(
    fileName: String?,
    sentences: List<Sentence>,
    chapters: List<Chapter>,
    currentIndex: Int,
    mainLabel: String,
    canStep: Boolean,
    hasBook: Boolean,
    statusText: String,
    listState: LazyListState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggle: () -> Unit,
    onOpenSentence: (Int) -> Unit,
    onGotoShelf: () -> Unit,
    fontFamily: FontFamily?,
    fontSizeSp: Float,
    textColor: Color,
    pageBg: Color,
    bookmarked: Boolean,
    canBookmark: Boolean,
    onToggleBookmark: () -> Unit
) {
    // ⚠️ 配色要铺满**整个页面**（Leo 反馈过"只在页面中间实现了"）：
    //   顶栏书名、三键、状态行、正文区全部同一个背景色，
    //   否则夜间模式下会出现"深色正文 + 亮色顶栏"的割裂感。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBg)
            .padding(16.dp)
    ) {

        // 顶部：书名（一眼知道在听哪本）+ 换一本（切回书架，不打断朗读）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = fileName ?: "还没选择书",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 字色跟着配色走，否则夜间模式下顶栏是深底黑字（看不见）
                color = textColor,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = onGotoShelf,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = textColor
                )
            ) { Text("换一本") }
        }
        Spacer(Modifier.height(10.dp))

        if (!hasBook) {
            // 还没选书时的引导：指路，而不是干摆着一个按不动的按钮
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎧", fontSize = 46.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("还没有在听的书", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "去书架挑一本接着听，或者导入一个新的 TXT / EPUB / PDF 文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onGotoShelf) { Text("去书架") }
                }
            }
            return@Column
        }

        // 三键。主按钮只有两种状态：正在念 → 「暂停」；否则 → 「继续」/「朗读」。
        // 跟通知栏的播放控制条是同一套语义，按哪边都一样。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                enabled = canStep,
                onClick = onPrev,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = textColor
                )
            ) { Text("上一句") }

            Button(
                modifier = Modifier.weight(1f),
                onClick = onToggle
            ) { Text(mainLabel) }

            OutlinedButton(
                enabled = canStep,
                onClick = onNext,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = textColor
                )
            ) { Text("下一句") }

            // 书签按钮紧挨着播放控件 —— 在这里才能"听到一句、顺手打个记号"（Leo 提的）
            OutlinedButton(
                enabled = canBookmark,
                onClick = onToggleBookmark,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = textColor
                )
            ) { Text(if (bookmarked) "已收藏" else "收藏") }
        }
        Spacer(Modifier.height(12.dp))

        // ⚠️ 这里原来有一根**横向**章节条（LazyRow），已搬到「目录」页（Leo 提的）：
        //   横向条子只能左右滑、一眼看不全，章节一多就挤成一条细缝，
        //   想知道"读到第几章"、想跳别的章，现在去底部「目录」页。
        // 目录页还多给了两件这里做不到的事：显示完整标题、标出正在读的那一章。

        // 正文区：整块铺上选定的背景色。
        // ⚠️ 每一行也要各自铺一次 —— 行与行之间有padding 的缝，
        //    只给列表铺色的话缝里会露出下层页面底色，深色模式下会看到一条条白线。
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .background(pageBg),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(sentences) { i, s ->
                val isCurrent = i == currentIndex
                Text(
                    text = s.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isCurrent) highlightBg(textColor, pageBg) else pageBg)
                        .clickable { onOpenSentence(i) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    // ⚠️ 字号用 sp（跟着系统字体缩放走），行高给1.7 倍——
                    //    中文在小字号下行距不够会"上下笔画粘在一起"。
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = fontFamily,
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp * 1.7f).sp,
                        color = textColor
                    )
                )
            }
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        )
    }
}

/**
 * 「设置」页 —— 调一次就长期有效的参数都在这儿。
 *
 * 这些都是"改完从下一段生效"或"立刻生效"的开关，听书过程中很少动，
 * 所以从正文页搬出来（正文页以前被这些控件挤得只剩几行）。
 * ⚠️ 这一页可以滚动：小屏手机上控件一多，不滚动就会被底部导航切掉半截。
 */
@Composable
private fun SettingsScreen(
    context: Context,
    engine: String,
    onEngine: (String) -> Unit,
    speaker: Int,
    onSpeaker: (Int) -> Unit,
    speed: Float,
    onSpeed: (Float) -> Unit,
    pauseLevel: Int,
    onPauseLevel: (Int) -> Unit,
    polyphoneOn: Boolean,
    onPolyphone: (Boolean) -> Unit,
    sleepMode: Int,
    sleepMinutes: Int,
    sleepLeft: String,
    hasChapters: Boolean,
    onSleepOff: () -> Unit,
    onSleepMinutes: (Int) -> Unit,
    onSleepChapter: () -> Unit,
    onImport: () -> Unit,
    modelStatusText: String,
    modelImporting: Boolean,
    modelImportHint: String,
    onPickModel: () -> Unit,
    readFont: ReadingStyle.SysFont,
    onFont: (ReadingStyle.SysFont) -> Unit,
    readSizeSp: Float,
    onSizeSp: (Float) -> Unit,
    readTheme: ReadingStyle.Theme,
    onTheme: (ReadingStyle.Theme) -> Unit,
    userFonts: List<String>,
    userFontFile: String,
    onUserFont: (String) -> Unit,
    onImportFont: () -> Unit,
    onDeleteFont: (String) -> Unit
) {
    val context = LocalContext.current
    // 版本号从安装包上读，省得升版本时还要来改一处文案
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "改完立刻生效的参数都在这里",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Spacer(Modifier.height(16.dp))

        // ── 朗读引擎 ─────────────────────────────────────────────
        SettingTitle("朗读引擎")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChoiceChip("系统 TTS", engine == "system") { onEngine("system") }
            ChoiceChip("本地 Kokoro", engine == "kokoro") { onEngine("kokoro") }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "系统 TTS 秒开、省电；本地 Kokoro 音质好、完全离线，但首次要等十几秒加载模型。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        // 模型状态（Full 变体装完即用；Lite 变体要点按钮自己选一份模型）
        if (engine == "kokoro") {
            val modelStatus = modelStatusText
            Spacer(Modifier.height(6.dp))
            Text(
                text = "模型：$modelStatus",
                style = MaterialTheme.typography.bodySmall,
                color = if (modelStatus.contains("缺少") || modelStatus.contains("没有找到模型")) {
                    Color(0xFFB3261E)
                } else Color.Gray
            )

            // ── 导入模型按钮（Lite 版真正需要的东西）──────────────
            //
            // ⚠️ 以前这里只有一句"用数据线拷到 Android/data/... 路径下"的提示，
            //   路径要手打、Lite 还多一个 .lite 后缀，用户基本没法自己搞定。
            //   现在给一个按钮：用系统文件选择器挑文件夹，App 自己拷进来。
            //
            // ⚠️ 为什么要用 SAF 而不是让用户填路径：Android 11 起是分区存储，
            //   App 压根读不到别的目录 —— 只有用户在**系统界面**里亲自授权才行。
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onPickModel() },
                    enabled = !modelImporting,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) { Text(if (modelImporting) "正在导入…" else "选择模型文件夹") }
                Spacer(Modifier.width(10.dp))
                if (modelStatus.contains("已就绪") || modelStatus.contains("待导入")) {
                    TextButton(
                        onClick = { onPickModel() },
                        enabled = !modelImporting,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) { Text("重新导入") }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "选到含有 model.int8.onnx 的那一层文件夹（就是模型文件夹本身）。\n" +
                    "选它的上级目录也行，我们会自动往下找一层。\n" +
                    "Full 版不需要这一步（模型已内置）；Lite 版第一次用本地引擎才需要。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            if (modelImportHint.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = modelImportHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (modelImportHint.startsWith("导入完成")) Color(0xFF1A7F47)
                    else Color(0xFFB3261E)
                )
            }
        }

        // 音色只在本地引擎下有意义（系统 TTS 的音色要去系统设置里调）
        if (engine == "kokoro") {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("音色：", style = MaterialTheme.typography.bodyMedium)
                SmallButton("-10") { onSpeaker((speaker - 10).coerceIn(0, 102)) }
                SmallButton("-1") { onSpeaker((speaker - 1).coerceIn(0, 102)) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "#$speaker",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.width(52.dp),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = voiceLabel(speaker),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                SmallButton("+1") { onSpeaker((speaker + 1).coerceIn(0, 102)) }
                SmallButton("+10") { onSpeaker((speaker + 10).coerceIn(0, 102)) }
            }
        }
        Spacer(Modifier.height(18.dp))

        // ── 语速（S4-3）──────────────────────────────────────────
        // 这是"播放器变速"：点一下马上就能听出快慢变化，
        // 队列里已经合成好的段落也一起变，不用等重新合成。
        SettingTitle("语速")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SmallButton("−") { onSpeed(stepSpeed(speed, -PlaybackService.SPEED_STEP)) }
            Text(
                text = formatSpeed(speed),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.Center
            )
            SmallButton("＋") { onSpeed(stepSpeed(speed, PlaybackService.SPEED_STEP)) }
            SmallButton("复位") { onSpeed(1.0f) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "播放器实时变速：调完正在念的这句马上就变，不用等重新合成。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Spacer(Modifier.height(18.dp))

        // ── 句间停顿（S4-3，仅本地引擎）──────────────────────────
        // ⚠️ 这一项改的是"合成出来的音频本身"，所以要重新合成才听得出来。
        //    约定：改完**从下一段开始生效** —— 正在念的这段不被打断，
        //    后面新合成的段落用新设置（不用清队列、不用重来一遍）。
        //    系统 TTS 那边没法控制句间停顿，所以只在本地引擎下显示。
        if (engine == "kokoro") {
            SettingTitle("句间停顿")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChoiceChip("紧凑", pauseLevel == PlaybackService.PAUSE_COMPACT) {
                    onPauseLevel(PlaybackService.PAUSE_COMPACT)
                }
                ChoiceChip("标准", pauseLevel == PlaybackService.PAUSE_NORMAL) {
                    onPauseLevel(PlaybackService.PAUSE_NORMAL)
                }
                ChoiceChip("舒缓", pauseLevel == PlaybackService.PAUSE_RELAXED) {
                    onPauseLevel(PlaybackService.PAUSE_RELAXED)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "改动从下一段开始生效，正在念的这段不会被打断。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(Modifier.height(18.dp))

            // ── 多音字纠正（FR-23，仅本地引擎）────────────────────
            //    模型读多音字只能按"这个字的默认读音"，没法像人一样看上下文。
            //    这里的做法是把那个字换成同音字（银行→银航），听感完全一样。
            //    表里每一条都用模型自己的词表校验过注音与声调，所以念音必然正确。
            if (engine == "kokoro") {
                SettingTitle("多音字纠正")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChoiceChip("开", polyphoneOn) { onPolyphone(true) }
                    ChoiceChip("关", !polyphoneOn) { onPolyphone(false) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "共 ${PronunciationDict.size()} 条常用词（银行/行业/重复/成长…）。" +
                        "关掉就按模型自己的读法念。改动从下一段生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(Modifier.height(18.dp))
            }
        }

        // ── 睡眠定时（S4-5）──────────────────────────────────────
        // ⚠️ 计时器在**后台服务**里跑，这里只负责"发指令 + 看状态"。
        //    因为听书时用户多半已经锁屏、界面早就不在了 —— 定时放界面上会失效。
        //    分钟档算的是**真实时间**：设 20 分钟就是 20 分钟后停，
        //    中途接电话、手动暂停都照样走（符合"我想几点前睡着"的直觉）。
        SettingTitle("睡眠定时")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactChip("关", sleepMode == PlaybackService.SLEEP_OFF) { onSleepOff() }
            PlaybackService.SLEEP_PRESETS.forEach { m ->
                val on = sleepMode == PlaybackService.SLEEP_TIME && sleepMinutes == m
                CompactChip("${m}分", on) { onSleepMinutes(m) }
            }
            CompactChip("本章后停", sleepMode == PlaybackService.SLEEP_CHAPTER) {
                onSleepChapter()
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                sleepMode == PlaybackService.SLEEP_TIME ->
                    "到点后声音会在 4 秒内淡出再暂停，断点全保留 —— " +
                        "点「继续」秒接上。" +
                        if (sleepLeft.isEmpty()) "" else "（$sleepLeft）"
                sleepMode == PlaybackService.SLEEP_CHAPTER ->
                    "念完当前这一章后自动淡出暂停。"
                !hasChapters -> "这本没识别到章节，「本章后停」用不了，换分钟档吧。"
                else -> "听着睡着自动停。分钟档按真实时间走，锁屏也照算。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (sleepMode == PlaybackService.SLEEP_OFF) Color.Gray else Color(0xFF3B6BFF)
        )
        Spacer(Modifier.height(18.dp))

        // ── 我的书 ───────────────────────────────────────────────
        // ── 正文排版（字体 / 字号 / 配色）──────────────────────
        // 放「我的书」前面：这三项是"每天都要用"的观感设置，
        //   埋到页面最底下会被前面的音色/语速/定时挤到看不见。
        SettingTitle("正文排版")
        Text(
            text = "只影响正文显示，不影响朗读。改完立刻生效。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Spacer(Modifier.height(10.dp))

        // ── 字体（下拉菜单，Leo 要求）─────────────────────────
        Text("字体", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        FontDropdown(
            sysFont = readFont,
            onSysFont = onFont,
            userFonts = userFonts,
            userFontFile = userFontFile,
            onUserFont = onUserFont,
            onImportFont = onImportFont,
            onDeleteFont = onDeleteFont
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = readFont.hint,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        // ── 字号（滑动条 1~99，Leo 要求）─────────────────────
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                text = "${readSizeSp.toInt()} sp",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF2B5CFF)
            )
        }
        // steps = 97 → 1..99 之间每一sp 一档，共 98 格。
        // ⚠️ 滑条 + onValueChange 会在拖动时连续回调，松手才写入存储，
        //    否则拖一次写几十次 SharedPreferences（会拖慢界面）。
        AppleSlider(
            value = readSizeSp,
            onValueChange = { onSizeSp(ReadingStyle.clampSize(it)) },
            valueRange = ReadingStyle.MIN_SIZE_SP..ReadingStyle.MAX_SIZE_SP,
            steps = 97
        )
        Text(
            text = "1 sp 极小（看版式）　·　17 sp 舒适　·　40 sp 以上适合视力不便者",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )

        // ── 配色 ────────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        Text("配色", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadingStyle.Theme.entries.forEach { th ->
                ChoiceChip(th.label, readTheme == th) { onTheme(th) }
            }
        }
        Spacer(Modifier.height(8.dp))

        // 实时预览：用**当前选中的**字体/字号/配色渲染，
        // 不用切到「正在听」去看效果 —— 改完自己去看一眼确认样式很 low效。
        val (pvFg, pvBg) = ReadingStyle.colors(readTheme, isSystemInDarkTheme())
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = pvBg,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "第一章雨夜来客　雨点敲在铁皮屋顶上，发出密集的鼓点声。",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = UserFonts.familyOf(context, userFontFile) ?: readFont.family,
                    fontSize = readSizeSp.sp,
                    lineHeight = readSizeSp.sp * 1.7f,
                    color = pvFg
                )
            )
        }

        SettingTitle("我的书")
        OutlinedButton(onClick = onImport) { Text("导入书籍（TXT / EPUB / PDF）") }
        Spacer(Modifier.height(18.dp))

        // ── 关于 ─────────────────────────────────────────────────
        SettingTitle("关于")
        Text(
            text = "阅听 Readaloud $version",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "完全离线：应用不含联网权限，系统层面就无法访问网络。" +
                "导入的书籍、朗读过程全在手机本地完成，任何内容都不会上传。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** 设置页里的一节小标题 */
@Composable
private fun SettingTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = Color(0xFF3B6BFF)
    )
    Spacer(Modifier.height(6.dp))
}

/**
 * 底部导航条。
 *
 * ⚠️ 图标是**自己画的**（TabIcon 里用 Canvas 画），没有引图标库：
 *   Compose 的 material-icons 不在我们的依赖树里，为一个三图标导航
 *   去加一个几百 KB 的依赖不划算，而且自绘的线条风格更好统一。
 */
@Composable
private fun BottomTabs(current: AppTab, onSelect: (AppTab) -> Unit) {
    Column {
        // 一条极细的分隔线，让导航条和浅灰的内容区分开
        HorizontalDivider(color = Color(0x14000000), thickness = 1.dp)
        NavigationBar(
            containerColor = Color.White,
            tonalElevation = 0.dp
        ) {
            AppTab.entries.forEach { t ->
                val selected = t == current
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(t) },
                    icon = { TabIcon(t, if (selected) Color(0xFF3B6BFF) else Color(0xFF8A8A8E)) },
                    label = { Text(tabLabel(t), style = MaterialTheme.typography.bodySmall) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = Color(0xFFE8EEFF),
                        selectedIconColor = Color(0xFF3B6BFF),
                        selectedTextColor = Color(0xFF3B6BFF),
                        unselectedIconColor = Color(0xFF8A8A8E),
                        unselectedTextColor = Color(0xFF8A8A8E)
                    )
                )
            }
        }
    }
}

private fun tabLabel(t: AppTab): String = when (t) {
    AppTab.SHELF -> "书架"
    AppTab.NOW -> "正在听"
    AppTab.TOC -> "目录"
    AppTab.SETTINGS -> "设置"
}

/**
 * 自己画的 Tab 图标（22dp 见方，纯几何线条）。
 *
 * 三个图形刻意长得完全不一样，扫一眼就能认出来：
 *   书架   = 三根竖条（一排立着的书脊）
 *   正在听 = 四根高低竖条（声波）
 *   设置   = 三根横滑块（调节杆）
 */
@Composable
private fun TabIcon(tab: AppTab, tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        when (tab) {
            AppTab.SHELF -> {
                // 一排书脊：等高竖条、底部对齐，高度略有差别才像"书"而不是柱状图
                val barW = w * 0.18f
                val gap = (w - barW * 3f) / 2f
                val heights = listOf(0.72f, 0.92f, 0.82f)
                heights.forEachIndexed { i, f ->
                    val bh = h * f
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(i * (barW + gap), h - bh),
                        size = Size(barW, bh),
                        cornerRadius = CornerRadius(barW * 0.35f, barW * 0.35f)
                    )
                }
            }
            AppTab.NOW -> {
                // 声波：四根竖条，中间高两边低，垂直居中
                val barW = w * 0.16f
                val gap = (w - barW * 4f) / 3f
                val heights = listOf(0.42f, 0.78f, 0.98f, 0.6f)
                heights.forEachIndexed { i, f ->
                    val bh = h * f
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(i * (barW + gap), (h - bh) / 2f),
                        size = Size(barW, bh),
                        cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f)
                    )
                }
            }
            AppTab.TOC -> {
                // 目录：三条长短不一的横线（像目录里的条目），最上面一条最长
                val barH = h * 0.12f
                val gap = (h - barH * 3f) / 2f
                val widths = listOf(1f, 0.78f, 0.9f)
                widths.forEachIndexed { i, f ->
                    val y = i * (barH + gap)
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(0f, y),
                        size = Size(w * f, barH),
                        cornerRadius = CornerRadius(barH / 2f, barH / 2f)
                    )
                }
            }
            AppTab.SETTINGS -> {
                // 三根横滑块：灰杆 + 实心圆点（圆点位置错开，一眼看出是"调节"）
                val barH = h * 0.13f
                val dotR = h * 0.13f
                val ys = listOf(0.2f, 0.5f, 0.8f)
                val dotX = listOf(0.72f, 0.32f, 0.6f)
                ys.forEachIndexed { i, f ->
                    val cy = h * f
                    drawRoundRect(
                        color = tint.copy(alpha = 0.35f),
                        topLeft = Offset(0f, cy - barH / 2f),
                        size = Size(w, barH),
                        cornerRadius = CornerRadius(barH / 2f, barH / 2f)
                    )
                    drawCircle(color = tint, radius = dotR, center = Offset(w * dotX[i], cy))
                }
            }
        }
    }
}

/** 底部导航的三个页面 */
private enum class AppTab { SHELF, NOW, TOC, SETTINGS }

/**
 * 算出"现在正在念第几句"。
 *
 * 一段音频里其实包含好几句文字，播放器只能告诉我们"这一段播到第几毫秒"。
 * 合成的时候已经把这些句子在音频里的**真实结束时刻**从波形里测出来了
 * （写在 mediaId 里），这里拿播放进度直接对号入座就行 —— 准。
 *
 * ⚠️ 以前是纯靠猜的：按每句话的字数比例去推算。
 *    实测根本不准 —— 中文标点不发音、各句语速也不一样，
 *    猜出来的位置会**跑在真实朗读前面**（用户反馈"高亮已经到下一句了"）。
 *    只有在极少数测不出边界的段落上，才退回用这个估算兜底。
 */
private fun sentenceAtPosition(
    c: MediaController,
    info: SegmentInfo,
    sentences: List<Sentence>
): Int {
    if (sentences.isEmpty()) return -1
    val start = info.startSentence.coerceIn(0, sentences.size - 1)
    val end = (start + info.sentenceCount).coerceIn(start + 1, sentences.size)
    if (end - start <= 1) return start

    val pos = c.contentPosition

    // 首选：用合成时实测出来的句子边界
    val bounds = info.boundsMs
    if (bounds.size == end - start) {
        for (i in bounds.indices) {
            if (pos < bounds[i]) return start + i
        }
        return end - 1
    }

    // 兜底：按字数比例估算（时长用 mediaId 里带的，不依赖播放器自报的 contentDuration）
    val dur = info.durationMs
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val lens = (start until end).map { sentences[it].text.length.coerceAtLeast(1) }
    val total = lens.sum()
    var acc = 0
    var k = 0
    for (i in lens.indices) {
        acc += lens[i]
        k = i
        if (acc >= frac * total) break
    }
    return (start + k).coerceIn(0, sentences.size - 1)
}

/**
 * 当前句的高亮底色。
 *
 * 用**背景色加深一点**而不是固定浅黄：夜间模式下浅黄底会非常刺眼
 * （深色页面上一个亮块，盯着它反而看不清字）。
 * 做法：拿背景色往"更暗"方向调一点，字色保持不变 → 任何配色下都协调。
 */
private fun highlightBg(fg: Color, bg: Color): Color =
    Color(
        red = (bg.red * 0.88f).coerceIn(0f, 1f),
        green = (bg.green * 0.88f).coerceIn(0f, 1f),
        blue = (bg.blue * 0.88f).coerceIn(0f, 1f),
        alpha = 1f
    )

private fun voiceLabel(id: Int): String = when (id) {
    0, 1 -> "英语女声"
    2 -> "英式女声"
    3 -> "中文女声"
    4 -> "中文女声2"
    59 -> "中文男声"
    else -> ""
}

/**
 * 语速加减一档。
 *
 * ⚠️ 为什么要 round 一下（踩过的小坑）：
 *    浮点数加减 0.1 会攒出 0.7999999999999999 这种尾巴，
 *    显示成 "0.8×" 看着正常，但存进设置里、跟上下限比较时就开始闹别扭。
 *    统一四舍五入到一位小数，值永远是干净的。
 */
private fun stepSpeed(current: Float, delta: Float): Float {
    val v = (current + delta)
        .coerceIn(PlaybackService.MIN_SPEED, PlaybackService.MAX_SPEED)
    return kotlin.math.round(v * 10f) / 10f
}

private fun formatSpeed(speed: Float): String = "%.1f×".format(speed)

/** 睡眠定时的剩余时间文案：还剩 18 分 24 秒 */
private fun formatSleepLeft(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return if (m > 0) "还剩 $m 分 $s 秒" else "还剩 $s 秒"
}

/**
 * 一排可选项里的一个「胶囊按钮」（引擎、停顿档位都用它）。
 * 选中了是实心蓝底白字，没选中是浅灰底深灰字。
 */
@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Color(0xFF3B6BFF) else Color(0xFFEFEFEF),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color.DarkGray,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * 紧凑版胶囊按钮 —— 用在选项多、一行塞不下的时候（比如睡眠定时那排）。
 * 跟 ChoiceChip 长一样，只是内边距收窄一点。
 */
@Composable
private fun CompactChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Color(0xFF3B6BFF) else Color(0xFFEFEFEF),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            color = if (selected) Color.White else Color.DarkGray,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFEFEFEF),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * 书架页（S4-4）。
 *
 * 每本书一张卡片：封面 + 书名 + 读到哪了的进度条。
 * 封面是**程序画出来的**（没有图片素材）—— 见 coverBrush 的说明。
 * 源文件已经不在了的书会盖一层深色并标出来，免得用户点进去才发现打不开。
 *
 * 交互：点一下 = 接着上次听；长按 = 从书架移除（会先确认，且不动源文件）。
 */
@Composable
private fun ShelfScreen(
    books: List<Book>,
    missing: Set<String>,
    progressOf: (String) -> Int,
    onOpen: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onImport: () -> Unit
) {
    // 长按后待确认移除的那本书（null 表示没有弹窗）
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("我的书架", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = if (books.isEmpty()) "还没有书，从右边导入一本试试"
                    else "共 ${books.size} 本 · 长按卡片可以移除",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            OutlinedButton(onClick = onImport) { Text("导入书籍") }
        }
        Spacer(Modifier.height(14.dp))

        if (books.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📖", fontSize = 46.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("书架还是空的", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "点右上角的「导入书籍」，选一个本地的\n" +
                            "TXT / EPUB / PDF 文件，以后每次打开 App 直接从书架接着听",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(books, key = { it.uri }) { b ->
                    BookCard(
                        book = b,
                        missing = b.uri in missing,
                        read = progressOf(b.uri),
                        onClick = { onOpen(b) },
                        onLongClick = { pendingDelete = b }
                    )
                }
            }
        }
    }

    // ⚠️ 移除前一定要确认：长按很容易误触，而用户会担心"是不是把书删了"。
    //    所以文案里明确写清楚：只动书架，不动手机上的文件。
    pendingDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("从书架移除？") },
            text = {
                Text(
                    "《${b.title}》\n\n" +
                        "只会把这张卡片从书架上去掉，手机上的原文件不会被删除，" +
                        "以后想听再导入一次就行。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(b)
                    pendingDelete = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 书架上一本书的卡片。
 *
 * @param read 读到第几句（-1 表示没读过）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    missing: Boolean,
    read: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val total = book.totalSentences.coerceAtLeast(1)
    val frac = if (read <= 0 || missing) 0f else (read.toFloat() / total).coerceIn(0f, 1f)

    Column(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)          // 接近一本实体书的长宽比
                .clip(RoundedCornerShape(12.dp))
                .background(coverBrush(book.title)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = book.title.take(1),
                fontSize = 46.sp,
                color = Color.White.copy(alpha = 0.9f)
            )
            if (missing) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xCC1A1A1A)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "源文件\n已丢失",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier.fillMaxWidth().height(4.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                missing -> "源文件不在了"
                read <= 0 -> "还没开始听 · 共 $total 句"
                else -> "已听 $read / $total 句"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 给没有封面的 TXT 生成一张封面：按书名算出一个固定的色相，做同色系柔和渐变。
 * 同一本书每次显示的颜色都一样。
 *
 * ⚠️ 为什么用代码画、不放图片素材（有意为之）：
 *   1. TXT 文件本来就没有封面可言，硬塞一张默认图反而显得廉价；
 *   2. 打包图片会占体积 —— 我们的 APK 已经 47MB（模型占大头），
 *      程序化生成零体积，也不会引入任何素材版权问题；
 *   3. 每本书颜色不同，书架上看起来是一排彩色的书脊，比统一灰底好看得多。
 */
private fun coverBrush(title: String): Brush {
    val hue = (kotlin.math.abs(title.hashCode()) % 360).toFloat()
    val top = Color.hsl(hue, 0.36f, 0.63f)
    val bottom = Color.hsl((hue + 26f) % 360f, 0.44f, 0.43f)
    return Brush.linearGradient(listOf(top, bottom))
}
