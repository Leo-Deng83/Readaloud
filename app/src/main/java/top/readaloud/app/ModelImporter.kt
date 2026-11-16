package top.readaloud.app

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **Lite 版**的模型导入：让用户用系统文件选择器挑一个文件夹，把模型搬进来。
 *
 * ## 为什么需要它
 *
 * Full 变体把模型打进安装包，装完即用；Lite 变体为了省体积（42MB vs 180MB）不带模型，
 * 原来的做法是"用户自己用数据线把模型文件夹拷到
 * `/sdcard/Android/data/<包名>/files/kokoro`，然后重开 App"——
 * 路径要手打、还要知道包名（Lite 还多一个 `.lite` 后缀），非常容易搞错。
 *
 * 换成系统文件选择器之后：点一下 → 在系统界面里挑模型所在的文件夹 → 等进度条走完。
 *
 * ## 为什么不能直接用文件路径
 *
 * Android 11（API 30）起有**分区存储**：App 不能随便读别的目录，
 * 官方给的路子就是 SAF（Storage Access Framework）——
 * 用户在系统界面里亲自授权，我们才有权读那个目录。
 *
 * ## 几个必须注意的点（都是踩过才知道的）
 *
 * 1. **不能假设选中的文件夹就是模型目录本身**。用户很可能选到它的上级目录
 *    （比如选了整个"下载"目录），所以要**向下找一层**：`模型目录/xxx.kokoro` 这种结构
 *    也认，认不到才报错并说清缺什么。
 * 2. **先拷到临时目录，全部拷完、校验通过后再改名**。直接往正式目录拷，
 *    中途失败会留下半套文件（下次启动就再也认不出来了）。
 * 3. **跳过已存在且大小一致的文件**（增量更新）。200MB 每次全量拷太慢，
 *    用户换一本书重开 App 也不该重新搬 200MB。
 * 4. **递归深度要限**（SAF 目录可能有软链接环，极端情况会无限递归），
 *    并且全程跑在 IO 线程上。
 */
object ModelImporter {

    private const val TAG = "ModelImporter"

    /** 模型必需的顶层文件（少任何一个都跑不起来） */
    val REQUIRED_FILES = listOf(
        "model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-zh.txt", "dict"
    )

    /** 引擎还会用到（缺失会导致部分语种发音异常，所以一并提示） */
    val OPTIONAL_FILES = listOf("espeak-ng-data", "lexicon-us-en.txt")

    /**
     * 把用户在系统文件选择器里挑中的目录导入为可用模型。
     *
     * @param onProgress 进度回调（**在 IO 线程上**，调用方要自己切回主线程）
     * @return 导入结果描述；失败时 throw，由调用方转成 Result
     */
    suspend fun import(
        context: Context,
        targetDir: File,
        treeUri: Uri,
        onProgress: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("打不开你选的文件夹（可能没有访问权限）")

        // ① 先找到"真正装着模型的目录"
        val source = locateModelDir(root, treeUri)
            ?: throw IllegalStateException(missingHint(root))

        // ② 拷到临时目录（失败不污染正式目录）
        val staging = File(targetDir.parentFile, "${targetDir.name}.importing")
        staging.deleteRecursively()
        staging.mkdirs()

        var copied = 0
        var skipped = 0
        var bytes = 0L
        val started = System.currentTimeMillis()

        fun copyTree(src: DocumentFile?, dst: File, depth: Int) {
            if (src == null || depth > 8) return
            val kids = src.listFiles()
            for (k in kids) {
                val target = File(dst, k.name)
                if (k.isDirectory) {
                    target.mkdirs()
                    copyTree(k, target, depth + 1)
                } else if (k.isFile && !k.name.isNullOrEmpty()) {
                    val size = k.length()
                    // ③ 增量：已存在且大小一致就跳过，别白搬 200MB
                    if (target.isFile && target.length() == size && size > 0) {
                        skipped++
                        bytes += size
                        continue
                    }
                    try {
                        context.contentResolver.openInputStream(k.uri)?.use { i ->
                            target.parentFile?.mkdirs()
                            target.outputStream().use { o -> i.copyTo(o, 256 * 1024) }
                        }
                        copied++
                        bytes += size
                    } catch (e: Exception) {
                        Log.w(TAG, "跳过读不了的文件：${k.name}（${e.message}）")
                    }
                }
            }
            // ④ 进度回调（每拷完一个文件报一次，别刷屏）
            if (copied + skipped > 0) {
                val mb = bytes / 1048576
                onProgress("正在导入模型…已处理 $mb MB（$copied 个新文件）")
            }
        }

        copyTree(source, staging, 0)

        // ⑤ 校验：必需文件必须齐全
        val missing = REQUIRED_FILES.filter { !File(staging, it).exists() }
        if (missing.isNotEmpty()) {
            staging.deleteRecursively()
            throw IllegalStateException(
                "这个文件夹里没有完整的模型，缺少：${missing.joinToString("、")}"
            )
        }

        // ⑥ 齐全了才替换正式目录
        targetDir.deleteRecursively()
        if (!staging.renameTo(targetDir)) {
            // rename 偶尔会失败（比如跨挂载点），退化成"拷进去"
            copyTree(source, targetDir, 0)
            staging.deleteRecursively()
        }

        val secs = (System.currentTimeMillis() - started) / 1000
        val warn = OPTIONAL_FILES.filter { !File(targetDir, it).exists() }
        val warnText = if (warn.isEmpty()) "" else "（缺 ${warn.joinToString("、")}，部分语种发音会受影响）"
        Log.i(TAG, "模型导入完成：新拷 $copied 个、跳过 $skipped 个，耗时 ${secs}s")
        "模型导入完成，共 ${(bytes / 1048576)} MB${if (copied > 0) "，新拷 $copied 个文件" else ""}$warnText"
    }

    /**
     * 用户可能选中模型的**上级**目录（例如整个"下载"文件夹），
     * 这里向下找最多两层，找到含 [REQUIRED_FILES] 的那个就返回它。
     */
    private fun locateModelDir(root: DocumentFile, treeUri: Uri): DocumentFile? {
        // 先看自己是不是就是模型目录
        val names = root.listFiles().mapNotNull { it.name }
        if (REQUIRED_FILES.all { names.contains(it) }) return root

        // 常见的套娃结构：选到的是上一级，模型在里面某个子目录
        for (sub in root.listFiles()) {
            if (!sub.isDirectory) continue
            val subNames = sub.listFiles().mapNotNull { it.name }
            if (REQUIRED_FILES.all { subNames.contains(it) }) return sub
        }
        Log.i(TAG, "在 ${root.name ?: treeUri} 下没找到模型目录")
        return null
    }

    /** 没找到时，把"到底缺什么"说清楚 —— 用户最需要的就是这句话 */
    private fun missingHint(root: DocumentFile): String {
        val names = root.listFiles().mapNotNull { it.name }.sorted()
        return buildString {
            append("这个文件夹里没有找到模型（必需：${REQUIRED_FILES.joinToString("、")}）")
            if (names.isNotEmpty()) {
                append("。里面的内容是：")
                append(names.take(8).joinToString("、"))
                if (names.size > 8) append(" 等 ${names.size} 项")
            }
            append("。请选到模型文件夹本身（就是里面直接有 model.int8.onnx 的那一层）")
        }
    }
}