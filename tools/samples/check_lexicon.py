# -*- coding: utf-8 -*-
"""查 Kokoro 中文词表里有没有某些字 —— 缺字会导致合成时字被跳过或在中间断开。"""
import os

LEX = r'D:/dev/models/kokoro-int8-multi-lang-v1_1/lexicon-zh.txt'
print('词表:', LEX, '存在:', os.path.exists(LEX))

chars = ['〇', '零', '至', '点', '百', '分', '之', '册', '℃', '％']
if os.path.exists(LEX):
    text = open(LEX, encoding='utf-8', errors='replace').read()
    lines = text.splitlines()
    print('条目数:', len(lines))
    print('前 3 条:', lines[:3])
    for c in chars:
        # 词表行格式一般是 "汉字 pinyin" 或 "汉字\t拼音"
        hit = [l for l in lines if l.split(' ')[0] == c or l.split('\t')[0] == c]
        print(f'  {c!r} 在表里: {bool(hit)}  {hit[:1]}')

# 顺便看看模型目录里还有什么
d = r'D:/dev/models/kokoro-int8-multi-lang-v1_1'
for f in sorted(os.listdir(d)):
    p = os.path.join(d, f)
    print(f'  {f:32} {os.path.getsize(p)/1024/1024:.1f} MB')
