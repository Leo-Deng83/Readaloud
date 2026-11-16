# -*- coding: utf-8 -*-
"""
真机端到端验证（数字被读断的问题）。

做四件事：
  1. 用 App 打开带书签的 PDF 样例（会自动开始朗读）
  2. 把日志里"实际送去合成的文本"打出来（前 3 段全文）+ 有没有"模型不认识的字"的告警
  3. 把界面上的句子抓出来，确认日期没有被版式换行劈成两句
  4. **把手机合成出来的那段 wav 拉回电脑**，用波形分析找内部停顿
     —— 这是最硬的证据：手机上的引擎到底把哪句话读断了，一测就知道
"""
import os
import re
import subprocess
import sys
import time

ADB = r'D:/Android/Sdk/platform-tools/adb.exe'
PKG = 'top.readaloud.app'
PDF = '合规报告-带书签.pdf'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'out')


def run(args, binary=False):
    """
    ⚠️ 拉二进制必须走 `exec-out`（踩过）：
       `adb shell cat 文件` 在 Windows 上会破坏二进制数据 ——
       拉回来的 wav 每个采样都是满刻度，听起来是"爆音、没有内容"。
       exec-out 是专给二进制用的通道，不做换行/编码转换。
    """
    cmd = [ADB, 'exec-out'] + args if binary else [ADB] + args
    r = subprocess.run(cmd, capture_output=True)
    if binary:
        return r.stdout or b''
    return ((r.stdout or b'').decode('utf-8', 'replace') +
            (r.stderr or b'').decode('utf-8', 'replace')).strip()


def main():
    os.makedirs(OUT, exist_ok=True)

    print('=== 1) 设备与文件 ===')
    print(run(['devices']))
    files = run(['shell', 'ls', f'/sdcard/Android/data/{PKG}/files/'])
    print(files)
    if PDF not in files:
        print(f'⚠️ 样例 {PDF} 不在 App 目录，先推一份')
        tmp = '/sdcard/Android/data/%s/files/_tmp.pdf' % PKG
        run(['push', os.path.join(os.path.dirname(os.path.dirname(
            os.path.dirname(os.path.abspath(__file__)))), 'samples', PDF), tmp])
        run(['shell', 'mv', tmp, f'/sdcard/Android/data/{PKG}/files/{PDF}'])

    print()
    print('=== 2) 打开并开始朗读 ===')
    run(['logcat', '-c'])
    run(['shell', 'am', 'start', '-S', '-n', f'{PKG}/.MainActivity',
         '--es', 'openfile',
         f'file:///sdcard/Android/data/{PKG}/files/{PDF}'])
    print('等待合成前几段（约 75 秒）…')
    time.sleep(75)

    log = run(['logcat', '-d', '-s', 'ReadaloudPlay', 'KokoroTts', 'ReadaloudPdf',
               'ReadaloudBook'])

    print()
    print('=== 3) 实际送去合成的文本（前 3 段）===')
    for line in log.splitlines():
        if '【朗读文本】' in line or '【原文对照】' in line:
            print('  ' + line.split(': ', 1)[-1])

    print()
    print('=== 4) 告警 / 关键词 ===')
    warn = [l for l in log.splitlines() if '不认识' in l or 'Unknown token' in l]
    print('  模型不认识的字：' + ('\n  '.join(warn) if warn else '无 ✅'))
    for kw in ['去掉版式硬换行', '结构噪声清理', 'PDF 解析完成']:
        for l in log.splitlines():
            if kw in l:
                print('  ' + l.split(': ', 1)[-1])
                break

    print()
    print('=== 5) 界面上的句子（看日期有没有被劈开）===')
    run(['shell', 'uiautomator', 'dump', '/sdcard/ui.xml'])
    xml = run(['shell', 'cat', '/sdcard/ui.xml'])
    texts = [t for t in re.findall(r'text="([^"]*)"', xml) if t.strip()]
    hit = [t for t in texts if '审查覆盖' in t or '2024' in t]
    for t in hit:
        print('  ' + repr(t[:110]))
    if not hit:
        print('  （视口里没有这句话，往下滚再试）')

    print()
    print('=== 6) 拉取手机上合成的音频，测内部停顿 ===')
    listing = run(['shell', 'run-as', PKG, 'ls', 'cache/tts'])
    print('  缓存目录：', listing.replace('\n', ' '))
    segs = sorted(set(re.findall(r'(seg_\d+\.wav)', listing)),
                  key=lambda s: int(re.findall(r'\d+', s)[0]))
    if not segs:
        print('  ⚠️ 没找到合成音频')
        return

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from check_pauses import gaps
    import numpy as np
    import wave

    for name in segs[:4]:
        # ⚠️ exec-out 后面不要再带 "shell"（它自己就是不走 shell 通道的）
        raw = run(['run-as', PKG, 'cat', f'cache/tts/{name}'], binary=True)
        path = os.path.join(OUT, 'device_' + name)
        with open(path, 'wb') as f:
            f.write(raw)
        try:
            with wave.open(path, 'rb') as w:
                sr = w.getframerate()
                ch = w.getnchannels()
                sw = w.getsampwidth()
                frames = w.readframes(w.getnframes())
        except Exception as e:
            print(f'  {name}: 打不开 wav（{e}），文件头 {raw[:16]!r}')
            continue
        if sw == 2:
            s = np.frombuffer(frames, dtype='<i2').astype(np.float32) / 32768.0
        elif sw == 4:
            s = np.frombuffer(frames, dtype='<i4').astype(np.float32) / 2147483648.0
        else:
            print(f'  {name}: 采样位宽 {sw} 字节，跳过')
            continue
        if ch > 1:
            s = s.reshape(-1, ch).mean(axis=1)
        g, total = gaps(s, sr)
        long_gaps = [(p, d) for p, d in g if d >= 400]
        print(f'  {name}: 时长 {total:.0f} ms，内部停顿 {len(g)} 处，'
              f'其中 ≥400ms 的 {len(long_gaps)} 处 {[d for _, d in long_gaps]}')
    print()
    print(f'（拉回来的音频在 {OUT}，可以直接听）')


if __name__ == '__main__':
    main()
