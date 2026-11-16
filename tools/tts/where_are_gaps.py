# -*- coding: utf-8 -*-
"""
把 listen/ 里四版音频的停顿位置，换算成"落在第几个字之间"。

这样就能区分两种停顿：
    · 落在「二零[停]二四」—— 数字内部被切开（坏）
    · 落在「二零二四年[停]一月」—— 词与词之间换气（可以接受，甚至是节奏）
"""
import os
import sys
import wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_pauses import gaps   # noqa: E402

import numpy as np   # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
LISTEN = os.path.join(HERE, 'listen')

TEXTS = {
    '1-V1-year-O.wav': '本次审查覆盖二〇二四年一月至二〇二四年十二月期间。',
    '2-V2-no-space.wav': '本次审查覆盖二零二四年一月至二零二四年十二月期间。',
    '3-V3-with-space.wav': '本次审查覆盖二零二四年 一 月至二零二四年 十二月期间。',
    '4-V4-pause-at-zhi.wav': '本次审查覆盖二零二四年一月，至，二零二四年十二月期间。',
    # 真机实际合成（v0.2.5 装上之后）：应当与 V4 完全一致的文本
    '7-DEVICE-V4-pause.wav':
        '本次审查覆盖二零二四年一月，至，二零二四年十二月期间，公司全部业务系统所产生的个人数据与业务数据。',
}


def read(path):
    with wave.open(path, 'rb') as w:
        sr, ch, sw = w.getframerate(), w.getnchannels(), w.getsampwidth()
        frames = w.readframes(w.getnframes())
    s = np.frombuffer(frames, dtype='<i2').astype(np.float32) / 32768.0 if sw == 2 \
        else np.frombuffer(frames, dtype='<i4').astype(np.float32) / 2147483648.0
    if ch > 1:
        s = s.reshape(-1, ch).mean(axis=1)
    return s, sr


def main():
    for name, text in TEXTS.items():
        path = os.path.join(LISTEN, name)
        if not os.path.exists(path):
            print(f'{name}: 缺文件')
            continue
        s, sr = read(path)
        g, total = gaps(s, sr)
        print(f'--- {name}  （时长 {total:.0f} ms，共 {len(text)} 字）')
        for pos, dur in g:
            approx = pos / max(total, 1) * len(text)
            i = int(round(approx))
            lo, hi = max(0, i - 4), min(len(text), i + 4)
            ctx = text[lo:hi]
            print(f'      {dur:>4.0f} ms 停顿 → 大致在第 {i:>2} 字，附近内容是「{ctx}」')
        if not g:
            print('      没有内部停顿（一句到底）')
        print()


if __name__ == '__main__':
    main()
