# 阅听 Readaloud

完全离线的 Android 听书 App。导入本地的 EPUB / PDF / TXT，用**装在 App 里的**本地
神经网络语音引擎按章节朗读，**全程不联网**。

> 📱 **包名**：`top.readaloud.app` · **最低系统**：Android 8.0（API 26）
> 📜 **许可**：Apache License 2.0（详见下方「许可证」）

---

## 为什么做这个

听书 App 普遍要求你把书上传到服务器——这在两个场景下不可接受：
**涉密资料**和**版权未公开的稿件**。

本 App 的第一原则是**绝不联网**，并且它不只是一个"承诺"：

- AndroidManifest 里**刻意不声明 `INTERNET` 权限**，系统层面就禁止创建网络套接字；
- 语音合成引擎（sherpa-onnx + Kokoro-82M）**随 App 一起装在手机上**，合成完全在 CPU 上完成；
- 任何人可以用 `apkanalyzer manifest permissions` 验证这一点（下面的「如何验证」有命令）。

副作用是它更省电：没有网络轮询、没有云端请求、息屏时几乎不耗电。

---

## 特性

| | |
|---|---|
| 📖 格式 | TXT / EPUB（EPUB2、EPUB3）/ PDF（文字版） |
| 🗂 目录 | EPUB 读书内目录；PDF **三级降级**：内嵌书签 → 目录页重建 → 按字号认标题 |
| 🗣 朗读 | 离线神经网络 TTS（Kokoro-82M int8），103 个音色，默认中文女声 |
| 🔤 读音 | 正文归一化（数字/日期/百分比/单位转中文读法）、多音字纠正词典（96 条，可关闭） |
| ⏱ 控制 | 三档停顿、0.6–2.0 变速、上一句/下一句、点句即从那一句开始 |
| 💤 后台 | 锁屏继续播、通知栏控制、耳机/蓝牙线控、**睡眠定时**（按分钟 / 念完本章后停） |
| 📖 书架 | 多本书管理、进度自动保存、换书互不干扰 |
| 📑 **目录** | 独立一页竖排全书章节，完整标题、正在读的那章高亮、点一下直接跳|
| 🔖 **书签** | 正文页「收藏」一键打点，目录页可看/跳转/长按删除，重启后仍在 |
| 🎨 **正文排版 | 字体（可导入自己的 ttf/otf）、字号 1~99 滑条、四档配色（含夜间/米黄） |
| 🧩 双变体 | Full（内置模型 180MB，开箱即用）/ Lite（约 55MB，模型自备） |

---

## 安装

从 `Releases` 或项目根目录取 APK 直接安装即可（需允许"安装未知来源应用"）。

| 变体 | 包名 | 体积 | 说明 |
|---|---|---|---|
| **Full** | `top.readaloud.app` | ~180 MB | **推荐**：模型内置，装完就能用 |
| **Lite** | `top.readaloud.app.lite` | ~55 MB | 不含模型，需自行放入模型目录 |

两个变体包名不同，**可以同时装、同时用**。

### Lite 版如何导入模型

把 `kokoro-int8-multi-lang-v1_1` 的内容放到：

```
/sdcard/Android/data/top.readaloud.app.lite/files/kokoro/
```

然后重开 App，会自动导入。设置页会显示模型状态。

> Lite 版用的是 `applicationId` `top.readaloud.app.lite`，所以上面路径里的
> `.lite` 不能省。

---

## 如何验证"真的不联网"

```bash
JAVA_HOME="D:/dev/studio/jbr" \
"D:/Android/Sdk/cmdline-tools/latest/bin/apkanalyzer.bat" \
  manifest permissions 阅听-v0.5.0-release-正式签名版.apk
```

输出里应当**没有** `android.permission.INTERNET`，也没有 `ACCESS_NETWORK_STATE`。
这是操作系统级别的禁止，不是靠代码自律。

---

## 从源码编译

环境要求：Android SDK（compileSdk 37）、JDK 21+、Gradle 9.x。

```bash
cd Readaloud

# ⬇️ 第一步：下载 Kokoro 模型（约 200MB，**仓库里没有**）
#    为什么没有：model.int8.onnx 有 109MB，超过 GitHub 单文件 100MB 的硬限制。
#    脚本走国内镜像（hf-mirror.com），不需要梯子。
python tools/fetch_model.py
#    只编 Lite 变体的话可以跳过这一步（Lite 不带模型）。

# 调试包（日常开发用，不签名或用 debug 签名）
./gradlew.bat assembleFullDebug        # Full 变体（约 1 分钟）
./gradlew.bat assembleLiteDebug        # Lite 变体

# 正式包（需要签名凭据，见 docs/发布签名说明.md）
READALOUD_KEYSTORE_PROPS="D:/dev/keys/keystore.properties" \
  ./gradlew.bat assembleFullRelease    # R8 压缩，约 5~7 分钟
```

产物在 `app/build/outputs/apk/<变体>/<构建类型>/`。

> ⚠️ **AGP 9 的坑**：模型放在 `app/src/full/assets/`，必须在 `build.gradle.kts` 里显式
> 声明 `sourceSets { getByName("full") { assets.srcDir("src/full/assets") } }`，
> 否则模型不会被打进 APK（编译全绿、装机成功，只有真机合成时才报"未找到模型"）。
> 详见 `docs/发布签名说明.md` 第二节之二。

---

## 代码结构

```
app/src/main/java/top/readaloud/app/
├── MainActivity.kt       界面（Compose）：书架 / 正在听 / 设置 三个页签
├── BookReader.kt         统一读取入口：读文件→切句→认章节→分段（界面与服务共用）
├── EpubReader.kt         EPUB 解析（nav / NCX 目录，XHTML 解析）
├── PdfReader.kt          PDF 三级降级目录重建（书签 → 目录页 → 版式认标题）
├── TextFileReader.kt     读文件 + 编码探测 + 断句 + 章节识别 + 分段
├── TextCleaner.kt        结构噪声清理（URL / 邮箱 / ISBN / 引文编号）
├── TextNormalizer.kt     正文归一化（数字、日期、百分比、单位 → 中文读法）
├── PronunciationDict.kt  多音字纠正词典（96 条）
├── LineBreaks.kt         版式硬换行还原（PDF 换行会把数字劈开）
├── PlaybackService.kt    后台朗读服务（Media3 + 合成队列 + 睡眠定时）
├── KokoroTts.kt          本地离线引擎封装（模型释放 / 合成 / 读音词典）
├── BookShelf.kt          书架（轻量 JSON 存储）
├── Speaker.kt            系统 TextToSpeech 兜底
└── WavWriter.kt          采样点 → WAV
```

两个**必须遵守**的架构约定（都是踩过坑才定下来的）：

1. **读文件必须走 `BookReader.load()`**。界面和播放服务共用它，保证两边看到的是同一份
   文本与切分。曾经服务端自己读 EPUB，把 zip 二进制当文本解码，念出来全是乱码。
2. **播放位置与目录的决策权在服务端**（`PlaybackService`）。界面手上只有"队列有多长"，
   而队列在控制器重连瞬间读不到东西，会把"秒切"误判成"什么都没有"。

---

## 开发者的自检流程

改完代码不要只看编译是否通过——**出问题的特点是"编译全绿、能启动、一用就崩"**。

```bash
# 1) 备份（动大配置前必做，见 D:\dev\backups\）
# 2) 编译
# 3) 真机回归：8 项全过才算完成
python tools/regress_r8.py
```

`tools/regress_r8.py` 覆盖：启动 / 模型释放 / 引擎加载 / 音频合成 / EPUB 解析 /
PDF 三档目录重建 / 点句跳转高亮 / 全程无崩溃 / **换书后不播旧书**。

最后一项来自一个真实 bug：界面换成新书、后台还在放旧书，暂停再继续也是旧书，
还会把旧书的进度写进新书。**前 7 项全绿也没能抓到它——因为那条路径当时根本没测。**

手工自检清单（脚本覆盖不到的部分）见 `docs/发布签名说明.md` 第七节。

---

## 已知限制

- PDF 必须是**有文字层**的（能选中文字），扫描影印版无法朗读；
- EPUB 的复杂排版（脚注、多栏）按纯文本流处理，个别地方顺序可能不准；
- 中文发音依赖 Kokoro 词表，生僻字可能读错（会写进日志）；
- 合成速度约实时的 1/3（麒麟 710F 上约每段 10–20 秒），长章节首段等待较久。

---

## 许可证

本项目采用 **Apache License 2.0**，允许他人闭源商业化使用（这点比 GPL 宽松）。

```
阅听 Readaloud
Copyright 2026 LD
```

- `LICENSE` —— Apache-2.0 协议全文（从 apache.org 逐字节下载，未手抄）
- `NOTICE` —— 署名与第三方组件声明
- `docs/第三方组件与许可证.md` —— **每个依赖的许可证清单 + 一处需要你知道的 GPL 兼容性问题**

⚠️ 请务必读一下上面第三份文档：Full 变体打包的 `espeak-ng-data`（18MB）来自
GPL-3.0 的 espeak-ng 项目，与 Apache-2.0 存在兼容性考量。

我们**实测尝试过移除它**（想省 18MB 并规避该问题），结果是引擎无法创建、中文完全念不出来
—— 因为 Kokoro 用 espeak-ng 作为多语言前端，`lang = "cmn"` 只是它的一个发音库，
中文同样要经过这一层。所以只能保留 + 如实披露。**开源分发不受影响；
若你打算商业分发，建议就此点咨询专业律师。**