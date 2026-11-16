# -*- coding: utf-8 -*-
"""
真机验证 PDF 的**三级目录重建**（L1 书签 / L2 目录页 / L3 版式认标题）。

三份样例各跑一遍，看日志里的目录来源与章节数对不对：
  合规报告-带书签.pdf          → 期望 bookmark，10 章
  合规报告-无书签有目录页.pdf  → 期望 tocpage，10 章
  技术方案-无书签无目录.pdf    → 期望 layout，6 章（靠字号认）
  扫描版样例-无文字层.pdf      → 期望抽不出文字、给提示

⚠️ 老规矩：样例要先复制进 App 自己的目录 ——
   调试入口传的是 file:// 地址，而 Android 10 起应用读不了 /sdcard/Download。
"""
import re
import subprocess
import sys
import time

ADB = r'D:/Android/Sdk/platform-tools/adb.exe'
PKG = 'top.readaloud.app'
APP_DIR = f'/sdcard/Android/data/{PKG}/files'
SAMPLES = '/sdcard/Download/ReadaloudSamples'

CASES = [
    ('bookmarks', '合规报告-带书签.pdf', 'bookmark', 10),
    ('tocpage', '合规报告-无书签有目录页.pdf', 'tocpage', 10),
    ('plain', '技术方案-无书签无目录.pdf', 'layout', 6),
    ('scanned', '扫描版样例-无文字层.pdf', 'none', 0),
]


def run(args):
    r = subprocess.run(args, capture_output=True)
    return ((r.stdout or b'').decode('utf-8', 'replace') +
            (r.stderr or b'').decode('utf-8', 'replace')).strip()


def chapters_from_ui():
    """从界面抓章节条上的文字（LazyRow 里显示的就是章节标题）"""
    run([ADB, 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml'])
    xml = run([ADB, 'shell', 'cat', '/sdcard/ui.xml'])
    return [t for t in re.findall(r'text="([^"]*)"', xml) if t.strip()]


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    print('--- 把样例复制进 App 目录 ---')
    for _, name, _, _ in CASES:
        print(' ', name, '->', run([ADB, 'shell', 'cp', f'{SAMPLES}/{name}', f'{APP_DIR}/{name}']) or 'ok')

    all_ok = True
    for key, name, want_source, want_chapters in CASES:
        if only and only != key:
            continue
        print()
        print('=' * 70)
        print(f'【{key}】{name}   期望：目录来源={want_source} 章节≈{want_chapters}')
        run([ADB, 'logcat', '-c'])
        run([ADB, 'shell', 'am', 'start', '-S', '-n', f'{PKG}/.MainActivity',
             '--es', 'openfile', f'file://{APP_DIR}/{name}'])
        time.sleep(22)
        log = run([ADB, 'logcat', '-d', '-s', 'ReadaloudPdf', 'ReadaloudBook', 'ReadaloudUI'])

        for line in log.splitlines():
            if any(k in line for k in ('内嵌书签', 'L2 目录页', 'L3 版式', 'PDF 解析完成',
                                       '载入完成', '剔���目录块', '去掉版式硬换行',
                                       '抽不出任何文字')):
                print('  ' + line.split(': ', 1)[-1])

        ui = chapters_from_ui()
        hint = [t for t in ui if '目录' in t or '顺读' in t]
        if hint:
            print('  界面提示：' + hint[0])
        chips = [t for t in ui if re.match(r'^\s*(第[一二三四五六七八九十]+[章节]|[一二三四五六七八九十]+、|\d+\.\s)', t)]
        if chips:
            print(f'  界面章节条（{len(chips)} 个）：' + ' | '.join(chips[:8]))

        # 判定
        src_ok = f'目录来源 {want_source}' in log
        if key == 'scanned':
            ok = '抽不出任何文字' in log
            print('  判定：' + ('✅ 正确识别为扫描版' if ok else '❌ 没识别出扫描版'))
        else:
            m = re.search(r'章节 (\d+) · 段落 \d+ · 目录来源 (\w+)', log)
            got_ch, got_src = (int(m.group(1)), m.group(2)) if m else (-1, '?')
            ok = src_ok and abs(got_ch - want_chapters) <= 1
            print(f'  判定：章节 {got_ch}（期望≈{want_chapters}）、来源 {got_src}（期望 {want_source}）'
                  f' → ' + ('✅' if ok else '❌'))
        all_ok = all_ok and ok

    crash = run([ADB, 'logcat', '-d', '-b', 'crash'])
    bad = [l for l in crash.splitlines() if 'readaloud' in l.lower() or 'FATAL' in l]
    print()
    print('崩溃检查：', '无 ✅' if not bad else '\n  '.join(bad))
    print('总判定：', '全部通过 ✅' if all_ok and not bad else '有未通过项 ❌')


if __name__ == '__main__':
    main()
