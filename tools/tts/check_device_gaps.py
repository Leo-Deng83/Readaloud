# -*- coding: utf-8 -*-
"""
精查：手机上合成的那两段音频，停顿究竟落在哪。

判据：
    · 停顿落在**逗号**处 → 正常（模型自身的标点处理，实测 670–830ms）
    · 停顿落在**数字中间** → 没修好
本脚本按"这句话的字数"估算时间轴，把停顿位置换算成"大概第几个字"，
再把它和逗号的位置对照。
"""
import os
import re
import subprocess
import sys

ADB = r'D:/Android/Sdk/platform-tools/adb.exe'
PKG = 'top.readaloud.app'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_pauses import gaps   # noqa: E402
import numpy as np              # noqa: E402
import wave                     # noqa: E402

# 与真机日志里的一致（第 3 段是日期句，第 5 段是 ISO 编号句）
EXPECT = {
    'seg_2.wav': '本次审查覆盖二零二四年一月至二零二四年十二月期间，公司全部业务系统所产生的个人数据与业务数据。',
    'seg_4.wav': '审查期间共抽检数据接口二百三十六个，抽样比例约百分之十二点五，高于 ISO 二七零零一要求的百分之十下限。',
}


def run(args, binary=False):
    """⚠️ 二进制走 exec-out（`adb shell cat` 会破坏二进制，见 verify_device.py 的说明）"""
    cmd = [ADB, 'exec-out'] + args if binary else [ADB] + args
    r = subprocess.run(cmd, capture_output=True)
    if binary:
        return r.stdout or b''
    return ((r.stdout or b'').decode('utf-8', 'replace') +
            (r.stderr or b'').decode('utf-8', 'replace')).strip()


def pull(name):
    # ⚠️ exec-out 后面不要再带 "shell"
    raw = run(['run-as', PKG, 'cat', f'cache/tts/{name}'], binary=True)
    path = os.path.join(OUT, 'device_' + name)
    if len(raw) < 100:
        return None
    with open(path, 'wb') as f:
        f.write(raw)
    return path


def read_wav(path):
    with wave.open(path, 'rb') as w:
        sr, ch, sw = w.getframerate(), w.getnchannels(), w.getsampwidth()
        frames = w.readframes(w.getnframes())
    if sw == 2:
        s = np.frombuffer(frames, dtype='<i2').astype(np.float32) / 32768.0
    else:
        s = np.frombuffer(frames, dtype='<i4').astype(np.float32) / 2147483648.0
    if ch > 1:
        s = s.reshape(-1, ch).mean(axis=1)
    return s, sr


def main():
    os.makedirs(OUT, exist_ok=True)
    print(f'{"段":<12}{"时长":>8}  内部停顿（换算成"大约第几个字"）')
    print('-' * 68)
    for name, text in EXPECT.items():
        path = pull(name)
        if path is None:
            print(f'{name:<12} 还没合成出来（等一会儿再跑）')
            continue
        s, sr = read_wav(path)
        g, total = gaps(s, sr)
        n = len(text)
        marks = []
        for pos, dur in g:
            approx_char = pos / max(total, 1) * n
            # 这个位置附近的原文（用于判断落在标点还是数字中间）
            lo, hi = max(0, int(approx_char) - 3), min(n, int(approx_char) + 3)
            ctx = text[lo:hi]
            marks.append(f'{pos:.0f}ms/{dur:.0f}ms≈第{approx_char:.0f}字「{ctx}」')
        print(f'{name:<12}{total:>7.0f}ms  ' + ('；'.join(marks) if marks else '无'))
        # 标点位置提示
        punc = [(i + 1, c) for i, c in enumerate(text) if c in '，。、；：']
        print(f'{"":<12}{"":>8}  该句标点在第 {[p for p, _ in punc]} 字')
    print('-' * 68)
    print('判读：停顿位置与标点位置吻合 = 正常；落在数字中间 = 没修好')


if __name__ == '__main__':
    main()
