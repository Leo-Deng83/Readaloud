# -*- coding: utf-8 -*-
"""
补测：中文后面的空格（不是中文之间）会不会造成长停顿。

为什么要测：
    归一化会把中英之间补一个空格（本意是帮模型切准语言边界）。
    "中文字 中文" 之间的空格实测会停 ~780ms；
    那么 "中文字 English" 和 "English 中文字" 这两个方向呢？
    如果也一样糟，那"补空格"这个设计就得推翻。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_pauses import build_tts, synth_and_report   # noqa: E402

CASES = [
    # 中文 → 拉丁（数字转中文后紧跟单位/型号，很常见）
    ('两条链路的日均传输量约为三点四GB。', 'X1_中文接英文_紧贴'),
    ('两条链路的日均传输量约为三点四 GB。', 'X2_中文接英文_空格'),
    # 拉丁 → 中文
    ('抽样比例高于ISO二七零零一要求的百分之十下限。', 'X3_英文接中文_紧贴'),
    ('抽样比例高于ISO 二七零零一要求的百分之十下限。', 'X4_英文接中文_空格'),
    # 两个都在同一句里（就是 App 现在实际送进去的样子）
    ('抽样比例高于 ISO 二七零零一 要求的百分之十下限。', 'X5_两个空格都有'),
]


def main():
    tts = build_tts()
    print(f'模型已加载，采样率 {tts.sample_rate}\n')
    print('=' * 64)
    for text, tag in CASES:
        synth_and_report(tts, text, tag)
    print('=' * 64)
    print('判读：把 X1/X2 和 X3/X4 各自比一比 ——')
    print('      只有"时长与停顿位置几乎一致"时，才能说那个空格无害。')


if __name__ == '__main__':
    main()
