# -*- coding: utf-8 -*-
"""
验证正文清洗的两块效果（真机）：
  A. EPUB —— 结构噪声（URL/邮箱/引文编号/ISBN）是否已从正文消失，标题空格是否还在
  B. PDF  —— 目录页内容是否不再被当正文念出来
"""
import re
import subprocess
import time

ADB = r'D:/Android/Sdk/platform-tools/adb.exe'
APP_DIR = '/sdcard/Android/data/top.readaloud.app/files'


def run(a):
    r = subprocess.run(a, capture_output=True)
    return ((r.stdout or b'').decode('utf-8', 'replace') +
            (r.stderr or b'').decode('utf-8', 'replace')).strip()


def open_book(name, wait=20):
    run([ADB, 'logcat', '-c'])
    run([ADB, 'shell', 'am', 'start', '-S', '-n', 'top.readaloud.app/.MainActivity',
         '--es', 'openfile', f'file://{APP_DIR}/{name}'])
    time.sleep(wait)


def collect_text(swipes=10):
    """滚动收集界面上的所有文字"""
    acc = []
    for _ in range(swipes):
        run([ADB, 'shell', 'uiautomator', 'dump', '/sdcard/ui.xml'])
        xml = run([ADB, 'shell', 'cat', '/sdcard/ui.xml'])
        for t in re.findall(r'text="([^"]*)"', xml):
            if t.strip() and t not in acc:
                acc.append(t)
        run([ADB, 'shell', 'input', 'swipe', '540', '1600', '540', '600', '260'])
        time.sleep(0.4)
    return '\n'.join(acc)


print('=' * 70)
print('A. EPUB：结构噪声是否清干净')
print('=' * 70)
run([ADB, 'shell', 'cp', f'/sdcard/Download/ReadaloudSamples/星尘信使-EPUB3.epub',
     f'{APP_DIR}/星尘信使-EPUB3.epub'])
open_book('星尘信使-EPUB3.epub', 12)
full = collect_text(10)
print('  文字节点收集完毕，逐项检查：')
for probe, want_present in [
    ('http', False), ('example.org', False), ('lin.shen', False),
    ('ISBN', False), ('978-7', False), ('[12]', False), ('[13-15]', False),
    ('第一章 雨夜来客', True),          # 标题里的空格要留着
    ('别去找它', True),                  # 正文内容不能误删
]:
    got = probe in full
    flag = 'OK ' if got == want_present else '!! '
    print(f'  {flag}{probe!r:18} 出现={got}  期望={want_present}')

print()
print('=' * 70)
print('B. PDF：目录页是否不再被念')
print('=' * 70)
open_book('合规报告-无书签有目录页.pdf', 22)
log = run([ADB, 'logcat', '-d', '-s', 'ReadaloudPdf', 'ReadaloudBook', 'ReadaloudPlay'])
for line in log.splitlines():
    if any(k in line for k in ('剔除目录块', '首段开头', '载入完成', '解析完成')):
        print('  ', line.split('Readaloud')[-1])
print()
print('  首段是否还是目录内容：')
first = [l for l in log.splitlines() if '首段开头' in l]
if first:
    seg = first[-1].split('首段开头：')[-1]
    print(f'    首段 = {seg!r}')
    print('    ' + ('!! 仍然是目录页内容' if '目 录' in seg or '……' in seg or '…' in seg
                    else 'OK 不是目录页内容了'))
