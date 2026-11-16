# -*- coding: utf-8 -*-
"""
本机 TTS 验证台 —— 用同一套 Kokoro 模型在电脑上合成，然后**分析波形里的停顿**。

为什么需要它：
    听感问题以前只能靠用户戴耳机听，改一次要装一次包、问一次人。
    现在把模型搬到电脑上跑，同一句话的不同写法各合成一遍，
    用波形把"哪里断了、断多久"测量出来 —— 客观、可复现、不用等耳朵。

用法：
    python tools/tts/check_pauses.py            # 跑内置的对比用例
    python tools/tts/check_pauses.py "要测的文本"

输出：
    每句给出 总时长 / 内部停顿的个数与位置（毫秒）/ 音素诊断
"""
import os
import sys
import wave

import numpy as np

MODEL_DIR = r'D:/dev/models/kokoro-int8-multi-lang-v1_1'
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')

SPEAKER = 3          # zf_001，与 App 默认一致
SILENCE_SCALE = 0.8  # 与 App 的"标准"档一致

# 判定静音的参数（与 App 里测句子边界的算法同源）
FRAME_MS = 10
SILENCE_RATIO = 0.015   # 峰值 1.5% 以下算静音
MIN_GAP_MS = 60         # 短于这个不算"停顿"


def build_tts():
    import sherpa_onnx
    d = MODEL_DIR
    config = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                model=os.path.join(d, 'model.int8.onnx'),
                voices=os.path.join(d, 'voices.bin'),
                tokens=os.path.join(d, 'tokens.txt'),
                data_dir=os.path.join(d, 'espeak-ng-data'),
                dict_dir=os.path.join(d, 'dict'),
                lexicon=os.path.join(d, 'lexicon-zh.txt'),
                lang='cmn',
                length_scale=1.0,
            ),
            num_threads=4,
            debug=False,
            provider='cpu',
        ),
        rule_fsts='',
        max_num_sentences=1,
        silence_scale=SILENCE_SCALE,
    )
    return sherpa_onnx.OfflineTts(config)


def gaps(samples, sample_rate):
    """找出内部停顿：(起点ms, 时长ms) 列表；首尾的静音不算"""
    n = len(samples)
    if n == 0:
        return [], 0.0
    step = int(sample_rate * FRAME_MS / 1000)
    frames = n // step
    rms = np.array([np.sqrt(np.mean(np.square(samples[i * step:(i + 1) * step])))
                    for i in range(frames)]) if frames else np.array([])
    if rms.size == 0:
        return [], 0.0
    peak = float(rms.max())
    thr = peak * SILENCE_RATIO

    runs = []
    start = None
    for i, v in enumerate(rms):
        quiet = v < thr
        if quiet and start is None:
            start = i
        elif not quiet and start is not None:
            runs.append((start, i))
            start = None
    if start is not None:
        runs.append((start, frames))

    out = []
    for a, b in runs:
        dur = (b - a) * FRAME_MS
        # 掐掉首尾静音；中间够长的才算"停顿"
        if a == 0 or b >= frames:
            continue
        if dur >= MIN_GAP_MS:
            out.append((a * FRAME_MS, dur))
    return out, n / sample_rate * 1000


def synth_and_report(tts, text, tag, save=True):
    audio = tts.generate(text, sid=SPEAKER, speed=1.0)
    s = np.array(audio.samples, dtype=np.float32)
    sr = audio.sample_rate
    g, total = gaps(s, sr)
    print(f'--- {tag} ---')
    print(f'  文本: {text}')
    print(f'  时长: {total:.0f} ms   内部停顿: {len(g)} 处')
    for pos, dur in g:
        print(f'       在 {pos:>6.0f} ms 处停了 {dur:.0f} ms')
    if save:
        os.makedirs(OUT_DIR, exist_ok=True)
        # 用文本的哈希做文件名，避免中文路径/重名问题
        name = f'{tag}_{abs(hash(text)) % 100000}.wav'
        path = os.path.join(OUT_DIR, name)
        pcm = (np.clip(s, -1.0, 1.0) * 32767).astype('<i2')
        with wave.open(path, 'wb') as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(sr)
            w.writeframes(pcm.tobytes())
        print(f'  音频: {path}')
    print()
    return len(g), total


def main():
    tts = build_tts()
    print(f'模型已加载，采样率 {tts.sample_rate}\n')

    if len(sys.argv) > 1:
        tts_texts = [(' '.join(sys.argv[1:]), 'custom')]
    else:
        # 用户报的两个现象，逐一对比
        tts_texts = [
            # ① 年份逐位读：当前代码用的是「〇」，而词表里没有〇
            ('本次审查覆盖二〇二四年一月至二〇二四年十二月期间。', 'A_当前_〇'),
            ('本次审查覆盖二零二四年一月至二零二四年十二月期间。', 'B_改零'),
            # ② 空格会不会被当成停顿
            ('本次审查覆盖 二零二四年 一 月至 二零二四年 十二月 期间。', 'C_带空格'),
            # ③ 编号逐位读（ISO 27001）
            ('抽样比例高于 ISO 二七〇〇一 要求的百分之十下限。', 'D_当前_〇编号'),
            ('抽样比例高于 ISO 二七零零一 要求的百分之十下限。', 'E_改零编号'),
            # ④ 被硬换行劈开的场景（修复前的句尾长这样）
            ('本次审查覆盖二零二四年一月至二零二四年十二。', 'F_被劈开的半句'),
        ]

    print('=' * 60)
    for text, tag in tts_texts:
        synth_and_report(tts, text, tag)
    print('=' * 60)
    print('阅读方法：内部停顿越少越连贯。数字逐位读时若出现停顿，')
    print('          基本就是那个字符模型不认识（会被跳过或断开）。')


if __name__ == '__main__':
    main()
