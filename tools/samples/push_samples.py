# -*- coding: utf-8 -*-
"""
把样例推到手机（中文文件名绕坑版）。

⚠️ 坑：adb 在 Windows 上直接 push 中文文件名会坏 ——
   实测文件名被截断成「星尘信使-EPUB」，PDF 更是直接报
   "remote couldn't create file: Is a directory"。
   多种编码尝试都无效，所以改成两步：
     1) 先用纯 ASCII 临时名推上去（这一步从来没失败过）
     2) 再用 adb shell 的 mv 改成中文名 —— 走的是 shell 命令字符串通道，
        UTF-8 中文能原样送达设备（设备本身就是 UTF-8 文件系统）
"""
import os
import subprocess

ADB = r'D:/Android/Sdk/platform-tools/adb.exe'
SAMPLES = r'D:/dev/projects/Readaloud/samples'
DEST = '/sdcard/Download/ReadaloudSamples'

# 本地文件名 → 临时 ASCII 名
FILES = [
    ('星尘信使-EPUB3.epub', 'tmp_epub3.epub'),
    ('星尘信使-EPUB2.epub', 'tmp_epub2.epub'),
    ('合规报告-带书签.pdf', 'tmp_pdf_bookmarks.pdf'),
    ('合规报告-无书签有目录页.pdf', 'tmp_pdf_tocpage.pdf'),
    ('技术方案-无书签无目录.pdf', 'tmp_pdf_plain.pdf'),
    ('扫描版样例-无文字层.pdf', 'tmp_pdf_scanned.pdf'),
]


def run(args):
    r = subprocess.run(args, capture_output=True)
    out = (r.stdout or b'').decode('utf-8', 'replace').strip()
    err = (r.stderr or b'').decode('utf-8', 'replace').strip()
    return out, err


print('--- 清空目标目录 ---')
print('rm :', run([ADB, 'shell', 'rm', '-rf', DEST])[1] or 'ok')
print('mkdir:', run([ADB, 'shell', 'mkdir', '-p', DEST])[1] or 'ok')

print('--- 第 1 步：用 ASCII 临时名推送 ---')
for local_name, tmp_name in FILES:
    src = os.path.join(SAMPLES, local_name)
    out, err = run([ADB, 'push', src, f'{DEST}/{tmp_name}'])
    print(f'  {tmp_name}: {err or out}')

print('--- 第 2 步：在设备上改成中文名 ---')
for local_name, tmp_name in FILES:
    out, err = run([ADB, 'shell', 'mv', f'{DEST}/{tmp_name}', f'{DEST}/{local_name}'])
    print(f'  {tmp_name} → {local_name}: {err or "ok"}')

print('--- 手机上的最终文件 ---')
out, _ = run([ADB, 'shell', 'ls', '-l', DEST])
print(out)
