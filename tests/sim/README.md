# tests/sim — 폰 없이 덧그리기 모양 확인

안드로이드 앱의 라벨 규칙(`Labeler.kt`)과 덮어쓰기 그리기(`OverlayView.drawCover`)를 파이썬으로 똑같이 옮겨 두고,
스크린샷 위에 결과를 그려서 **눈으로 확인한 뒤에** 폰에 올린다.

```sh
pip install rapidocr-onnxruntime pillow        # 로컬 OCR (ML Kit 대용) + 그리기
python3 tests/sim/ocr.py shot.jpg shot.ocr.json  # 스크린샷 → OCR 줄·상자 json
python3 tests/sim/render.py shot.jpg shot.ocr.json out.png   # 라벨을 덮어 그린 그림 + 줄별 라벨 목록
```

- 로컬 OCR은 ML Kit과 다르게 읽을 수 있다. 실제 폰 결과를 재현하려면 치판 창 → '읽은 글자' → **[OCR 결과 복사(JSON)]**로 받은 json을 `render.py`에 그대로 넣는다 (형식이 같다).
- `labeler.py` 와 `Labeler.kt`, `render.py` 와 `drawCover` 는 규칙이 같아야 한다. 한쪽을 바꾸면 다른 쪽도 바꾼다.
- 폰트: Noto Sans CJK (`apt install fonts-noto-cjk`). 이모지는 로컬에서 네모로 보이지만 폰에서는 제대로 나온다.
