"""스크린샷 → OCR 줄 목록 json ([{text, box:[l,t,r,b], conf}]). 로컬 확인용 (ML Kit 대용).
사용: python3 tests/sim/ocr.py <screenshot.jpg> <out.json>"""
import json, sys
from rapidocr_onnxruntime import RapidOCR

res, _ = RapidOCR()(sys.argv[1])
lines = []
for box, text, conf in (res or []):
    xs = [p[0] for p in box]; ys = [p[1] for p in box]
    lines.append({"text": text, "box": [int(min(xs)), int(min(ys)), int(max(xs)), int(max(ys))], "conf": round(float(conf), 2)})
json.dump(lines, open(sys.argv[2], 'w'), ensure_ascii=False, indent=0)
print(len(lines), '줄')
