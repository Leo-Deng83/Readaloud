# -*- coding: utf-8 -*-
"""
PDF 目录重建 L2/L3 的算法原型（用 pypdf 先验一遍，再翻译成 Kotlin）。

要验三件事：
  ① 印刷页码 → PDF 物理页 的映射建不建得起来（目录页上写"第 1 页"，物理页可能是第 3 页）
  ② L2：能不能从「标题 ……… 页码」行里抽出条目，并定位到正文里的字符偏移
  ③ L3：既没书签也没目录页时，能不能靠**字号**认出标题（正文 10.5 / 小标题 12.5 / 大标题 16）

⚠️ pypdf 与 PDFBox 的取文/分行细节不同，这里验的是**算法与阈值**，
   真正实现要用 PDFBox 的 TextPosition（拿得到字号与 x 坐标）。
"""
import re
import sys
from collections import Counter

from pypdf import PdfReader

# ── 与 PdfReader.kt 保持一致的判定规则 ──
TOC_LINE = re.compile(r'^\s*.{2,40}?\s*[.·…⋯]{3,}\s*\d{1,4}\s*$')
PAGE_NUMBER = re.compile(
    r'^[\s\-—–_·.]*(\d{1,4})[\s\-—–_·.]*$'
    r'|^第\s*(\d{1,4})\s*页$'
    r'|^[Pp][Aa][Gg][Ee]\s*(\d{1,4})(\s*/\s*\d{1,4})?$'
    r'|^(\d{1,4})\s*/\s*\d{1,4}$'
)

CJK = r'一-鿿㐀-䶿'


def page_number_of(line):
    m = PAGE_NUMBER.match(line)
    if not m:
        return None
    for g in m.groups():
        if g:
            return int(g)
    return None


def extract_pages(path):
    """返回 [(页文本行列表, [(行文本, 字号, x, y)])]"""
    r = PdfReader(path)
    pages = []
    for p in r.pages:
        lines = []
        sizes = []          # (text, size, x, y)

        def visitor(text, cm, tm, font_dict, font_size, _sizes=sizes):
            if text.strip():
                # ⚠️ 绝对坐标 = 文本矩阵 + 变换矩阵（reportlab 用 cm 定位，
                #    只看 tm[5] 会把所有行都算成同一 y，行就全并成一行了 —— 踩过）
                _sizes.append((text, round(font_size, 1),
                               round(tm[4] + cm[4], 1), round(tm[5] + cm[5], 1)))

        txt = p.extract_text(visitor_text=visitor) or ''
        lines = [l.strip() for l in txt.splitlines() if l.strip()]
        pages.append((lines, sizes))
    return pages


def build_printed_map(pages):
    """印刷页码 → 物理页序号（从每页首尾两行里的页码行取）"""
    out = {}
    for i, (lines, _) in enumerate(pages):
        cand = lines[:2] + lines[-2:]
        for ln in cand:
            n = page_number_of(ln)
            if n is not None:
                out.setdefault(n, i)
                break
    return out


def strip_toc_blocks(pages, verbose=True):
    """把目录行剔掉，返回清理后的每页行（顺便把目录条目收出来）"""
    entries = []
    out = []
    for lines, _ in pages:
        toc_here = [l for l in lines if TOC_LINE.match(l)]
        if len(toc_here) >= 3:
            for l in toc_here:
                m = re.match(r'^\s*(.{2,40}?)\s*[.·…⋯]{3,}\s*(\d{1,4})\s*$', l)
                if m:
                    entries.append((m.group(1).strip(), int(m.group(2))))
            kept = [l for l in lines if not TOC_LINE.match(l)]
            out.append(kept if len(kept) > 2 else [])
        else:
            out.append(list(lines))
    if verbose and entries:
        print(f'  目录条目 {len(entries)} 条，前 3 条：{entries[:3]}')
    return out, entries


def assemble(pages_lines):
    """拼全文并记下每页起始偏移"""
    sb = []
    page_start = []
    for i, lines in enumerate(pages_lines):
        page_start.append(sum(len(x) + 1 for x in sb))
        sb.extend(lines)
    return '\n'.join(sb), page_start


def body_font_size(pages):
    """正文字号 = 按字符数加权的众数（页眉页脚字号小但字少，会被自动忽略）"""
    c = Counter()
    for _, sizes in pages:
        for text, size, x, y in sizes:
            c[size] += len(text.strip())
    return c.most_common(1)[0][0] if c else 0


def l3_headings(pages, body_size):
    """按字号认标题：从大到小取层级，字号明显大于正文的短行就是标题"""
    found = []
    for pi, (_, sizes) in enumerate(pages):
        # 同一行的字号可能略有差异，取该行出现最多的字号
        by_y = {}
        for text, size, x, y in sizes:
            by_y.setdefault(y, []).append((text, size, x))
        for y in sorted(by_y.keys(), reverse=True):
            items = sorted(by_y[y], key=lambda t: t[2])
            line = ''.join(t[0] for t in items).strip()
            if not line or len(line) > 40:
                continue
            size = Counter(t[1] for t in items).most_common(1)[0][0]
            if size >= body_size * 1.08:
                found.append((pi, line, size))
    return found


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else 'all'
    base = r'D:/dev/projects/Readaloud/samples/'

    if which in ('all', 'toc'):
        print('=' * 70)
        print('【L2】无书签但有目录页：合规报告-无书签有目录页.pdf')
        pages = extract_pages(base + '合规报告-无书签有目录页.pdf')
        print(f'  物理页数 {len(pages)}')
        pmap = build_printed_map(pages)
        print(f'  印刷页码→物理页 映射：{dict(sorted(pmap.items()))}')
        cleaned, entries = strip_toc_blocks(pages)
        text, page_start = assemble(cleaned)
        print(f'  清理后正文 {len(text)} 字')
        ok = 0
        for title, printed in entries:
            phys = pmap.get(printed)
            if phys is None:
                print(f'  !! {title!r} 印刷页 {printed} 找不到物理页')
                continue
            raw = page_start[phys] if phys < len(page_start) else 0
            probe = max(0, raw - 200)
            pos = text.find(title, probe)
            if pos >= 0:
                ok += 1
            else:
                print(f'  !! {title!r} 在物理页 {phys} 附近找不到（raw={raw}）')
        print(f'  → {len(entries)} 条目录里，{ok} 条成功定位到正文')
        print(f'  正文开头 60 字：{text[:60]!r}')

    if which in ('all', 'plain'):
        print()
        print('=' * 70)
        print('【L3】无书签无目录页：技术方案-无书签无目录.pdf')
        pages = extract_pages(base + '技术方案-无书签无目录.pdf')
        pmap = build_printed_map(pages)
        print(f'  物理页数 {len(pages)}  印刷页码映射：{dict(sorted(pmap.items()))}')
        cleaned, entries = strip_toc_blocks(pages)
        text, page_start = assemble(cleaned)
        bs = body_font_size(pages)
        print(f'  正文字号（按字符加权众数）= {bs}')
        heads = l3_headings(pages, bs)
        print(f'  按字号认出 {len(heads)} 个标题：')
        for pi, line, size in heads:
            print(f'     第{pi + 1}页  {size:>5}pt  {line!r}')
        # 逐个定位
        ok = 0
        for pi, line, size in heads:
            raw = page_start[pi] if pi < len(page_start) else 0
            probe = max(0, raw - 100)
            pos = text.find(line, probe)
            if pos >= 0:
                ok += 1
            else:
                print(f'  !! 定位失败：{line!r}（第{pi + 1}页，raw={raw}）')
        print(f'  → {len(heads)} 个标题里，{ok} 个成功定位到正文')


if __name__ == '__main__':
    main()
