"""두 줄로 접힌 이름을 한 문단으로 합친다 (CaptureService.groupParagraphs 와 같은 규칙).
같은 열(가로 겹침 60% 이상)에서 바로 아래(세로 간격이 줄 높이의 0.8배 이내)에 있고 글자 높이가 비슷(30% 이내)하면 같은 문단."""
import re


def is_han(c):
    return '㐀' <= c <= '鿿'


def join(a, b):
    if a and b and (not is_han(a[-1]) or not is_han(b[0])) and a[-1].isalnum() and b[0].isalnum():
        return a + ' ' + b
    return a + b


def group(lines):
    """lines: [{text, box}] → [{text, box, n, rows:[box...]}]"""
    paras = []
    for ln in sorted(lines, key=lambda l: (l['box'][1], l['box'][0])):
        l, t, r, b = ln['box']; h = b - t
        attached = False
        for p in paras:
            pl, pt, pr, pb = p['box']; ph = p['rows'][-1][3] - p['rows'][-1][1]
            ov = min(r, pr) - max(l, pl)
            if ov <= 0 or ov < 0.6 * min(r - l, pr - pl):
                continue
            gap = t - pb
            if gap < -0.2 * h or gap > 0.8 * h:
                continue
            if abs(h - ph) > 0.3 * max(h, ph):
                continue
            p['text'] = join(p['text'], ln['text'].strip())
            p['box'] = [min(l, pl), min(t, pt), max(r, pr), max(b, pb)]
            p['rows'].append(ln['box']); p['n'] += 1
            attached = True
            break
        if not attached:
            paras.append({'text': ln['text'].strip(), 'box': list(ln['box']), 'rows': [ln['box']], 'n': 1})
    return paras


def split_label(text, n):
    """라벨 글을 ' · ' 조각 단위로 n 줄에 고르게 나눈다"""
    if n <= 1:
        return [text]
    parts = text.split(' · ')
    if len(parts) < n:
        # 조각이 모자라면 글자 수로 나눈다
        step = max(1, -(-len(text) // n))
        return [text[i:i + step] for i in range(0, len(text), step)][:n]
    total = len(text); target = total / n
    rows, cur = [], ''
    for i, prt in enumerate(parts):
        cand = prt if not cur else cur + ' · ' + prt
        remaining_rows = n - len(rows)
        if cur and len(cand) > target and remaining_rows > 1 and (len(parts) - i) >= remaining_rows - 1:
            rows.append(cur); cur = prt
        else:
            cur = cand
    rows.append(cur)
    while len(rows) > n:
        rows[-2] = rows[-2] + ' · ' + rows[-1]; rows.pop()
    return rows
