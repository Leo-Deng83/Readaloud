# -*- coding: utf-8 -*-
"""
正文清洗规则的预演验证（编译前先在电脑上把效果看清楚）。

覆盖两块：
  A. TextCleaner 的结构噪声删除（URL / 邮箱 / 引文编号 / ISBN）
  B. PdfReader 的目录块识别（"标题……页码"）
素材都取自真实样例文件，不是编的。
"""
import re
import zipfile

URL = re.compile(r'(?:https?://|www\.)[^\s，。；、！？）】」』"\']+', re.I)
EMAIL = re.compile(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}')
ISBN = re.compile(r'ISBN[\s:：]*[\d][\d\-\s]{8,20}[\dXx]', re.I)
CITE_START = re.compile(r'^\s*[\[［〔]\s*\d{1,3}(?:\s*[-–,，]\s*\d{1,3})*\s*[\]］〕]\s*')
CITE_INLINE = re.compile(r'(?<=[\s，。；、：！？)】」』])\s*[\[［〔]\s*\d{1,3}(?:\s*[-–,，]\s*\d{1,3})*\s*[\]］〕]')
MULTI_SPACE = re.compile(r'[ \t\u00A0]{2,}')
SPACE_BEFORE_PUNCT = re.compile(r'\s+([，。；、：！？])')
SPACE_BETWEEN_CJK = re.compile(r'(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])')
DUP_PUNCT = re.compile(r'([，。；、：！？])\s*[，。；、：！？]+')

TOC_LINE = re.compile(r'^\s*.{2,40}?\s*[.·…⋯]{3,}\s*\d{1,4}\s*$')


def tidy(s):
    s = MULTI_SPACE.sub(' ', s)
    s = SPACE_BEFORE_PUNCT.sub(r'\1', s)
    s = DUP_PUNCT.sub(r'\1', s)
    s = SPACE_BETWEEN_CJK.sub('', s)
    return s.strip()


def clean(text):
    out = []
    for line in text.split('\n'):
        l = URL.sub('', line)
        l = EMAIL.sub('', l)
        l = ISBN.sub('', l)
        l = CITE_START.sub('', l)
        l = CITE_INLINE.sub('', l)
        l = tidy(l)
        if l:
            out.append(l)
    return '\n'.join(out)


print('=' * 72)
print('A. 结构噪声清理（素材：样例 EPUB 第四章的真实内容）')
z = zipfile.ZipFile(r'D:/dev/projects/Readaloud/samples/星尘信使-EPUB3.epub')
raw = z.read('OEBPS/ch4.xhtml').decode('utf-8')
body = raw.split('<body>')[1].split('</body>')[0]
body = re.sub(r'<[^>]+>', '\n', body)
body = body.replace('&mdash;', '—')
lines = [l.strip() for l in body.split('\n') if l.strip()]
src = '\n'.join(lines)
print('--- 清理前（含噪声的两行）---')
for l in src.split('\n'):
    if 'http' in l or '@' in l or 'ISBN' in l or '[' in l:
        print('  ', l)
print('--- 清理后 ---')
dst = clean(src)
for l in dst.split('\n'):
    if '脚注' in l or '参考文献' in l:
        print('  ', l)
print()
print('  噪声残留检查：')
for probe in ['http', '@', 'ISBN', '[12]', '[13-15]', '978-7']:
    print(f'    {probe:10} 仍在正文里: {probe in dst}')

print()
print('=' * 72)
print('B. 目录块识别（素材：样例 PDF 的目录页）')
from pypdf import PdfReader
r = PdfReader(r'D:/dev/projects/Readaloud/samples/合规报告-无书签有目录页.pdf')
toc_page = (r.pages[0].extract_text() or '')
toc_lines = [l.strip() for l in toc_page.splitlines() if l.strip()]
hits = [l for l in toc_lines if TOC_LINE.match(l)]
print(f'  第 1 页共 {len(toc_lines)} 行，被判定为目录行的有 {len(hits)} 行：')
for l in hits[:4]:
    print('   ✓', l)
miss = [l for l in toc_lines if not TOC_LINE.match(l)]
print(f'  未判为目录行的（应该只剩页眉页码之类）: {miss}')
print()
print('  整块判定（阈值 3 行）:', '整页当目录丢掉' if len(hits) >= 3 else '不成块，保留')

print()
print('=' * 72)
print('C. 正文页不该被误判（素材：样例 PDF 的正文页）')
for i in (1, 2, 3):
    if i >= len(r.pages):
        break
    t = (r.pages[i].extract_text() or '')
    ls = [l.strip() for l in t.splitlines() if l.strip()]
    h = [l for l in ls if TOC_LINE.match(l)]
    print(f'  第 {i+1} 页 {len(ls)} 行 → 目录行 {len(h)} 行 {"(OK 不会误删)" if len(h) < 3 else "(!! 会误删)"}')
