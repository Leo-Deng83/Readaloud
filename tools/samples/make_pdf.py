# -*- coding: utf-8 -*-
"""
生成 PDF 测试样例（三份，对应 PRD FR-03 的三种目录重建路径）。

为什么要三份：
  手机上一个 pdf 都没有，而"能抽出文字"和"能重建出目录"是两件事。
  PRD 规定目录要三级降级（L1 内嵌书签 → L2 目录页 → L3 排版聚类），
  所以样例必须覆盖：有书签的、没书签但有目录页的、以及最坏情况（扫描版，压根没文字）。
  另外正文刻意混入页眉页脚和独立页码行 —— 这些噪声不该被念出来。

输出：D:/dev/projects/Readaloud/samples/*.pdf
"""
import os

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Flowable, Image, PageBreak, Paragraph, SimpleDocTemplate, Spacer

OUT_DIR = r'D:/dev/projects/Readaloud/samples'
FONT_PATH = 'C:/Windows/Fonts/simhei.ttf'

pdfmetrics.registerFont(TTFont('CN', FONT_PATH))

BODY = ParagraphStyle('body', fontName='CN', fontSize=10.5, leading=19, spaceAfter=6)
H1 = ParagraphStyle('h1', fontName='CN', fontSize=16, leading=26, spaceBefore=6, spaceAfter=12)
H2 = ParagraphStyle('h2', fontName='CN', fontSize=12.5, leading=20, spaceBefore=8, spaceAfter=6)
TOC = ParagraphStyle('toc', fontName='CN', fontSize=10.5, leading=22)


class Bookmark(Flowable):
    """在当前位置插一条 PDF 书签（outline entry）—— L1 路径的测试素材。"""

    def __init__(self, key, title, level=0):
        super().__init__()
        self.key, self.title, self.level = key, title, level
        self.width = self.height = 0

    def draw(self):
        self.canv.bookmarkPage(self.key)
        self.canv.addOutlineEntry(self.title, self.key, level=self.level, closed=False)


def header_footer(canvas, doc):
    """页眉 + 页脚页码 —— 故意加进去的噪声，好的解析器应该把它剔掉。"""
    canvas.saveState()
    canvas.setFont('CN', 7.5)
    canvas.setFillColor(colors.HexColor('#888888'))
    canvas.drawString(20 * mm, A4[1] - 14 * mm, '内部资料 · 数据安全合规审查报告')
    canvas.drawRightString(A4[0] - 20 * mm, 14 * mm, f'第 {doc.page} 页')
    canvas.drawCentredString(A4[0] / 2, 14 * mm, '- %d -' % doc.page)
    canvas.restoreState()


# ── 报告内容（原创虚构；混入数字、百分比、英文缩写、日期，考验正文清洗）──
SECTIONS = [
    ('第一章 审查概况', 0, [
        ('1.1 审查范围', 1, [
            '本次审查覆盖 2024 年 1 月至 2024 年 12 月期间，公司全部业务系统所产生的个人数据与业务数据。',
            '纳入范围的一级系统共 17 个，其中涉及用户身份信息的有 9 个，涉及资金流水记录的有 4 个。',
            '审查期间共抽检数据接口 236 个，抽样比例约 12.5%，高于 ISO 27001 要求的 10% 下限。',
        ]),
        ('1.2 审查方法', 1, [
            '采用文档核查、配置比对与流量抽样三种方式并行。其中配置比对依托内部 CMDB 系统自动完成。',
            '对于第三方 SDK 的运行行为，我们部署了沙箱环境进行为期 14 天的持续观测。',
            '观测结果显示，共有 3 个 SDK 在用户未授权的情况下调用了设备标识接口，详见第二章。',
        ]),
    ]),
    ('第二章 主要发现', 0, [
        ('2.1 数据分类分级', 1, [
            '抽样范围内，标注为「重要数据」的字段共 412 个，其中 28 个字段的标注与实际内容不符。',
            '典型问题是将完整的身份证号错误地归类为「一般个人信息」，导致加密强度不足。',
            '建议在 2025 年第一季度前，完成全部字段的分级复核，并同步更新数据字典。',
        ]),
        ('2.2 跨境传输', 1, [
            '审查发现 2 条跨境数据链路未按规定完成申报，分别指向新加坡节点与法兰克福节点。',
            '两条链路的日均传输量约为 3.4GB，主要承载日志与埋点数据。',
            '依据《数据出境安全评估办法》，上述情形应当在传输前完成安全评估，目前已暂停传输。',
        ]),
        ('2.3 第三方 SDK', 1, [
            '3 个 SDK 存在越权采集行为，其中 2 个已在 2024 年 11 月完成整改。',
            '剩余 1 个 SDK 的开发方已停止维护，建议直接移除并替换为自研方案。',
        ]),
    ]),
    ('第三章 整改建议', 0, [
        ('3.1 短期措施', 1, [
            '立即暂停未申报的两条跨境链路，并在 15 个工作日内补交评估材料。',
            '对 28 个分级不准确的字段加密升级，预计工作量约 40 人日。',
        ]),
        ('3.2 长期机制', 1, [
            '建立数据分级复核的季度例会制度，由数据治理委员会牵头。',
            '将 SDK 准入纳入采购流程，未经安全评估的第三方组件不得上线。',
            '把本次审查中形成的 12 项检查项固化为自动化巡检规则。',
        ]),
    ]),
]

TOC_LINES = [
    '第一章 审查概况 ………………………………………………… 1',
    '1.1 审查范围 ………………………………………………………… 1',
    '1.2 审查方法 ………………………………………………………… 1',
    '第二章 主要发现 ………………………………………………… 2',
    '2.1 数据分类分级 …………………………………………………… 2',
    '2.2 跨境传输 ………………………………………………………… 2',
    '2.3 第三方 SDK ……………………………………………………… 2',
    '第三章 整改建议 ………………………………………………… 3',
    '3.1 短期措施 ………………………………………………………… 3',
    '3.2 长期机制 ………………………………………………………… 3',
]


def make_report(path, with_bookmarks, with_toc_page):
    doc = SimpleDocTemplate(
        path, pagesize=A4,
        leftMargin=22 * mm, rightMargin=22 * mm,
        topMargin=22 * mm, bottomMargin=22 * mm,
        title='数据安全合规审查报告', author='合规部',
    )
    story = []
    if with_toc_page:
        # 目录页：没有书签的电子书经常靠这一页提供目录（测 L2 路径）
        story.append(Paragraph('目　录', H1))
        story.append(Spacer(1, 4 * mm))
        for line in TOC_LINES:
            story.append(Paragraph(line, TOC))
        story.append(PageBreak())

    for title, level, subs in SECTIONS:
        story.append(Bookmark('bm_' + title, title, 0) if with_bookmarks else Spacer(0, 0))
        story.append(Paragraph(title, H1))
        for sub_title, sub_level, paras in subs:
            story.append(Bookmark('bm_' + sub_title, sub_title, 1) if with_bookmarks else Spacer(0, 0))
            story.append(Paragraph(sub_title, H2))
            for p in paras:
                story.append(Paragraph(p, BODY))
        story.append(PageBreak())

    doc.build(story, onFirstPage=header_footer, onLaterPages=header_footer)
    print('生成:', path, os.path.getsize(path), 'bytes')


def make_scanned(path):
    """扫描版：整页都是图，一个字都抽不出来 —— 用来验证「给用户一条活路」的失败提示。"""
    from PIL import Image as PILImage, ImageDraw

    # 先在内存里画一张"扫描件"（白底 + 灰底纹 + 几行黑条模拟文字）
    w, h = 1240, 1754
    img = PILImage.new('RGB', (w, h), (252, 251, 248))
    d = ImageDraw.Draw(img)
    import random
    random.seed(7)
    for _ in range(90):                                   # 模拟扫描噪点
        x, y = random.randint(0, w), random.randint(0, h)
        g = random.randint(200, 240)
        d.rectangle([x, y, x + random.randint(1, 6), y + 1], fill=(g, g, g))
    y = 170
    d.rectangle([150, y, 760, y + 34], fill=(38, 38, 40))  # 标题条
    y += 90
    for i in range(26):                                     # 正文行
        if i and i % 9 == 0:
            y += 30
        lw = random.randint(700, 940)
        d.rectangle([150, y, 150 + lw, y + 18], fill=(60, 60, 64))
        y += 42
    tmp_png = os.path.join(OUT_DIR, '_scan_tmp.png')
    img.save(tmp_png)

    # 直接用 canvas 满页绘制（platypus 会按页边距裁，图片会放不下）
    from reportlab.pdfgen import canvas as rl_canvas
    c = rl_canvas.Canvas(path, pagesize=A4)
    c.drawImage(tmp_png, 0, 0, width=A4[0], height=A4[1])
    c.showPage()
    c.save()
    os.remove(tmp_png)
    print('生成:', path, os.path.getsize(path), 'bytes')


# ── L3 专用内容：既没有书签，也没有目录页，标题只能靠字号/编号认出来 ──
#    刻意用「一、」「1.」这种 CHAPTER_REGEX（只认"第X章/1.2"）认不出来的写法，
#    逼着解析器用**版式信号**（字号 16 / 12.5 / 正文 10.5）去判断谁是标题。
PLAIN_SECTIONS = [
    ('一、总体要求', 0, [
        '本方案面向内部离线场景，全部处理过程均不依赖网络连接，确保资料不外流。',
        '系统需要在普通手机上流畅运行，内存占用控制在可接受范围内，避免影响其他应用。',
    ]),
    ('二、技术实现', 0, [
        ('1. 数据抽取', 1, [
            '文本抽取采用逐页流式处理，避免将整份文档一次性读入内存。',
            '抽取过程中同步记录每一页的字符偏移，供后续目录定位使用。',
        ]),
        ('2. 异常处理', 1, [
            '遇到加密文档或结构异常时，捕获异常并给出可操作的提示，而不是直接崩溃。',
            '解析进度实时上报，用户在等待过程中可以看到已完成的页数。',
        ]),
        ('3. 性能优化', 1, [
            '合成阶段按段落切分并后台预取，播放端负责变速，避免重复合成。',
            '已合成音频缓存在应用私有目录，应用重启后可继续复用。',
        ]),
    ]),
    ('三、后续计划', 0, [
        '下一阶段补充多音字词库，并按实际听感逐条补充读音纠正条目。',
        '打包阶段将输出内置模型与外置模型两个版本，分别面向不同使用场景。',
    ]),
]


def make_plain(path):
    """L3 样例：无书签、无目录页，只有正文里的分级标题（靠字号与编号区分）。"""
    doc = SimpleDocTemplate(
        path, pagesize=A4,
        leftMargin=22 * mm, rightMargin=22 * mm,
        topMargin=22 * mm, bottomMargin=22 * mm,
        title='离线资料朗读方案', author='技术组',
    )
    story = []
    for title, level, items in PLAIN_SECTIONS:
        story.append(Paragraph(title, H1))
        for it in items:
            if isinstance(it, str):
                story.append(Paragraph(it, BODY))
            else:
                sub_title, sub_level, paras = it
                story.append(Paragraph(sub_title, H2))
                for p in paras:
                    story.append(Paragraph(p, BODY))
        story.append(Spacer(1, 6 * mm))

    doc.build(story, onFirstPage=header_footer, onLaterPages=header_footer)
    print('生成:', path, os.path.getsize(path), 'bytes')


if __name__ == '__main__':
    os.makedirs(OUT_DIR, exist_ok=True)
    make_report(os.path.join(OUT_DIR, '合规报告-带书签.pdf'), with_bookmarks=True, with_toc_page=False)
    make_report(os.path.join(OUT_DIR, '合规报告-无书签有目录页.pdf'), with_bookmarks=False, with_toc_page=True)
    make_plain(os.path.join(OUT_DIR, '技术方案-无书签无目录.pdf'))
    make_scanned(os.path.join(OUT_DIR, '扫描版样例-无文字层.pdf'))
