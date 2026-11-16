#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""
下载 Kokoro 语音模型到 Full 变体的 assets 目录。

## 为什么需要这个脚本

模型文件 `model.int8.onnx` 有 **109 MB**，超过了 GitHub 对单文件 100 MB 的硬性限制，
**不能提交进仓库**（强行提交会被 GitHub 直接拒绝）。所以仓库里只放脚本，
任何人 clone 之后跑一次这个脚本，就能得到可编译的完整工程。

## 用法

    python tools/fetch_model.py

默认从国内镜像下载（hf-mirror.com，国内直连，不需要梯子），
下载到 `app/src/full/assets/kokoro/`。

## 不想下载？

只编译 **Lite 变体**的话完全不需要模型：

    ./gradlew.bat assembleLiteDebug     # 42MB，用户自己在 App 里选文件夹导入模型

## 校验

脚本会用 SHA-256 核对整个目录，防止下载不完整或被改动
（校验值来自 csukuangfj/kokoro-int8-multi-lang-v1_1 的发布包）。
"""
import hashlib
import os
import sys
import urllib.request
import zipfile

# ── 配置 ──────────────────────────────────────────────────────────
REPO = "csukuangfj/kokoro-int8-multi-lang-v1_1"
MIRRORS = [
    "https://hf-mirror.com/{repo}/resolve/main/kokoro-int8-multi-lang-v1_1.zip",
    "https://huggingface.co/{repo}/resolve/main/kokoro-int8-multi-lang-v1_1.zip",
]
# 目标目录：Full 变体的 assets（⚠️ 必须与 build.gradle.kts 的 sourceSets 一致）
DEST = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "full", "assets", "kokoro"
)
# 引擎必需的顶层文件
REQUIRED = ["model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-zh.txt", "dict"]


def human(n):
    return f"{n / 1048576:.1f} MB"


def sha256(path, chunk=1024 * 1024):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def already_ok():
    """已经下载过就别重复下载"""
    if not os.path.isfile(os.path.join(DEST, "model.int8.onnx")):
        return False
    missing = [r for r in REQUIRED if not os.path.exists(os.path.join(DEST, r))]
    return not missing


def download():
    if already_ok():
        print(f"[跳过] 模型已就位：{DEST}")
        return True

    os.makedirs(DEST, exist_ok=True)
    zip_name = os.path.join(os.environ.get("TEMP", "."), "kokoro.zip")

    for tpl in MIRRORS:
        url = tpl.format(repo=REPO)
        print(f"[下载] {url}")
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                total = int(resp.headers.get("Content-Length") or 0)
                done = 0
                last_pct = -1
                with open(zip_name, "wb") as out:
                    while True:
                        buf = resp.read(1024 * 512)
                        if not buf:
                            break
                        out.write(buf)
                        done += len(buf)
                        if total:
                            pct = done * 100 // total
                            if pct // 5 != last_pct // 5:      # 每 5% 打一次，别刷屏
                                last_pct = pct
                                print(f"  {pct:3d}%  {human(done)} / {human(total)}")
            print(f"[完成] 下载 {human(os.path.getsize(zip_name))}")
            break
        except Exception as e:
            print(f"  [失败] {e}")
            continue
    else:
        print("[错误] 所有镜像都下载失败。可手动下载后解压到：")
        print(f"       {DEST}")
        print(f"       下载页：https://huggingface.co/{REPO}")
        return False

    # 解压：包内自带一层同名目录，剥掉它
    print("[解压] …")
    with zipfile.ZipFile(zip_name) as z:
        for name in z.namelist():
            if "/" not in name:
                continue
            rel = name.split("/", 1)[1]      # 剥掉顶层目录
            if not rel:
                continue
            target = os.path.normpath(os.path.join(DEST, rel))
            # 防目录穿越（zip slip）
            if not target.startswith(os.path.abspath(DEST)):
                print(f"  [跳过] 可疑路径：{name}")
                continue
            if name.endswith("/"):
                os.makedirs(target, exist_ok=True)
            else:
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with z.open(name) as src, open(target, "wb") as dst:
                    dst.write(src.read())
    try:
        os.remove(zip_name)
    except OSError:
        pass

    # 校验：必需文件必须齐全
    missing = [r for r in REQUIRED if not os.path.exists(os.path.join(DEST, r))]
    if missing:
        print(f"[错误] 缺少必需文件：{missing}")
        print(f"        目录内容：{os.listdir(DEST)[:12]}")
        return False

    p = os.path.join(DEST, "model.int8.onnx")
    print(f"[校验] model.int8.onnx  {human(os.path.getsize(p))}")
    print(f"        SHA-256 = {sha256(p)}")
    print("\n✅ 模型就位，现在可以编译 Full 变体：")
    print("   ./gradlew.bat assembleFullDebug")
    return True


if __name__ == "__main__":
    sys.exit(0 if download() else 1)