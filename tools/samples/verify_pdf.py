# -*- coding: utf-8 -*-
"""
真机验证 PDF 抽取（三条路径各来一遍）。

⚠️ 老规矩：把样例复制进 App 自己的目录再打开 ——
   调试入口传的是 file:// 地址，而 Android 10 起应用读不了 /sdcard/Download。
"""
import subprocess
import sys
import time

ADB = r'D:/Android/Sdk/platform-tools/adb.exe'
APP_DIR = '/sdcard/Android/data/top.readaloud.app/files'
SAMPLES = '/sdcard/Download/ReadaloudSamples'

CASES = {
    'bookmarks': '合规报告-带书签.pdf',
    'tocpage': '合规报告-无书签有目录页.pdf',
    'scanned': '扫描版样例-无文字层.pdf',
}


def run(args):
    r = subprocess.run(args, capture_output=True)
    return ((r.stdout or b'').decode('utf-8', 'replace') +
            (r.stderr or b'').decode('utf-8', 'replace')).strip()


which = sys.argv[1] if len(sys.argv) > 1 else 'bookmarks'
name = CASES[which]

print('--- 复制样例到 App 目录 ---')
print(' ', run([ADB, 'shell', 'cp', f'{SAMPLES}/{name}', f'{APP_DIR}/{name}']) or 'ok')

uri = f'file://{APP_DIR}/{name}'
print('--- 打开：', name, '---')
run([ADB, 'logcat', '-c'])
print(' ', run([ADB, 'shell', 'am', 'start', '-S', '-n',
                'top.readaloud.app/.MainActivity', '--es', 'openfile', uri]))
time.sleep(25)
print('--- 日志 ---')
print(run([ADB, 'logcat', '-d', '-s', 'ReadaloudPdf', 'ReadaloudBook',
           'ReadaloudUI', 'ReadaloudPlay']))
print('--- 崩溃检查 ---')
crash = run([ADB, 'logcat', '-d', '-b', 'crash'])
bad = [l for l in crash.splitlines() if 'readaloud' in l.lower() or 'FATAL' in l]
print('  ' + ('\n  '.join(bad) if bad else '无'))
