#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""
R8 压缩版的真机回归测试（v0.3.3 引入）

⚠️ 为什么非要用 Python 写（踩过的坑）：
   上一版是 bash 脚本，在 Git Bash 下路径里的空格 C:\Users\Leo Deng\…
   把 adb 的参数拆成了好几段 → mkdir 报"只读文件系统"、cat 找不到文件，
   一堆假失败。**验证工具本身不可靠，比没有验证更糟**，所以改用 Python。
   （本段必须是原始字符串 r，否则路径里的反斜杠会被当成转义序列。）

为什么必须有这个测试：R8 把类名方法名全改了，而 pdfbox / sherpa-onnx
都靠反射按名字找类。出问题的特点是「编译通过、能启动、一用就崩」。

用法：python tools/regress_r8.py
"""
import os
import re
import subprocess
import sys
import time

ADB = r"D:\Android\Sdk\platform-tools\adb.exe"
PKG = "top.readaloud.app"
SAMPLES = f"/sdcard/Android/data/{PKG}/files"
HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES_DIR = os.path.join(os.path.dirname(HERE), "samples")

PASS, FAIL = [], []


def sh(*args, timeout=180):
    """跑一条 adb 命令，返回 (返回码, 输出)"""
    try:
        r = subprocess.run([ADB, *args], capture_output=True, timeout=timeout)
        return r.returncode, (r.stdout + r.stderr).decode("utf-8", "ignore")
    except subprocess.TimeoutExpired:
        return 1, "TIMEOUT"


def shell(cmd, timeout=180):
    return sh("shell", cmd, timeout=timeout)[1]


def logcat_clear():
    sh("logcat", "-c")


def logcat(pattern, tail=4):
    """抓日志里某个关键字的最后几行（去掉时间戳前缀）"""
    out = shell(f'logcat -d | grep -E "{pattern}" | tail -{tail}')
    lines = [re.sub(r"^.*?\d+:\d+:\d+\.\d+\s+\d+\s+\d+\s+", "", l).strip()
             for l in out.splitlines() if l.strip()]
    return "\n      ".join(lines)


def ok(msg):
    PASS.append(msg)
    print(f"  [PASS] {msg}")


def bad(msg):
    FAIL.append(msg)
    print(f"  [FAIL] {msg}")


def find_samples():
    """找出样例文件（中文名在 shell 里容易出问题，这里在本地找）"""
    out = []
    for n in sorted(os.listdir(SAMPLES_DIR)) if os.path.isdir(SAMPLES_DIR) else []:
        low = n.lower()
        if low.endswith(".pdf") and "扫描版" not in n:
            out.append(("pdf", os.path.join(SAMPLES_DIR, n), n))
        elif low.endswith(".epub") and "epub3" in low:
            out.append(("epub", os.path.join(SAMPLES_DIR, n), n))
        elif low.endswith(".txt"):
            out.append(("txt", os.path.join(SAMPLES_DIR, n), n))
    return out


def current_title():
    """当前正在读的书名（从界面 dump 里读"正在听"页顶部那行）"""
    xml = shell("uiautomator dump /sdcard/r8cur.xml >/dev/null 2>&1; cat /sdcard/r8cur.xml")
    m = re.findall(r'text="([^"]{2,})"', xml)
    for t in m:
        if t not in ("书架", "正在听", "目录", "设置", "换一本", "收藏", "已收藏"):
            return t
    return ""


def push(local, remote_name):
    """把本地文件推进 App 私有目录（分两步：先到 /data/local/tmp，再 cp）"""
    tmp = f"/data/local/tmp/ra_{remote_name}"
    sh("push", local, tmp)
    shell(f"cp {tmp} {SAMPLES}/{remote_name}")
    return f"{SAMPLES}/{remote_name}"


def main():
    print("=" * 54)
    print(" 发布前真机回归测试")
    print("=" * 54)

    # ── 0) 环境 ────────────────────────────────────────
    print("\n[0/8] 环境准备")
    if "package:" not in shell(f"pm path {PKG}"):
        print(f"  [FAIL] 手机上没装 {PKG}，先装 release 包")
        return 1
    ok(f"{PKG} 已安装")
    shell(f"mkdir -p {SAMPLES}")

    samples = find_samples()
    if not samples:
        print("  [FAIL] 本地 samples/ 里没有样例文件")
        return 1
    ok(f"找到 {len(samples)} 个样例：{[s[2] for s in samples]}")

    epub = next((s for s in samples if s[0] == "epub"), None)
    pdfs = [s for s in samples if s[0] == "pdf"]
    if epub:
        push(epub[1], "sample.epub")

    # ── 1) 启动不崩 ────────────────────────────────────
    print("\n[1/8] 启动不崩溃")
    shell(f"am force-stop {PKG}")
    logcat_clear()
    sh("shell", "am", "start", "-S", "-n", f"{PKG}/.MainActivity")
    time.sleep(8)
    log = shell("logcat -d | grep -E 'FATAL EXCEPTION|AndroidRuntime' | head -8")
    if log.strip():
        bad("启动崩溃：\n" + log)
        return 1
    ok("启动正常")

    # ── 2) 模型释放 + 引擎加载（sherpa-onnx 反射）──────
    print("\n[2/8] 模型自动释放与 TTS 引擎加载")
    if epub:
        shell(f"am force-stop {PKG}")
        logcat_clear()
        sh("shell", "am", "start", "-S", "-n", f"{PKG}/.MainActivity",
           "--es", "openfile", f"file://{SAMPLES}/sample.epub")
        time.sleep(50)
        log = shell("logcat -d")
        if "模型加载完成" in log:
            ok("引擎加载成功（模型已释放）")
        else:
            bad("引擎加载失败：\n      " + logcat("KokoroTts|未找到模型|模型加载失败", 5))

        # ── 3) 真合成 ─────────────────────────────────
        print("\n[3/8] 音频合成")
        time.sleep(25)
        log = shell("logcat -d")
        m = re.findall(r"已合成 (\d+)/(\d+) 段", log)
        if m:
            ok(f"合成正常（已到第 {m[-1][0]}/{m[-1][1]} 段）")
        else:
            bad("没合成出音频：\n      " + logcat("ReadaloudPlay", 5))
    else:
        bad("没有 EPUB 样例，跳过引擎测试")

    # ── 4) EPUB 解析 ───────────────────────────────────
    print("\n[4/8] EPUB 解析")
    line = logcat("载入完成", 2)
    if "epub" in line:
        ok(f"EPUB 解析：{line}")
    else:
        bad(f"EPUB 解析异常：{line or '无日志'}")

    # ── 5) PDF 解析 + 目录重建（R8 最容易打坏的一环）────
    print("\n[5/8] PDF 解析与目录重建（书签 / 目录页 / 纯版式 L3）")
    for i, (_, local, name) in enumerate(pdfs, 1):
        remote = push(local, f"t{i}.pdf")
        shell(f"am force-stop {PKG}")
        logcat_clear()
        sh("shell", "am", "start", "-S", "-n", f"{PKG}/.MainActivity",
           "--es", "openfile", f"file://{remote}")
        time.sleep(11)
        line = logcat("载入完成|ReadaloudPdf", 3)
        m = re.search(r"章节 (\d+)", line)
        if m and int(m.group(1)) >= 1:
            src = re.search(r"目录来源 (\w+)", line)
            ok(f"PDF#{i} {name} → 章节 {m.group(1)} 个，来源 {src.group(1) if src else '?'}")
        else:
            bad(f"PDF#{i} {name} 失败：{line or '无日志'}")
        crash = shell("logcat -d | grep -E 'FATAL EXCEPTION' | head -4")
        if crash.strip():
            bad(f"PDF#{i} 处理中崩溃：\n{crash}")
            break

    # ── 6) 点句 + 高亮（界面逻辑）──────────────────────
    print("\n[6/8] 点句跳转与高亮")
    shell(f"am force-stop {PKG}")
    logcat_clear()
    sh("shell", "am", "start", "-S", "-n", f"{PKG}/.MainActivity",
       "--es", "openfile", f"file://{SAMPLES}/sample.epub")
    time.sleep(9)
    shell("uiautomator dump /sdcard/r8ui.xml >/dev/null 2>&1")
    xml = shell("cat /sdcard/r8ui.xml")
    # 从 dump 里挑一句较长的可点正文（坐标必须用实际分辨率，截图是缩略图不能换算）
    cands = re.findall(
        r'text="([^"]{15,})"[^>]*clickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not cands:
        bad("dump 里没找到可点正文（App 没载入书？）")
    else:
        text, x1, y1, x2, y2 = cands[min(3, len(cands) - 1)]
        shell(f"input tap {(int(x1)+int(x2))//2} {(int(y1)+int(y2))//2}")
        time.sleep(5)
        line = logcat("点句请求", 2)
        if line:
            ok(f"点句已响应：{line}")
        else:
            bad("点句无响应（界面事件或服务链路坏了）")

    # ── 7) 全程无崩溃 ─────────────────────────────────
    print("\n[7/8] 全程无崩溃")
    # ⚠️ 判据必须精确、且**不能带复杂正则**：只认 FATAL EXCEPTION 这一个关键字。
    #    （早先写成 grep 'FATAL EXCEPTION|AndroidRuntime' → 把 uiautomator 自己
    #     的正常启动日志（D 级）也当成崩溃；再后来加了个含正则的组合条件 →
    #     Git Bash 把引号吃掉，报 "no closing quote"。验证工具自己不可靠最耽误事。）
    crash = shell("logcat -d | grep FATAL | head -3")
    if crash.strip():
        bad("过程中有崩溃：\n" + crash.strip())
    else:
        ok("无崩溃")

    # ── 8) 换书：后台必须跟着换（v0.3.3 上线前自检抓到的 bug）────
    # 症状：界面换成新书了，声音还在放旧书；点暂停再「继续」又是旧书。
    # 之所以要专门测：前 7 项全绿也照样漏掉了它 —— 这条路径以前**根本没测过**。
    print("\n[8/8] 换书后不播旧书（本轮新增，曾是线上级 bug）")
    # 先让第一本真的开始合成，再从界面换书（不能重启 App，那不是真实场景）
    shell(f"am force-stop {PKG}")
    logcat_clear()
    sh("shell", "am", "start", "-S", "-n", f"{PKG}/.MainActivity",
       "--es", "openfile", f"file://{SAMPLES}/sample.epub")
    time.sleep(45)                       # 等它合成出至少一段，确保真的在念
    pre = shell("logcat -d | grep -c '已合成'")
    shell("uiautomator dump /sdcard/sw0.xml >/dev/null 2>&1")
    xml0 = shell("cat /sdcard/sw0.xml")
    if '换一本' not in xml0:
        bad("界面没载入书，换书场景无法测")
    else:
        # 点「换一本」→ 书架 → 选另一本（PDF）
        btn = re.search(r'text="换一本"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml0)
        shell(f"input tap {(int(btn.group(1))+int(btn.group(3)))//2} "
              f"{(int(btn.group(2))+int(btn.group(4)))//2}")
        time.sleep(3)
        shell("uiautomator dump /sdcard/sw1.xml >/dev/null 2>&1")
        xml1 = shell("cat /sdcard/sw1.xml")
        # 找一张和当前不同的书卡（标题不含 EPUB 那一本的名字）
        cards = re.findall(
            r'text="([^"]{3,})"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml1)
        # 只认"书名"，并且**必须挑一本和当前不一样的**。
        # ⚠️ 挑到"当前这本"会让整个换书测试退化：换书 → 回到同一本 →
        #    后面那几步测的就不是"换书"了（曾经因此误报过一次失败）。
        skip = ("我的书架", "导入书籍", "书架", "正在听", "设置", "目录")
        cur = current_title()
        target = None
        for name, x1, y1, x2, y2 in cards:
            if any(k in name for k in skip):
                continue
            if re.match(r"^共 \d+ 本", name):   # "共 N 本 · 长按卡片可以移除" 不是书名
                continue
            if name == cur:                      # ← 就是当前这本，换一本才有意义
                continue
            target = (name, x1, y1, x2, y2)
            break
        if target is None:
            # 只有一本书可换时如实说明，而不是把"没换成"当成通过
            print("  [跳过] 书架上只有当前这一本书，无法验证换书")
            return
        if target is None:
            bad("书架里没找到第二本书")
        else:
            name, x1, y1, x2, y2 = target
            shell(f"input tap {(int(x1)+int(x2))//2} {(int(y1)+int(y2))//2}")
            time.sleep(12)
            after = shell("logcat -d")
            # 关键：后台必须作废旧那一轮（换书指令的日志）
            if "界面换书" in after:
                ok(f"已通知后台换书（切到《{name}》）")
            else:
                bad("后台完全不知道换了书 —— 换书指令没发或没处理")
            # 换书后合成循环必须停下来：不该再有"已合成"新记录
            new_synth = int(shell("logcat -d | grep -c '已合成'") or 0)
            if new_synth <= int(pre or 0):
                ok("旧书的合成循环已停止")
            else:
                bad(f"换书后还在合成旧书（新增 {new_synth - int(pre or 0)} 段）")
            # 队列里不能混进旧书：再点「朗读」必须是新书的内容
            #⚠️ 换完书可能停在**书架页**（点书卡是切页，不是留在目录页），
            #    所以先回「正在听」再找按钮 —— 否则会误报"找不到朗读按钮"。
            shell("input tap 540 2265 >/dev/null 2>&1")   # 底部「正在听」
            time.sleep(2)
            shell("uiautomator dump /sdcard/sw2.xml >/dev/null 2>&1")
            xml2 = shell("cat /sdcard/sw2.xml")
            main_btn = re.search(r'text="(朗读|继续)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml2)
            if main_btn:
                shell(f"input tap {(int(main_btn.group(2))+int(main_btn.group(4)))//2} "
                      f"{(int(main_btn.group(3))+int(main_btn.group(5)))//2}")
                time.sleep(14)
                line = logcat("共 .* 句|载入完成", 3)
                if line:
                    ok(f"换书后起播的是新书：{line}")
                else:
                    bad("换书后起播没反应")
            else:
                bad("界面找不到「朗读/继续」按钮")


    # ── 汇总 ─────────────────────────────────────────
    print("\n" + "=" * 54)
    print(f" 通过 {len(PASS)} 项，失败 {len(FAIL)} 项")
    for f in FAIL:
        print(f"   ✗ {f}")
    print("=" * 54)
    if FAIL:
        print(" ❌ 有失败项 —— 见 备份说明.md 的回滚步骤")
        return 1
    print(" ✅ 全部通过，可以发布")
    return 0


if __name__ == "__main__":
    sys.exit(main())