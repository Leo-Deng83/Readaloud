# -*- coding: utf-8 -*-
"""
从手机拉合成音频 —— 必须用 `adb exec-out`。

⚠️ 踩过的坑：`adb shell cat 文件` 在 Windows 上**会破坏二进制数据**
   （shell 通道会做换行/编码转换），拉回来的 wav 每个采样都是满刻度，
   听起来就是"爆音、没有内容"。用 `exec-out`（专门给二进制用的通道）才对。
"""
import os
import struct
import subprocess
import sys

ADB = r'D:/Android/Sdk/platform-tools/adb.exe'
PKG = 'top.readaloud.app'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'compare2')


def exec_out(args):
    """二进制安全的执行（exec-out 不走 shell 通道）"""
    r = subprocess.run([ADB, 'exec-out'] + args, capture_output=True)
    return r.stdout or b''


def peak_of(path):
    with open(path, 'rb') as f:
        head = f.read(44)
        if head[:4] != b'RIFF':
            return None
    bits = struct.unpack('<H', head[34:36])[0]
    with open(path, 'rb') as f:
        f.seek(44)
        data = f.read(24000 * 2)
    if bits == 16:
        vals = struct.unpack('<%dh' % (len(data) // 2), data)
        return max(abs(v) for v in vals)
    return -1


def main():
    os.makedirs(OUT, exist_ok=True)
    listing = subprocess.run(
        [ADB, 'shell', 'run-as', PKG, 'ls', 'cache/tts'],
        capture_output=True).stdout.decode('utf-8', 'replace')
    segs = sorted({s for s in listing.split() if s.startswith('seg_')},
                  key=lambda s: int(s.split('_')[1].split('.')[0]))
    print('手机上已合成的段:', ' '.join(segs) or '（还没有）')

    for name in segs[:5]:
        raw = exec_out(['run-as', PKG, 'cat', f'cache/tts/{name}'])
        if len(raw) < 100:
            print(f'  {name}: 拉取失败')
            continue
        dst = os.path.join(OUT, 'device_' + name)
        with open(dst, 'wb') as f:
            f.write(raw)
        pk = peak_of(dst)
        print(f'  {name} -> {os.path.basename(dst)}  {len(raw)//1024} KB  '
              f'前1秒峰值={pk}  {"(正常)" if pk and pk < 30000 else "(!! 爆音)"}')
    print()
    print('目录:', OUT)


if __name__ == '__main__':
    main()
