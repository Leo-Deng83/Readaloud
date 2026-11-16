# -*- coding: utf-8 -*-
"""预演 v0.2.3 新加的归一化规则（全角/罗马/单位符号、中英边界、中文后半角标点）。"""

import re

FULLWIDTH_ALNUM = re.compile(r'[\uFF10-\uFF19\uFF21-\uFF3A\uFF41-\uFF5A]')
ROMAN = {'Ⅰ': '一', 'Ⅱ': '二', 'Ⅲ': '三', 'Ⅳ': '四', 'Ⅴ': '五', 'Ⅵ': '六',
         'Ⅶ': '七', 'Ⅷ': '八', 'Ⅸ': '九', 'Ⅹ': '十', 'Ⅺ': '十一', 'Ⅻ': '十二'}
UNITS = {'℃': '摄氏度', '℉': '华氏度', '％': '百分之', '‰': '千分之',
         '㎡': '平方米', '㎥': '立方米', '㎞': '公里', '㎝': '厘米',
         '㎜': '毫米', '㎏': '千克', '㎎': '毫克', '°': '度'}
CJK_LATIN = re.compile(r'(?<=[\u4e00-\u9fff])(?=[A-Za-z])')
LATIN_CJK = re.compile(r'(?<=[A-Za-z])(?=[\u4e00-\u9fff])')
HALF_AFTER_CJK = re.compile(r'(?<=[\u4e00-\u9fff])([,;:!?])')

PUNCT_MAP = {',': '，', ';': '；', ':': '：', '!': '！', '?': '？'}


def news(s):
    s = FULLWIDTH_ALNUM.sub(lambda m: chr(ord(m.group(0)) - 0xFEE0), s)
    if any(c in ROMAN for c in s):
        s = ''.join(ROMAN.get(c, c) for c in s)
    if any(c in UNITS for c in s):
        s = ''.join(UNITS.get(c, c) for c in s)
    s = CJK_LATIN.sub(' ', s)
    s = LATIN_CJK.sub(' ', s)
    s = HALF_AFTER_CJK.sub(lambda m: PUNCT_MAP[m.group(0)], s)
    return s


cases = [
    'ＡＢＣ１２３ 是 全角',
    '第Ⅳ章 转折',
    '温度 37℃',
    '面积 120㎡，重 5㎏',
    'NASA的巡天团队在 2024 年公布',
    '这个 API 很稳定',
    '他说,好的。',
    'This is a test, 中文在后面',
    'OCR识别的PDF文件',
    '正常的中文句子，不该改变。',
]
print('=' * 74)
for c in cases:
    print(f'  {c!r:34} → {news(c)!r}')
print('=' * 74)
print('检查点：')
print('  · 全角数字字母应变半角')
print('  · 罗马数字 Ⅳ → 四')
print('  · ℃/㎡/㎏ 应变中文')
print('  · 中英边界应插入空格（NASA的 → NASA 的）')
print('  · 纯英文句子的半角标点**不该**被改')
print('  · 正常中文句不该被改动')
