# -*- coding: utf-8 -*-
"""检查音频文件的真实格式 —— 电脑上放不出来，先看头信息对不对。"""
import glob
import os
import struct
import wave

HERE = os.path.dirname(os.path.abspath(__file__))


def info(path):
    size_kb = os.path.getsize(path) // 1024
    with open(path, 'rb') as f:
        head = f.read(64)
    riff = head[0:4]
    declared = struct.unpack('<I', head[4:8])[0]
    waveid = head[8:12]
    chunk = head[12:16]
    audio_fmt, ch, sr, br, ba, bits = struct.unpack('<HHIIHH', head[20:36])
    print(f'{os.path.basename(path):36} {size_kb:>5} KB')
    print(f'{"":36} 头={riff}/{waveid}/{chunk} 声明长度={declared} '
          f'格式号={audio_fmt}(1=PCM 3=float) 声道={ch} 采样率={sr} 位宽={bits}')
    try:
        with wave.open(path, 'rb') as w:
            frames = w.getnframes()
            print(f'{"":36} 可读: {frames} 帧 / {frames / w.getframerate():.2f} 秒')
    except Exception as e:
        print(f'{"":36} !! 标准 wav 模块读不了: {e}')
    # 采样几个点看看幅度（判断是不是爆音）
    if bits == 16:
        with open(path, 'rb') as f:
            f.seek(44)
            data = f.read(48000)
        vals = struct.unpack('<%dh' % (len(data) // 2), data)
        peak = max(abs(v) for v in vals) if vals else 0
        print(f'{"":36} 前 1 秒峰值 = {peak} (32767 = 满刻度，满刻度=爆音)')


for p in sorted(glob.glob(os.path.join(HERE, 'compare', '*.wav'))):
    info(p)
    print()
