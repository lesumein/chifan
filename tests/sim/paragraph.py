"""두 줄로 접힌 이름을 한 문단으로 합친다 (Paragraphs.kt 와 같은 규칙).
같은 열(가로 겹침 60% 이상)에서 바로 아래(세로 간격이 줄 높이의 0.8배 이내)에 있고 글자 높이가 비슷(30% 이내)하고,
**사전의 말이 줄 경계에 걸쳐 있을 때만**(春龙抬首鸿|运当头 의 鸿运) 합친다 — 제목 + 지점 이름(锦芳小吃|艾窝窝 / 鲜鱼口店)처럼
모양만 같은 두 줄을 합치면 라벨이 엉뚱한 줄까지 덮는다. OCR 상자 높이는 같은 글꼴도 20%쯤 들쭉날쭉이라 높이로는 못 가른다."""
import re


def is_han(c):
    return '㐀' <= c <= '鿿'


def join(a, b):
    if a and b and (not is_han(a[-1]) or not is_han(b[0])) and a[-1].isalnum() and b[0].isalnum():
        return a + ' ' + b
    return a + b


def straddles(is_word, a, b, max_len=8):
    """a+b 의 경계에 걸친 사전 말(2자 이상)이 있나"""
    a = ''.join(c for c in a if is_han(c))[-(max_len - 1):]
    b = ''.join(c for c in b if is_han(c))[:max_len - 1]
    j = a + b; bi = len(a)
    for st in range(max(0, bi - max_len + 1), bi):
        for en in range(bi + 1, min(len(j), st + max_len) + 1):
            if en - st >= 2 and is_word(j[st:en]):
                return True
    return False


def group(lines, is_word=lambda w: False):
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
            if not straddles(is_word, p['text'], ln['text']):
                continue
            p['text'] = join(p['text'], ln['text'].strip())
            p['box'] = [min(l, pl), min(t, pt), max(r, pr), max(b, pb)]
            p['rows'].append(ln['box']); p['n'] += 1
            p['parts'].append((ln['text'].strip(), list(ln['box'])))
            attached = True
            break
        if not attached:
            paras.append({'text': ln['text'].strip(), 'box': list(ln['box']), 'rows': [ln['box']], 'n': 1,
                          'parts': [(ln['text'].strip(), list(ln['box']))]})
    return paras


def split_label(text, n):
    """라벨 글을 ' · ' 조각 단위로 n 줄에 고르게 나눈다"""
    if n <= 1:
        return [text]
    parts = text.split(' · ')
    if len(parts) < n:
        parts = text.split(' ')          # 조각이 모자라면 띄어쓰기 단위로 ("2월 2일 용두절" → "2월 2일" / "용두절")
        sep = ' '
        if len(parts) < n:
            return [text]                # 그것도 모자라면 한 줄로 (글자를 쪼개 '간/식'으로 만들지 않는다)
    else:
        sep = ' · '
    total = len(text); target = total / n
    rows, cur = [], ''
    for i, prt in enumerate(parts):
        cand = prt if not cur else cur + sep + prt
        remaining_rows = n - len(rows)
        # 넘치는 쪽이 모자란 쪽보다 크면 여기서 줄을 바꾼다 (줄 길이를 고르게)
        if cur and len(cand) > target and (len(cand) - target) > (target - len(cur)) and remaining_rows > 1 and (len(parts) - i) >= remaining_rows - 1:
            rows.append(cur); cur = prt
        else:
            cur = cand
    rows.append(cur)
    while len(rows) > n:
        rows[-2] = rows[-2] + sep + rows[-1]; rows.pop()
    return rows
