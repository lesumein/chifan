"""
OCR 결과(json: [{text, box:[l,t,r,b]}]) 를 라벨로 바꿔 스크린샷 위에 덮어 그린다.
android/.../OverlayView.kt drawCover 와 같은 규칙.
사용: python3 tests/sim/render.py <screenshot.jpg> <ocr.json> <out.png>
"""
import json, sys, os
from PIL import Image, ImageDraw, ImageFont
sys.path.insert(0, os.path.dirname(__file__))
from labeler import Dict, label, FOOD, DICT

FONT = '/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc'
BAR = {DICT: (194, 70, 46), FOOD: (162, 80, 46)}


def _trim(draw, text, font, avail):
    # 안 들어가면 먼저 띄어쓰기를 빼 보고("월 0 판매"→"월0판매"), 그래도 안 되면 …로 자른다
    if ' ' in text and ' · ' not in text and draw.textlength(text.replace(' ', ''), font=font) <= avail:
        return text.replace(' ', '')
    while draw.textlength(text, font=font) > avail and len(text) > 2:
        text = text[:-2] + '…'
    return text


def render(img_path, ocr_path, out_path, dp=None):
    img = Image.open(img_path).convert('RGB')
    W, H = img.size
    dp = dp or W / 412.0          # 보통 폰: 412dp 폭
    dic = Dict()
    lines = json.load(open(ocr_path, encoding='utf-8'))
    draw = ImageDraw.Draw(img, 'RGBA')
    drawn = []
    report = []
    from paragraph import group, split_label
    paras = group(lines, is_word=lambda w: dic.exact(w) is not None)
    boxes = [p['box'] for p in paras]
    for pr in sorted(paras, key=lambda p: (p['box'][1], p['box'][0])):
        l, t, r, b = pr['box']
        n = pr['n']; row_h = (b - t) / n
        # 오른쪽으로 넓힐 수 있는 한계: 같은 높이에 있는 다음 글자 상자 직전까지
        limit = W; llimit = 0
        for o in boxes:
            if o is pr['box'] or o[3] < t or o[1] > b:
                continue
            if o[0] >= r - 2:
                limit = min(limit, o[0] - 4 * dp)
            elif o[2] <= l + 2:
                llimit = max(llimit, o[2] + 4 * dp)
        res = label(dic, pr['text'])
        report.append((pr['text'], res[0] if res else None))
        if not res:
            continue
        text, kind = res
        if row_h < 6 * dp or (r - l) < 8 * dp:
            continue
        box = [l - 3 * dp, t - 2 * dp, min(r + 3 * dp, W), b + 2 * dp]
        skip = False
        for d in drawn:
            ix = max(0, min(box[2], d[2]) - max(box[0], d[0])); iy = max(0, min(box[3], d[3]) - max(box[1], d[1]))
            if ix * iy > 0.3 * (box[2] - box[0]) * (box[3] - box[1]):
                skip = True; break
        if skip:
            continue
        rows = split_label(text, n)
        if len(rows) < n:      # 조각이 줄 수보다 적으면 윗줄만 덮는다
            box[3] = box[1] + 4 * dp + row_h * len(rows)
        minsz = min(8 * dp, row_h * 0.95)   # 원문이 아주 작으면 최소 크기도 줄 높이 안으로 (넘치면 아래 라벨에 가려 잘린다)
        size = max(minsz, min(17 * dp, row_h * 0.72))
        font = ImageFont.truetype(FONT, int(size))
        avail = (box[2] - box[0]) - 11 * dp
        w = max(draw.textlength(rw, font=font) for rw in rows)
        if w > avail:
            # 넓히기: 먼저 오른쪽(다음 글자 직전까지), 모자라면 왼쪽(앞 글자·앞 라벨 직후까지) — 가운데 정렬된 칸(분류 표)에 빈 곳이 많다
            extra = min(w - avail, limit - box[2])
            if extra > 0:
                box[2] += extra
            for d in drawn:
                if not (d[3] < box[1] or d[1] > box[3]) and d[2] <= box[0] + 2:
                    llimit = max(llimit, d[2] + 2 * dp)
            need = w - ((box[2] - box[0]) - 11 * dp)
            if need > 0:
                box[0] -= max(0, min(need, box[0] - llimit, 3 * row_h))   # 왼쪽은 줄 높이 3배까지만 (OCR은 사진을 모른다: 썸네일 위로 뻗지 않게)
            avail = (box[2] - box[0]) - 11 * dp
        if w > avail:
            # 그래도 안 되면 자르기 전에 간격부터 줄인다 ("쓰촨 · 간식" → "쓰촨·간식")
            rows = [rw.replace(' · ', '·') for rw in rows]
            w = max(draw.textlength(rw, font=font) for rw in rows)
        while w > avail and size > minsz:
            size -= 1 * dp; font = ImageFont.truetype(FONT, int(size)); w = max(draw.textlength(rw, font=font) for rw in rows)
        rows = [rw if draw.textlength(rw, font=font) <= avail else _trim(draw, rw, font, avail) for rw in rows]
        draw.rounded_rectangle(box, radius=5 * dp, fill=(43, 37, 34, 255))
        draw.rounded_rectangle([box[0], box[1], box[0] + 3 * dp, box[3]], radius=2 * dp, fill=BAR[kind])
        asc, desc = font.getmetrics()
        for i, rw in enumerate(rows):
            cy = box[1] + 2 * dp + row_h * (i + 0.5)
            draw.text((box[0] + 7 * dp, cy), rw, font=font, fill=(255, 255, 255), anchor='lm')
        drawn.append(box)
    img.save(out_path)
    return report


if __name__ == '__main__':
    rep = render(sys.argv[1], sys.argv[2], sys.argv[3])
    for zh, ko in rep:
        print(f"{zh:<28} → {ko if ko else '—'}")
