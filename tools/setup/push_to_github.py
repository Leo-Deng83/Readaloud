#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""
把本地 git 仓库通过 **GitHub REST API** 推上去。

## 为什么要写这个脚本（而不是 git push）

本机环境里 `github.com` 的 git 协议被代理挡住（CONNECT tunnel failed 502），
但 `api.github.com` 是通的（实测 HTTP 200）。
GitHub 的 Git Data API 可以完成等价于 push 的操作：
    上传 blob → 建 tree → 建 commit → 更新 refs/heads/main

## 令牌放哪

**仓库外**：`D:\dev\keys\github_token.txt`，只写令牌一行。
令牌绝不写进本脚本、README 或任何被 git 跟踪的文件。

## 用法

    python tools/setup/push_to_github.py

加 `--dry-run` 只检查不推送：

    python tools/setup/push_to_github.py --dry-run
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

TOKEN_FILE = r"D:\dev\keys\github_token.txt"
OWNER = "Leo-Deng83"
REPO = "Readaloud"
BRANCH = "main"
API = "https://api.github.com"
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def api(path, token, method="GET", payload=None, retries=3):
    """调 GitHub API；自动带 token，失败重试（网络抖动很常见）"""
    url = API + path
    data = None
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "Readaloud-Push-Script",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"

    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, data=data, headers=headers, method=method)
            with urllib.request.urlopen(req, timeout=90) as r:
                raw = r.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "ignore")
            # 401/403/404 重试也没用，直接报清楚
            if e.code in (401, 403, 404):
                raise RuntimeError(f"GitHub API {e.code} {path}: {body[:300]}") from e
            last = e
        except Exception as e:
            last = e
        if attempt < retries - 1:
            time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"GitHub API 失败 {method} {path}: {last}")


def tracked_files():
    """从 git 索引里取文件清单 + 权限（100644 / 100755 / 120000）"""
    # ⚠️ 必须加 -c core.quotePath=false：
    #    git 默认把中文文件名输出成八进制转义（"samples/\346\211\253..."），
    #    直接拿来当路径会找不到文件 → 静默跳过中文文件（实测跳过了 6 个测试样例）。
    out = subprocess.run(
        ["git", "-c", "core.quotePath=false", "ls-files", "-s"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=True,
        encoding="utf-8",
    ).stdout
    files = []
    for line in out.splitlines():
        parts = line.split(None, 3)
        if len(parts) != 4:
            continue
        mode, _sha, stage, path = parts
        files.append((path, mode.strip(), stage.strip()))
    return files


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="只检查，不推送")
    args = ap.parse_args()

    # 预演不需要令牌：先让人看清"到底会推什么"
    if args.dry_run:
        files = tracked_files()
        total = 0
        for p, _, _ in files:
            f = os.path.join(REPO_ROOT, p)
            if os.path.isfile(f):
                total += os.path.getsize(f)
        print(f"[预演] {len(files)} 个文件 / {total/1048576:.1f} MB")
        print(f"       目标：{OWNER}/{REPO}@{BRANCH}")
        print(f"       令牌文件存在：{os.path.isfile(TOKEN_FILE)}")
        return 0

    if not os.path.isfile(TOKEN_FILE):
        print(f"[错误] 找不到令牌文件：{TOKEN_FILE}")
        print("       只写令牌一行，放在仓库外面。")
        return 1

    token = open(TOKEN_FILE, encoding="utf-8", errors="ignore").read().strip()
    if not (token.startswith("ghp_") or token.startswith("github_pat_")):
        print("[错误] 令牌格式不对（应以 ghp_ 或 github_pat_ 开头）")
        return 1

    # ① 先验证令牌有效
    me = api("/user", token)
    print(f"[认证] {me.get('login')}（{me.get('name') or '未设置姓名'}）")

    # ② 取仓库现状（找到当前 HEAD，作为新 commit 的父提交）
    repo = api(f"/repos/{OWNER}/{REPO}", token)
    print(f"[仓库] {repo['full_name']}（{'私有' if repo['private'] else '公开'}）")
    parent = None
    try:
        ref = api(f"/repos/{OWNER}/{REPO}/git/ref/heads/{BRANCH}", token)
        parent = ref["object"]["sha"]
        print(f"[分支] {BRANCH} 现有 HEAD：{parent[:8]}")
    except RuntimeError as e:
        if "404" in str(e):
            print(f"[分支] {BRANCH} 不存在，将新建")
        else:
            raise

    # ③ 逐个上传 blob
    files = tracked_files()
    tree = []
    for i, (path, mode, stage) in enumerate(files, 1):
        full = os.path.join(REPO_ROOT, path)
        if stage != "0":
            continue  # 只推 stage 0（正常文件），跳过冲突中的条目
        if not os.path.isfile(full):
            print(f"  [{i}/{len(files)}] 跳过（不是普通文件）：{path}")
            continue
        with open(full, "rb") as f:
            raw = f.read()
        blob = api(
            f"/repos/{OWNER}/{REPO}/git/blobs", token, "POST",
            {"content": base64.b64encode(raw).decode("ascii"), "encoding": "base64"},
        )
        # git 的 100755 = 可执行文件（比如 gradlew）
        git_mode = "100755" if mode == "100755" else "100644"
        tree.append({
            "path": path,
            "mode": git_mode,
            "type": "blob",
            "sha": blob["sha"],
        })
        if i % 20 == 0 or i == len(files):
            print(f"  [{i}/{len(files)}] 已上传 {i} 个文件")

    # ④ 建 tree（base_tree 留空 = 全新树；已有仓库会整体替换内容）
    new_tree = api(
        f"/repos/{OWNER}/{REPO}/git/trees", token, "POST",
        {"tree": tree},
    )
    print(f"[树] {len(tree)} 个条目，sha={new_tree['sha'][:8]}")

    # ⑤ 建 commit
    commit_payload = {
        "message": "阅听 Readaloud v0.5.0：首个功能完整版\n\n目录页 / 书签 / 正文排版；完全离线，无 INTERNET 权限。",
        "tree": new_tree["sha"],
    }
    if parent:
        commit_payload["parents"] = [parent]
    commit = api(f"/repos/{OWNER}/{REPO}/git/commits", token, "POST", commit_payload)
    print(f"[提交] {commit['sha'][:8]}")

    # ⑥ 更新分支引用
    if parent:
        api(
            f"/repos/{OWNER}/{REPO}/git/refs/heads/{BRANCH}", token, "PATCH",
            {"sha": commit["sha"], "force": False},
        )
    else:
        api(
            f"/repos/{OWNER}/{REPO}/git/refs", token, "POST",
            {"ref": f"refs/heads/{BRANCH}", "sha": commit["sha"]},
        )

    print(f"\n✅ 推送完成：https://github.com/{OWNER}/{REPO}")
    return 0


if __name__ == "__main__":
    sys.exit(main())