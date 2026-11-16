# -*- coding: utf-8 -*-
"""
生成 EPUB 测试样例（零第三方依赖，手工按 EPUB 规范打包）。

为什么自己做样例而不是随便找个 epub：
  手机上一个 epub 都没有，而"能读通"和"读得对"是两件事 ——
  样例里刻意塞了解析器必须处理的坑：ruby 注音（不能读出拼音）、
  表格、图片、script/style（要剥掉）、HTML 实体、跨行段落、中英混排。
  做两份：一份 EPUB3（目录在 nav 文档里），一份 EPUB2（只有 NCX）——
  后者专门用来验证"nav 找不到时回退 NCX"这条逻辑真的通。

输出：D:/dev/projects/Readaloud/samples/*.epub
"""
import os
import zipfile

OUT_DIR = r'D:/dev/projects/Readaloud/samples'

TITLE = '星尘信使'
AUTHOR = '林知远'
LANG = 'zh-CN'
UID = 'urn:uuid:readaloud-sample-0001'


# ── 图片（程序生成，不引素材，避免版权问题）──────────────────────
def make_png(w=360, h=240, c1=(58, 92, 168), c2=(122, 66, 160)):
    """画一张纵向渐变的图，返回 PNG 字节。用最小的手写 PNG 编码。"""
    import struct
    import zlib

    rows = bytearray()
    for y in range(h):
        rows.append(0)                      # 每行的 filter 字节
        t = y / max(1, h - 1)
        r = int(c1[0] + (c2[0] - c1[0]) * t)
        g = int(c1[1] + (c2[1] - c1[1]) * t)
        b = int(c1[2] + (c2[2] - c1[2]) * t)
        rows.extend(bytes([r, g, b]) * w)

    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(rows), 9))
    png += chunk(b'IEND', b'')
    return png


# ── 正文内容（原创，避免版权问题；每处特殊结构都是给解析器出的题）──
CH1 = """<h1>第一章 雨夜来客</h1>
<p>雨点敲在铁皮屋顶上，发出密集的鼓点声。林深把台灯往下压了压，让光只落在书页上，其余的角落都沉进阴影里。</p>
<p>门外忽然传来三下敲门声。不轻不重，间隔均匀得像是被人计算过。</p>
<p>他起身去开门。门口站着一个浑身湿透的年轻人，怀里抱着一个用油布裹得严严实实的包裹。</p>
<p>「有人让我把这个交给你。」年轻人说，「他说你看到就明白了。」</p>
<p>林深接过包裹。油布下面是一只樟木匣子，匣盖上刻着一行极小的字：<em>给十年后的林深</em>。</p>
<p>那是他自己的笔迹。</p>
<p>他抬头想问些什么，年轻人已经转身走进雨里，脚步快得不像刚爬过七层楼梯的样子。</p>
"""

CH2 = """<h1>第二章 迟到的信</h1>
<p>匣子里没有信，只有一本皮面笔记本和一张折了四折的星图。</p>
<p>笔记本的扉页上写着一串观测记录，字迹潦草，却每一行都标着日期。</p>
<table>
  <tr><th>观测日期</th><th>目标</th><th>视星等</th></tr>
  <tr><td>2014-03-11</td><td>天狼星</td><td>−1.46</td></tr>
  <tr><td>2014-04-02</td><td>参宿四</td><td>0.42</td></tr>
  <tr><td>2014-05-19</td><td>织女星</td><td>0.03</td></tr>
</table>
<p>林深一页一页往下看，手指忽然在某一行停住了。那是他十年前的笔迹，记录着一个当时根本不该存在的坐标。</p>
<p>纸页的边缘有一点焦痕，像是被什么高温的东西燎过。</p>
"""

CH3 = """<h1>第三章 老图书馆</h1>
<p>第二天一早，林深去了城西的老图书馆。那栋楼建于上世纪三十年代，外墙爬满了爬山虎。</p>
<p style="text-align:center"><img src="images/plate.png" alt="图书馆一角"/></p>
<p>他在<ruby>星图<rt>xīng tú</rt></ruby>上找到的那个坐标，正对着图书馆三楼的西北角。</p>
<p>楼梯是木头的，踩上去会发出吱呀声。管理员坐在窗边打盹，眼皮都没抬一下。</p>
<p>三楼西北角的书架已经很久没人动过了，书脊上积着一层灰。他数着架子往前走，在第七排停下。</p>
<script>console.log('这一行是脚本，任何情况下都不该出现在朗读内容里');</script>
<style>body { font-family: serif; }</style>
<p>书架最底层压着一本没有书脊标签的册子。</p>
"""

CH4 = """<h1>第四章 星尘之下</h1>
<p>册子里夹着一份打印稿。纸已经发黄，边角卷起，上面是一段摘要。</p>
<p>摘要提到，NASA 的巡天团队在 2024 年公开的数据里，发现了一颗编号 2014-AB 的小行星。它的轨道周期是 12.5 年，近日点距离地球约 0.31 个天文单位。</p>
<p>林深把这句话读了三遍。十年。正好是匣子上刻的那个数字。</p>
<p>窗外的雨停了。云层裂开一道缝，光落下来，照在册子最后一页的空白处。</p>
<p>那一页本来什么都没有。可就在光落下去的瞬间，纸上慢慢浮出一行淡淡的字迹：<em>&mdash;&mdash; 别去找它。</em></p>
<p>林深合上册子，把它抱在怀里，像抱着一个迟到十年的回答。</p>
<p>稿末附着一行脚注<sup>[12]</sup>，以及一个联络方式：lin.shen@example.org，原始出处见 https://example.org/archive/2014-ab 这份存档。</p>
<p>参考文献还列出了 ISBN 978-7-121-23456-7，另外 [13-15] 三条旁证也都指向同一个坐标。</p>
<p>他忽然想起，十年前那个雨夜，自己也在笔记本上写过几乎一样的话。</p>
"""

CHAPTERS = [
    ('ch1', '第一章 雨夜来客', CH1),
    ('ch2', '第二章 迟到的信', CH2),
    ('ch3', '第三章 老图书馆', CH3),
    ('ch4', '第四章 星尘之下', CH4),
]

CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""


def xhtml(title, body, with_css=True):
    css = '<link rel="stylesheet" type="text/css" href="style.css"/>' if with_css else ''
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN" lang="zh-CN">
<head>
  <meta charset="utf-8"/>
  <title>{title}</title>
  {css}
</head>
<body>
{body}</body>
</html>
"""


CSS = """body { font-family: serif; line-height: 1.7; }
h1 { font-size: 1.4em; }
table { border-collapse: collapse; }
td, th { border: 1px solid #999; padding: 4px 8px; }
"""


def write_epub(path, epub3):
    """epub3=True 出 EPUB3（nav 目录）；False 出 EPUB2（只有 NCX）"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, 'w') as z:
        # ⚠️ mimetype 必须是压缩包里的第一个文件，且不压缩 —— 这是 EPUB 规范的硬要求，
        #    有些严格解析器（包括我们的）会靠它认文件类型
        zi = zipfile.ZipInfo('mimetype')
        zi.compress_type = zipfile.ZIP_STORED
        z.writestr(zi, 'application/epub+zip')

        def add(name, data):
            z.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)

        add('META-INF/container.xml', CONTAINER)
        add('OEBPS/style.css', CSS)
        add('OEBPS/images/plate.png', make_png())
        for cid, ctitle, body in CHAPTERS:
            add(f'OEBPS/{cid}.xhtml', xhtml(ctitle, body))

        if epub3:
            # EPUB3：目录写在 nav 文档里（properties="nav"，规范强制）
            nav_items = '\n'.join(
                f'      <li><a href="{cid}.xhtml">{ctitle}</a></li>' for cid, ctitle, _ in CHAPTERS
            )
            add('OEBPS/nav.xhtml', f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN">
<head><meta charset="utf-8"/><title>目录</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>目录</h1>
    <ol>
{nav_items}
    </ol>
  </nav>
</body>
</html>
""")
            manifest = '\n'.join([
                '    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>',
                '    <item id="css" href="style.css" media-type="text/css"/>',
                '    <item id="plate" href="images/plate.png" media-type="image/png"/>',
                '    <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>',
            ] + [
                f'    <item id="{cid}" href="{cid}.xhtml" media-type="application/xhtml+xml"/>'
                for cid, _, _ in CHAPTERS
            ])
            spine = '\n'.join(
                [f'    <itemref idref="{cid}"/>' for cid, _, _ in CHAPTERS]
            )
            opf = f"""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="zh-CN">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">{UID}</dc:identifier>
    <dc:title>{TITLE}</dc:title>
    <dc:creator>{AUTHOR}</dc:creator>
    <dc:language>{LANG}</dc:language>
    <meta property="dcterms:modified">2026-10-05T12:00:00Z</meta>
  </metadata>
  <manifest>
{manifest}
  </manifest>
  <spine>
{spine}
  </spine>
</package>
"""
        else:
            # EPUB2：目录只有 NCX（nav 不存在）—— 专门用来验证回退逻辑
            points = '\n'.join(f"""    <navPoint id="np{i+1}" playOrder="{i+1}">
      <navLabel><text>{ctitle}</text></navLabel>
      <content src="{cid}.xhtml"/>
    </navPoint>""" for i, (cid, ctitle, _) in enumerate(CHAPTERS))
            add('OEBPS/toc.ncx', f"""<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="{UID}"/>
    <meta name="dtb:depth" content="1"/>
  </head>
  <docTitle><text>{TITLE}</text></docTitle>
  <navMap>
{points}
  </navMap>
</ncx>
""")
            manifest = '\n'.join([
                '    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>',
                '    <item id="css" href="style.css" media-type="text/css"/>',
                '    <item id="plate" href="images/plate.png" media-type="image/png"/>',
            ] + [
                f'    <item id="{cid}" href="{cid}.xhtml" media-type="application/xhtml+xml"/>'
                for cid, _, _ in CHAPTERS
            ])
            spine = '\n'.join(
                [f'    <itemref idref="{cid}"/>' for cid, _, _ in CHAPTERS]
            )
            opf = f"""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:identifier id="bookid">{UID}-v2</dc:identifier>
    <dc:title>{TITLE}（EPUB2 版）</dc:title>
    <dc:creator>{AUTHOR}</dc:creator>
    <dc:language>{LANG}</dc:language>
  </metadata>
  <manifest>
{manifest}
  </manifest>
  <spine toc="ncx">
{spine}
  </spine>
</package>
"""
        add('OEBPS/content.opf', opf)
        if epub3:
            add('OEBPS/cover.xhtml', xhtml('封面', '<h1>星尘信使</h1><p>林知远 著</p>', with_css=False))
    print('生成:', path, os.path.getsize(path), 'bytes')


if __name__ == '__main__':
    write_epub(os.path.join(OUT_DIR, '星尘信使-EPUB3.epub'), epub3=True)
    write_epub(os.path.join(OUT_DIR, '星尘信使-EPUB2.epub'), epub3=False)
