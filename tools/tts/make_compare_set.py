# -*- coding: utf-8 -*-
"""
把同一句话做成一组可对比的版本（都存成标准 16bit/24kHz 单声道 wav，电脑上直接能放）。

为什么这么做：
    Leo 反馈"03 最自然"。03 是**带空格**那版 —— 它的停顿其实是缺陷（数字被切断），
    但他觉得好听。这说明他可能偏好"数字之间有气口"的节奏。
    所以这里把所有可能的方向都做出来，并且**把每版的停顿量出来**，
    让他对着数据和自己的耳朵一起判断：要"完全连贯"还是"有气口但不断字"。

变体（说的都是同一句话）：
    V1  年份用〇        —— 修复前的缺陷版
    V2  零、无空格      —— 本次修复后的默认
    V3  零、数字两侧留空格 —— Leo 说最自然的那版（停顿=空格造成的）
    V4  零、在"至"前加逗号 —— 新增候选：数字不断开，只在语义边界给气口
"""
import os
import sys
import wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_pauses import build_tts, gaps   # noqa: E402

import numpy as np   # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'compare2')

VARIANTS = [
    ('V1_year-O.wav',        'V1 年份用〇（修复前）',        '本次审查覆盖二〇二四年一月至二〇二四年十二月期间。'),
    ('V2_no-space.wav',      'V2 零+无空格（本次修复）',     '本次审查覆盖二零二四年一月至二零二四年十二月期间。'),
    ('V3_with-space.wav',    'V3 零+数字两侧空格',           '本次审查覆盖二零二四年 一 月至二零二四年 十二月期间。'),
    ('V4_comma-at-zhi.wav',  'V4 零+至前加逗号',             '本次审查覆盖二零二四年一月，至，二零二四年十二月期间。'),
]


def save(path, samples, sr):
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype('<i2')
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def measure(tts, text, silence_scale=0.8):
    audio = tts.generate(text, sid=3, speed=1.0) if silence_scale == 0.8 else None
    if audio is None:
        # 需要改 silence_scale 时，只能重建引擎（配置期参数）
        raise RuntimeError('unused')
    s = np.array(audio.samples, dtype=np.float32)
    g, total = gaps(s, audio.sample_rate)
    return s, audio.sample_rate, total, g


def main():
    os.makedirs(OUT, exist_ok=True)
    tts = build_tts()
    print(f'模型已加载（采样率 {tts.sample_rate}）\n')
    print('=' * 78)
    print(f'{"版本":<26}{"时长":>8}   内部停顿（毫秒）')
    print('-' * 78)
    for name, label, text in VARIANTS:
        s, sr, total, g = measure(tts, text)
        save(os.path.join(OUT, name), s, sr)
        detail = '，'.join(f'{d:.0f}' for _, d in g) or '无'
        print(f'{label:<26}{total:>7.0f}ms   {len(g)} 处：{detail}')
    print('-' * 78)
    print()
    print('文本对照（送去合成的就是这些）：')
    for _, label, text in VARIANTS:
        print(f'  {label:<26}{text}')
    print()
    print('音频目录:', OUT)
    print('说明：V4 是新增候选 —— 数字一个字都不拆，只在"至"两侧给语义气口。')


if __name__ == '__main__':
    main()
