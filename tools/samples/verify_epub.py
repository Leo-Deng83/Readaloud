# -*- coding: utf-8 -*-
"""
真机验证 EPUB 解析：把样例复制进 App 目录，用调试入口打开，抓解析日志。

为什么先复制到 App 自己的目录：
  调试入口传的是 file:// 地址，而 Android 10 起应用不能直接读 /sdcard/Download ——
  但 App 自己的外部目录（/sdcard/Android/data/<包名>/files）是可以读的。
  （真实用户走的是系统文件选择器，那条路不受这个限制。）
"""
import subprocess
import sys
import time

ADB = r'D:/Android/Sdk/platform-tools/adb.exe'
APP_DIR = '/sdcard/Android/data/top.readaloud.app/files'
SAMPLES = '/sdcard/Download/ReadaloudSamples'


def run(args):
    r = subprocess.run(args, capture_output=True)
    return ((r.stdout or b'').decode('utf-8', 'replace') +
            (r.stderr or b'').decode('utf-8', 'replace')).strip()


def copy_samples():
    for n in ['星尘信使-EPUB3.epub', '星尘信使-EPUB2.epub']:
        print('  复制', n, ':', run([ADB, 'shell', 'cp', f'{SAMPLES}/{n}', f'{APP_DIR}/{n}']) or 'ok')


def open_book(name, wait=8):
    uri = f'file://{APP_DIR}/{name}'
    print('  打开:', uri)
    run([ADB, 'logcat', '-c'])
    print(' ', run([ADB, 'shell', 'am', 'start', '-S', '-n',
                    'top.readaloud.app/.MainActivity', '--es', 'openfile', uri]))
    time.sleep(wait)
    print('  --- 解析日志 ---')
    print(run([ADB, 'logcat', '-d', '-s', 'ReadaloudBook', 'ReadaloudEpub',
               'ReadaloudUI', 'ReadaloudPlay']))
    print('  --- 崩溃检查 ---')
    crash = run([ADB, 'logcat', '-d', '-b', 'crash'])
    print('   ', '\n    '.join(l for l in crash.splitlines() if 'readaloud' in l.lower() or 'FATAL' in l) or '无')


if __name__ == '__main__':
    which = sys.argv[1] if len(sys.argv) > 1 else 'epub3'
    print('--- 复制样例到 App 目录 ---')
    copy_samples()
    name = '星尘信使-EPUB3.epub' if which == 'epub3' else '星尘信使-EPUB2.epub'
    print(f'--- 打开 {which} ---')
    open_book(name)
