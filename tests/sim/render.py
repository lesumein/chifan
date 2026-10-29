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


def render(img_path, ocr_path, out_path, dp=None):
    img = Image.open(img_path).convert('RGB')
    W, H = img.size
    dp = dp or W / 412.0          # 보통 폰: 412dp 폭
    dic = Dict()
    lines = json.load(open(ocr_path, encoding='utf-8'))
    draw = ImageDraw.Draw(img, 'RGBA')
    drawn = []
    report = []
    boxes = [ln['box'] for ln in lines]
    for ln in sorted(lines, key=lambda l: (l['box'][1], l['box'][0])):
        l, t, r, b = ln['box']
        # 오른쪽으로 넓힐 수 있는 한계: 같은 높이에 있는 다음 글자 상자 직전까지
        limit = W
        for o in boxes:
            if o is ln['box']:
                continue
            if o[0] >= r - 2 and not (o[3] < t or o[1] > b):
                limit = min(limit, o[0] - 4 * dp)
        res = label(dic, ln['text'])
        report.append((ln['text'], res[0] if res else None))
        if not res:
            continue
        text, kind = res
        bh, bw = b - t, r - l
        if bh < 6 * dp or bw < 9 * dp:
            continue
        box = [l - 3 * dp, t - 2 * dp, min(r + 3 * dp, W), b + 2 * dp]
        # 이미 덮은 상자와 30% 넘게 겹치면 건너뜀
        skip = False
        for d in drawn:
            ix = max(0, min(box[2], d[2]) - max(box[0], d[0])); iy = max(0, min(box[3], d[3]) - max(box[1], d[1]))
            if ix * iy > 0.3 * (box[2] - box[0]) * (box[3] - box[1]):
                skip = True; break
        if skip:
            continue
        size = max(9 * dp, min(17 * dp, bh * 0.72))
        font = ImageFont.truetype(FONT, int(size))
        avail = (box[2] - box[0]) - 11 * dp
        w = draw.textlength(text, font=font)
        while w > avail and size > 9 * dp:
            size -= 1 * dp; font = ImageFont.truetype(FONT, int(size)); w = draw.textlength(text, font=font)
        if w > avail:
            extra = min(w - avail, limit - box[2])
            if extra > 0:
                box[2] += extra
        avail = (box[2] - box[0]) - 11 * dp
        while draw.textlength(text, font=font) > avail and len(text) > 2:
            text = text[:-2] + '…'
        draw.rounded_rectangle(box, radius=5 * dp, fill=(255, 253, 249, 247))
        draw.rounded_rectangle([box[0], box[1], box[0] + 3 * dp, box[3]], radius=2 * dp, fill=BAR[kind])
        asc, desc = font.getmetrics()
        y = (box[1] + box[3]) / 2 - (asc - desc) / 2
        draw.text((box[0] + 7 * dp, y), text, font=font, fill=(42, 33, 28))
        drawn.append(box)
    img.save(out_path)
    return report


if __name__ == '__main__':
    rep = render(sys.argv[1], sys.argv[2], sys.argv[3])
    for zh, ko in rep:
        print(f"{zh:<28} → {ko if ko else '—'}")
