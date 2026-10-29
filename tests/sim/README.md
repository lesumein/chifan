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

## 가짜 화면 묶음 (fixtures/screens.html)

메이퇀 H5는 해외 IP에서 가게 목록을 내려주지 않아, 흔한 화면 구조를 직접 만든 7장(가게 목록·메뉴·옵션 창·주문 확인·배달 상태·밀크티 메뉴·공동구매 격자)으로 회귀 확인한다.
```sh
node tests/sim/shoot.js /tmp/fx            # 폰 크기로 캡처
python3 tests/sim/batch.py /tmp/fx /tmp/fxout   # OCR → 라벨 → 덮어 그리기, sheet.png 와 못 붙인 줄 목록
```

- `paragraph.py`: 두 줄로 접힌 이름을 한 문단으로 합치는 규칙 (`Paragraphs.kt`와 같아야 한다). `render.py`가 쓴다.

- `coverage.py`: 번역율(라벨이 붙은 줄 비율)과 직역 비율을 잰다. 말뭉치 txt나 `*.ocr.json`을 넣는다.
- `parity.py`: 말뭉치 → `원문\t라벨` 표. Kotlin `LabelerParityTest`가 이 표와 한 줄씩 대조한다:
  `python3 tests/sim/parity.py 말뭉치.txt > /tmp/p.tsv && cd android && CHIFAN_PARITY=/tmp/p.tsv ./gradlew testDebugUnitTest`
- 말뭉치(웹에서 모은 메뉴 이름·화면 글자)는 다른 서비스의 글이라 레포에 넣지 않는다.
