# -*- coding: utf-8 -*-
"""
修复验证：每组**只变一个变量**，其它字完全相同 —— 这样时长和停顿才可比。

判读标准：
    这几句内部**没有标点**（除了句末的句号），所以正常情况下不该出现长停顿。
    出现 ≥400ms 的长停顿 = 文字本身有问题（模型不认识的字 / 被空格·换行劈开）。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_pauses import build_tts, gaps   # noqa: E402

import numpy as np   # noqa: E402

LONG_GAP_MS = 400

# (组名, 说明, 修复前文本, 修复后文本)
GROUPS = [
    (
        '① 年份里那个「〇」',
        '词表里没有〇，模型遇到不认的字会在中间断开',
        '本次审查覆盖二〇二四年一月至二〇二四年十二月期间。',
        '本次审查覆盖二零二四年一月至二零二四年十二月期间。',
    ),
    (
        '② 中文字之间的空格',
        '空格会被模型当成停顿（PDF 抽取的数字两侧就是这种空格）',
        '本次审查覆盖二零二四年 一 月至二零二四年 十二月期间。',
        '本次审查覆盖二零二四年一月至二零二四年十二月期间。',
    ),
    (
        '③ 编号里那个「〇」（ISO 27001）',
        '同一个原因，编号也要用零',
        '抽样比例高于 ISO 二七〇〇一要求的百分之十下限。',
        '抽样比例高于 ISO 二七零零一要求的百分之十下限。',
    ),
]


def measure(tts, text):
    audio = tts.generate(text, sid=3, speed=1.0)
    s = np.array(audio.samples, dtype=np.float32)
    g, total = gaps(s, audio.sample_rate)
    long_gaps = [d for _, d in g if d >= LONG_GAP_MS]
    return total, long_gaps


def main():
    tts = build_tts()
    print(f'模型已加载（采样率 {tts.sample_rate}）\n')
    print('=' * 76)

    rows = []
    for name, why, before, after in GROUPS:
        tb, gb = measure(tts, before)
        ta, ga = measure(tts, after)
        rows.append((name, tb, gb, ta, ga))
        print(f'{name}  —— {why}')
        print(f'   修复前：{tb:>6.0f} ms   长停顿 {len(gb)} 处 {gb}')
        print(f'    文本：{before}')
        print(f'   修复后：{ta:>6.0f} ms   长停顿 {len(ga)} 处 {ga}')
        print(f'    文本：{after}')
        print()

    print('=' * 76)
    all_ok = True
    for name, tb, gb, ta, ga in rows:
        gain = tb - ta
        ok = len(ga) < len(gb) or (len(ga) == len(gb) and gain > 300)
        all_ok = all_ok and ok
        print(f'{"✅" if ok else "❌"} {name}：长停顿 {len(gb)} → {len(ga)} 处，'
              f'时长 {tb:.0f} → {ta:.0f} ms（省 {gain:.0f} ms）')
    print()
    print('结论：' + ('三组全部改善，修复有效 ✅' if all_ok else '仍有未改善的项 ❌'))
    print('注意：句子内部的逗号/顿号本来就会停 700–800ms（模型自身的标点处理），')
    print('      那是正常的，不属于要修的"断错地方"。')


if __name__ == '__main__':
    main()
