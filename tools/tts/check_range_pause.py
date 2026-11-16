# -*- coding: utf-8 -*-
"""
校验"区间两侧加气口 + 收尾清空格"这条规则链（Kotlin 里相关规则的等价实现）。

目的：确认输出**逐字等于** Leo 试听后选的 V4 那句文本，
并且不会在别的地方误伤（"截至""来到""周到"里的至/到不能动）。
"""
import re

DATE = re.compile(r'(\d{4})-(\d{1,2})-(\d{1,2})')
TIME = re.compile(r'\b(\d{1,2}):(\d{2})\b')
YEAR_RANGE = re.compile(r'(\d{4})\s*[-–—]\s*(\d{4})(\s*[年月日天页%]*)')
RANGE = re.compile(r'(\d+(?:\.\d+)?)\s*[–—~～至]\s*(\d+(?:\.\d+)?)(\s*[年月日天页%]*)')
RANGE_SPACED = re.compile(r'(?<![A-Za-z0-9])(\d{1,3})\s+-\s+(\d{1,3})(?![0-9])(\s*[年月日天页%]*)')
RANGE_WORD_PAUSE = re.compile(r'(?<=[\d月日年点时分秒%])[ \t]*(至|到)[ \t]*(?=[\d零一二三四五六七八九十])')
YEAR = re.compile(r'(\d{4})\s*年')
DECIMAL = re.compile(r'(\d+)\.(\d+)')
NUMBER = re.compile(r'\d+')
PERCENT = re.compile(r'(\d+(?:\.\d+)?)\s*%')
CJK = '一-鿿㐀-䶿豈-﫿々〆〇'
SP = r'[ \t 　]'
CJK_SPACES = re.compile(f'(?<=[{CJK}]){SP}+(?=[{CJK}])')
SPACE_BEFORE_PUNCT = re.compile(SP + '+(?=[，。、；：！？…》）】」』])')
SPACE_AFTER_PUNCT = re.compile('([，。、；：！？…》）】」』])' + SP + '+')

CN = '零一二三四五六七八九'
UNITS = ['千', '百', '十', '']


def int_to_cn(n):
    if n == 0:
        return '零'
    ds = [n // 1000 % 10, n // 100 % 10, n // 10 % 10, n % 10]
    out, zero = '', False
    for i, d in enumerate(ds):
        if d == 0:
            if out:
                zero = True
        else:
            if zero:
                out += '零'
                zero = False
            out += CN[d] + UNITS[i]
    return out[1:] if out.startswith('一十') else out


def digits_to_cn(s):
    return ''.join(CN[int(c)] for c in s if c.isdigit())


def num_to_cn(s):
    if '.' in s:
        i, f = s.split('.', 1)
        head = int_to_cn(int(i)) if len(i) <= 4 else digits_to_cn(i)
        return head + '点' + digits_to_cn(f)
    if len(s) <= 4:
        return int_to_cn(int(s))
    if s.endswith('000'):
        return int_to_cn(int(s))
    return digits_to_cn(s)


def year_to_cn(y):
    if len(y) == 4 and y.endswith('000'):
        return ('两' if y[0] == '2' else CN[int(y[0])]) + '千'
    return digits_to_cn(y)


def range_spoken(a, b, unit):
    tail = '，' if not unit.strip() else unit
    return '，' + a + '到' + b + tail


def normalize(text):
    s = text.replace('〇', '零')
    s = DATE.sub(lambda m: year_to_cn(m.group(1)) + '年' + num_to_cn(str(int(m.group(2)))) +
                  '月' + num_to_cn(str(int(m.group(3)))) + '日', s)
    s = TIME.sub(lambda m: num_to_cn(m.group(1)) + '点' +
                 ('整' if m.group(2) == '00' else num_to_cn(m.group(2)) + '分'), s)
    s = RANGE_WORD_PAUSE.sub(lambda m: '，' + m.group(1) + '，', s)
    s = YEAR_RANGE.sub(lambda m: range_spoken(year_to_cn(m.group(1)), year_to_cn(m.group(2)), m.group(3)), s)
    s = RANGE.sub(lambda m: range_spoken(num_to_cn(m.group(1)), num_to_cn(m.group(2)), m.group(3)), s)
    s = RANGE_SPACED.sub(lambda m: range_spoken(num_to_cn(m.group(1)), num_to_cn(m.group(2)), m.group(3)), s)
    s = PERCENT.sub(lambda m: '百分之' + num_to_cn(m.group(1)), s)
    s = YEAR.sub(lambda m: year_to_cn(m.group(1)) + '年', s)
    s = DECIMAL.sub(lambda m: num_to_cn(m.group(1) + '.' + m.group(2)), s)
    s = NUMBER.sub(lambda m: num_to_cn(m.group(0)), s)
    s = CJK_SPACES.sub('', s)
    s = SPACE_BEFORE_PUNCT.sub('', s)
    s = SPACE_AFTER_PUNCT.sub(lambda m: m.group(1), s)
    return s


V4 = '本次审查覆盖二零二四年一月，至，二零二四年十二月期间。'
CASES = [
    # ① 目标：必须逐字等于试听选中的 V4
    ('本次审查覆盖 2024 年 1 月至 2024 年 12 月期间。', V4),
    ('本次审查覆盖 2024 年 1 月至 2024 年 12 月期间，公司全部业务系统所产生的个人数据与业务数据。',
     '本次审查覆盖二零二四年一月，至，二零二四年十二月期间，公司全部业务系统所产生的个人数据与业务数据。'),
    # ② 其他区间写法也要有气口
    ('共处理了 3 至 5 批次。', '共处理了三，至，五批次。'),
    ('统计区间为 12 至 15 天。', '统计区间为十二，至，十五天。'),
    ('周期 1990-2000 年。', '周期，一九九零到两千年。'),
    # ③ 至/到 不能被误伤
    ('截至目前，数据已齐。', '截至目前，数据已齐。'),
    ('以至于大家都不说话了。', '以至于大家都不说话了。'),
    ('他来到了门口。', '他来到了门口。'),
    ('这里有很多周到细致的安排。', '这里有很多周到细致的安排。'),
    ('自 2024 年 1 月起执行。', '自二零二四年一月起执行。'),
    # ④ 原有规则不能被破坏
    ('数据接口 236 个，抽样比例约 12.5%。', '数据接口二百三十六个，抽样比例约百分之十二点五。'),
    ('会议时间为 14:30。', '会议时间为十四点三十分。'),
    ('标准编号 ISO 27001 的要求。', '标准编号 ISO 二七零零一的要求。'),
    ('第 1.2 节的内容。', '第一点二节的内容。'),
]

ok = True
for src, want in CASES:
    got = normalize(src)
    hit = (got == want)
    ok = ok and hit
    print(('  OK  ' if hit else '  !!  ') + src)
    if not hit:
        print('        期望: ' + want)
        print('        实际: ' + got)
print()
print('全部一致 ✅' if ok else '有不一致项 ❌')
print()
print('关键：日期句必须逐字等于 ——')
print('  ' + V4)
print('实际：')
print('  ' + normalize('本次审查覆盖 2024 年 1 月至 2024 年 12 月期间。'))
