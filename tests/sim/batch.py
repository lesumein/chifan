"""폴더 안의 스크린샷을 전부 OCR → 라벨 → 덮어 그리기 하고, 한 장(contact sheet)으로 모은다.
사용: python3 tests/sim/batch.py <스크린샷 폴더> <결과 폴더>"""
import os, sys, json, glob
sys.path.insert(0, os.path.dirname(__file__))
from render import render
from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
from rapidocr_onnxruntime import RapidOCR
ocr = RapidOCR()
outs = []
missing = {}
for f in sorted(glob.glob(os.path.join(src, '*.png')) + glob.glob(os.path.join(src, '*.jpg'))):
    name = os.path.splitext(os.path.basename(f))[0]
    oj = os.path.join(dst, name + '.ocr.json')
    if not os.path.exists(oj):
        res, _ = ocr(f)
        lines = []
        for box, text, conf in (res or []):
            xs = [p[0] for p in box]; ys = [p[1] for p in box]
            lines.append({"text": text, "box": [int(min(xs)), int(min(ys)), int(max(xs)), int(max(ys))], "conf": round(float(conf), 2)})
        json.dump(lines, open(oj, 'w'), ensure_ascii=False, indent=0)
    op = os.path.join(dst, name + '.out.png')
    rep = render(f, oj, op)
    outs.append(op)
    for zh, ko in rep:
        if ko is None and any('㐀' <= c <= '鿿' for c in zh):
            missing[zh] = missing.get(zh, 0) + 1
    print(f"== {name}: {sum(1 for _, k in rep if k)} 라벨 / {len(rep)} 줄")
    for zh, ko in rep:
        print(f"   {zh:<26} → {ko if ko else '—'}")

# contact sheet (3열)
ims = [Image.open(o) for o in outs]
cols = 3
w = max(i.width for i in ims); h = max(i.height for i in ims)
rows = (len(ims) + cols - 1) // cols
sheet = Image.new('RGB', (cols * w + (cols - 1) * 16, rows * h + (rows - 1) * 16), 'white')
for k, im in enumerate(ims):
    sheet.paste(im, ((k % cols) * (w + 16), (k // cols) * (h + 16)))
sheet = sheet.resize((sheet.width // 2, sheet.height // 2))
sheet.save(os.path.join(dst, 'sheet.png'))
print("\n라벨 못 붙인 줄 (한자 포함):")
for zh, n in sorted(missing.items(), key=lambda x: -x[1])[:60]:
    print(f"   {n}× {zh}")
