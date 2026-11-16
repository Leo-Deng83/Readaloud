# -*- coding: utf-8 -*-
"""
自检生成的样例 —— 样例本身不对，后面调试就会白费工夫。
查三件事：
  1. PDF 的大纲（书签）到底有没有写进去（L1 的测试素材必须真的有书签）
  2. PDF 能不能抽出中文文字（L2/L3 的素材必须能抽，扫描版必须抽不出）
  3. EPUB 的 mimetype 是否是第一个文件且未压缩（规范的硬要求）
"""
import os
import zipfile

from pypdf import PdfReader

SAMPLES = r'D:/dev/projects/Readaloud/samples'


def walk_outline(items, depth=0, out=None):
    if out is None:
        out = []
    for it in items:
        if isinstance(it, list):
            walk_outline(it, depth + 1, out)
        else:
            out.append('  ' * depth + str(it.title))
    return out


print('=' * 60)
for name in sorted(os.listdir(SAMPLES)):
    path = os.path.join(SAMPLES, name)
    if name.lower().endswith('.pdf'):
        r = PdfReader(path)
        print(f'[PDF] {name}')
        print(f'      页数 = {len(r.pages)}')
        try:
            outline = walk_outline(r.outline)
        except Exception as e:                      # noqa: BLE001
            outline = [f'<读取大纲出错: {e}>']
        print(f'      书签数 = {len(outline)}')
        for line in outline[:6]:
            print('        -', line)
        text = (r.pages[0].extract_text() or '')
        print(f'      第 1 页抽出文字数 = {len(text)}')
        print(f'      开头 = {text[:60]!r}')
        print()

for name in sorted(os.listdir(SAMPLES)):
    path = os.path.join(SAMPLES, name)
    if name.lower().endswith('.epub'):
        print(f'[EPUB] {name}')
        with zipfile.ZipFile(path) as z:
            infos = z.infolist()
            first = infos[0]
            print(f'      第一个条目 = {first.filename!r} 压缩方式={first.compress_type} (0=不压缩)')
            print(f'      mimetype 内容 = {z.read("mimetype").decode()!r}')
            print(f'      文件数 = {len(infos)}')
            for i in infos:
                print('        ·', i.filename)
        print()
print('=' * 60)
