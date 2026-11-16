# -*- coding: utf-8 -*-
"""把 PDF 样例的原文逐页打印出来，方便对照"归一化前到底长什么样"。"""
import sys, os

FROM = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

try:
    from pypdf import PdfReader
except ImportError:
    print("缺 pypdf")
    sys.exit(1)

name = sys.argv[1] if len(sys.argv) > 1 else 'samples/合规报告-带书签.pdf'
path = os.path.join(FROM, name)
r = PdfReader(path)
for i, p in enumerate(r.pages):
    t = p.extract_text() or ''
    if '2024' in t or '27001' in t or '%' in t:
        print('=== 第', i + 1, '页 ===')
        for ln in t.splitlines():
            if ln.strip():
                print('   ', repr(ln))
