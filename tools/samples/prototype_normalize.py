# -*- coding: utf-8 -*-
"""
正文归一化规则的"预演版"（只为验证规则本身，确认输出自然了再翻译成 Kotlin）。

要解决的问题（Leo 验收反馈）：
  · "1.1" 这种小标题编号，模型要么跳过、要么读成"1，1"
  · "12.5%" 的百分号被读成英文 percent
  · 阿拉伯数字整体音调别扭 —— 根因是模型读数字时用的是非中文音素规则

规则顺序很关键：先处理复合形态（日期/时间/区间/百分比/单位），
最后才处理"光秃秃的数字"，否则 "2024-03-11" 会被拆得乱七八糟。
"""
import re

DIGITS = '零一二三四五六七八九'
BIG_UNITS = ['', '万', '亿', '万亿']


def section_to_cn(sec):
    """1..9999 的整数转中文（内部的四位一段）"""
    units = ['千', '百', '十', '']
    ds = [sec // 1000 % 10, sec // 100 % 10, sec // 10 % 10, sec % 10]
    out = []
    zero = False
    for i, d in enumerate(ds):
        if d == 0:
            if out:
                zero = True
        else:
            if zero:
                out.append('零')
                zero = False
            out.append(DIGITS[d] + units[i])
    s = ''.join(out)
    if s.startswith('一十'):
        s = s[1:]          # "一十" 读作 "十" 更自然
    return s


def int_to_cn(n):
    if n == 0:
        return '零'
    parts = []
    v = n
    while v > 0:
        parts.append(v % 10000)
        v //= 10000
    out = []
    for i in range(len(parts) - 1, -1, -1):
        sec = parts[i]
        if sec == 0:
            continue
        if out and sec < 1000:
            out.append('零')            # 一万零一
        out.append(section_to_cn(sec) + BIG_UNITS[i])
    return ''.join(out)


def digits_to_cn(s):
    """逐位读（0 读作 〇）—— 用于年份、超过 4 位的编号"""
    m = '〇一二三四五六七八九'
    return ''.join(m[int(c)] for c in s if c.isdigit())


def year_to_cn(y):
    """年份：整千年读"两千"，其余逐位读（2024 → 二〇二四）"""
    if len(y) == 4 and y.endswith('000'):
        k = int(y[0])
        return ('两' if k == 2 else DIGITS[k]) + '千'
    return digits_to_cn(y)


def num_to_cn(s):
    """
    数字串转中文。
    ⚠️ 4 位以内整体读（236 → 二百三十六）；
       超过 4 位：末尾一大串 0 的（金额类，1000000）整体读成"一百万"，
       其余逐位读 —— "ISO 27001" 读"二七〇〇一"才自然，
       读成"二万七千零一"就跑偏了。
    """
    if '.' in s:
        i, f = s.split('.', 1)
        head = int_to_cn(int(i)) if len(i) <= 4 else digits_to_cn(i)
        return head + '点' + digits_to_cn(f)
    if len(s) <= 4:
        return int_to_cn(int(s))
    if s.endswith('000'):
        return int_to_cn(int(s))
    return digits_to_cn(s)


def normalize(text):
    s = text

    # ① 日期 2024-03-11 → 二〇二四年三月十一日
    s = re.sub(r'(\d{4})-(\d{1,2})-(\d{1,2})',
               lambda m: year_to_cn(m.group(1)) + '年' +
                         num_to_cn(str(int(m.group(2)))) + '月' +
                         num_to_cn(str(int(m.group(3)))) + '日', s)

    # ② 时间 14:30 → 十四点三十分
    s = re.sub(r'\b(\d{1,2}):(\d{2})\b',
               lambda m: num_to_cn(m.group(1)) + '点' +
                         ('整' if m.group(2) == '00' else num_to_cn(m.group(2)) + '分'), s)

    # ③ 年份区间 1990-2000 → 一九九〇年到两千年（PRD 点名的那种写法）
    s = re.sub(r'(\d{4})\s*[-–—]\s*(\d{4})',
               lambda m: year_to_cn(m.group(1)) + '到' + year_to_cn(m.group(2)), s)

    # ④ 其余区间：只认非 ASCII 连接号，或带空格的短数字（12 - 15）
    #    ⚠️ 故意不认紧贴的 ASCII 连字符："A-12-3" 这类编号读成"减"更糟
    s = re.sub(r'(\d+(?:\.\d+)?)\s*[–—~～至]\s*(\d+(?:\.\d+)?)',
               lambda m: num_to_cn(m.group(1)) + '到' + num_to_cn(m.group(2)), s)
    s = re.sub(r'(?<![A-Za-z0-9])(\d{1,3})\s+-\s+(\d{1,3})(?![0-9])',
               lambda m: num_to_cn(m.group(1)) + '到' + num_to_cn(m.group(2)), s)

    # ⑤ 百分比 12.5% → 百分之十二点五
    s = re.sub(r'(\d+(?:\.\d+)?)\s*%',
               lambda m: '百分之' + num_to_cn(m.group(1)), s)

    # ⑥ 常见单位（PRD 点名的那几个）
    s = re.sub(r'(\d+(?:\.\d+)?)\s*km/h', lambda m: '每小时' + num_to_cn(m.group(1)) + '公里',
               s, flags=re.I)
    s = re.sub(r'(\d+(?:\.\d+)?)\s*m/s', lambda m: '每秒' + num_to_cn(m.group(1)) + '米',
               s, flags=re.I)
    s = re.sub(r'(\d+(?:\.\d+)?)\s*°C', lambda m: num_to_cn(m.group(1)) + '摄氏度', s, flags=re.I)

    # ⑦ 年份 2024 年 → 二〇二四年
    s = re.sub(r'(\d{4})\s*年', lambda m: year_to_cn(m.group(1)) + '年', s)

    # ⑧ 小数 / 编号 1.1 → 一点一
    s = re.sub(r'(\d+)\.(\d+)', lambda m: num_to_cn(m.group(1) + '.' + m.group(2)), s)

    # ⑨ 剩下的裸数字
    s = re.sub(r'\d+', lambda m: num_to_cn(m.group(0)), s)

    # ⑩ 数学/关系符号
    s = s.replace('≥', '大于等于').replace('≤', '小于等于').replace('≈', '约等于')
    s = s.replace('≠', '不等于').replace('×', '乘').replace('÷', '除').replace('±', '正负')
    # 加号/等号只在"两边都是数字或字母"时当运算符（a+b=3 → a加b等于三）；
    # ⚠️ 减号刻意不处理：编号里的连字符（A-12-3）读成"减"比不处理更别扭
    operand = r'[0-9A-Za-z〇零一二三四五六七八九十百千万亿点]'
    s = re.sub(r'(?<=' + operand + r')\s*\+\s*(?=' + operand + r')', '加', s)
    s = re.sub(r'(?<=' + operand + r')\s*=\s*(?=' + operand + r')', '等于', s)

    return s


if __name__ == '__main__':
    from pypdf import PdfReader
    r = PdfReader(r'D:/dev/projects/Readaloud/samples/合规报告-带书签.pdf')
    body = '\n'.join((p.extract_text() or '') for p in r.pages)
    print('=' * 70)
    print('【原文（前 12 行）】')
    print('\n'.join(body.splitlines()[:12]))
    print('=' * 70)
    print('【归一化后（前 12 行）】')
    print('\n'.join(normalize(body).splitlines()[:12]))
    print('=' * 70)
    print('【针对性的单点用例】')
    cases = [
        '1.1 审查范围', '12.5%', '3.4GB', '2024 年 1 月', '14:30', '2014-03-11',
        'ISO 27001', '共 236 个', '412 个字段', '10% 下限', '15 个工作日',
        '1990-2000', '5km/h', 'a+b=3', '温度 37°C', '第 12 章', '共 1000000 元',
        '0.31 个天文单位', '2.3 × 10', '编号 A-12-3',
    ]
    for c in cases:
        print(f'  {c!r:26} → {normalize(c)!r}')
