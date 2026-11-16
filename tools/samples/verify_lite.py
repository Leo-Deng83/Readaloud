# -*- coding: utf-8 -*-
"""
用 **Lite 变体**（top.readaloud.app.lite，54.7MB）快速验证 PDF 解析逻辑。

⚠️ 为什么用 Lite 调试：
    PDF 解析（抽文本 / 重建目录）**不需要语音模型**，
    而 Full 变体 196.7MB，每次装机要等很久、还要你点确认弹窗。
    装一个 Lite 在手机上专门跑解析逻辑，迭代快得多；
    两个包名不同，可以同时装着互不干扰。

用法：python tools/samples/verify_lite.py <case>
    case = bookmarks / tocpage / plain / scanned / poly
"""
import re
import subprocess
import sys
import time

ADB = r'D:/Android/Sdk/platform-tools/adb.exe'
PKG = 'top.readaloud.app.lite'
APP_DIR = f'/sdcard/Android/data/{PKG}/files'
SAMPLES = '/sdcard/Download/ReadaloudSamples'

CASES = {
    'bookmarks': '合规报告-带书签.pdf',
    'tocpage': '合规报告-无书签有目录页.pdf',
    'plain': '技术方案-无书签无目录.pdf',
    'scanned': '扫描版样例-无文字层.pdf',
    # 多音字词典验证：这段文字覆盖 行/重/长/差 等多音字
    'poly': '样例小说.txt',
}


def run(args):
    r = subprocess.run(args, capture_output=True)
    return ((r.stdout or b'').decode('utf-8', 'replace') +
            (r.stderr or b'').decode('utf-8', 'replace')).strip()


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else 'plain'
    name = CASES[which]
    print(f'=== Lite 验证：{which}（{name}）===')
    print(' 复制样例:', run([ADB, 'shell', 'mkdir', '-p', APP_DIR]) or 'ok')
    print(' ', run([ADB, 'shell', 'cp', f'{SAMPLES}/{name}', f'{APP_DIR}/{name}']) or 'ok')

    run([ADB, 'logcat', '-c'])
    run([ADB, 'shell', 'am', 'start', '-S', '-n', f'{PKG}/.MainActivity',
         '--es', 'openfile', f'file://{APP_DIR}/{name}'])
    time.sleep(20)
    log = run([ADB, 'logcat', '-d', '-s', 'ReadaloudPdf', 'ReadaloudBook', 'ReadaloudPlay'])

    for line in log.splitlines():
        if any(k in line for k in ('内嵌书签', '印刷页码', 'L2 目录页', 'L3 版式', 'L3 候选',
                                   '定位失败', 'PDF 解析完成', '载入完成', '抽不出任何文字',
                                   '【朗读文本】', '规范化')):
            print('  ' + line.split(': ', 1)[-1])

    run([ADB, 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml'])
    xml = run([ADB, 'shell', 'cat', '/sdcard/ui.xml'])
    texts = [t for t in re.findall(r'text="([^"]*)"', xml) if t.strip()]
    chips = [t for t in texts if re.match(r'^\s*(第[一二三四五六七八九十]+[章节章]|[一二三四五六七八九十]+、|\d+\.\s*[^0-9])', t)]
    if chips:
        print(f'  界面章节条（{len(chips)}）：' + ' | '.join(chips[:10]))
    crash = run([ADB, 'logcat', '-d', '-b', 'crash'])
    bad = [l for l in crash.splitlines() if 'readaloud' in l.lower() or 'FATAL' in l]
    print('  崩溃：', '无' if not bad else '\n   '.join(bad))


if __name__ == '__main__':
    main()
