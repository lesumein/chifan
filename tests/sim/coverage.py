"""번역율 재기: 한 줄에 하나씩 적힌 말뭉치(또는 *.ocr.json)를 넣으면 라벨이 붙은 줄 비율과 직역(글자 뜻) 비율을 낸다.
가격으로 시작하는 줄과 한자가 없는 줄은 대상에서 뺀다.
  python3 tests/sim/coverage.py 말뭉치.txt [다른.txt ...] [화면.ocr.json ...]  [-v: 못 붙인 줄 보기]"""
import sys, os, json
sys.path.insert(0, os.path.dirname(__file__))
from labeler import Dict, label, is_han, PRICE_START, HANJA
d = Dict()
verbose = '-v' in sys.argv
for path in [a for a in sys.argv[1:] if a != '-v']:
    lines = [l['text'] for l in json.load(open(path, encoding='utf-8'))] if path.endswith('.json') else open(path, encoding='utf-8').read().split('\n')
    lines = [l.strip() for l in lines if l.strip() and any(is_han(c) for c in l) and not PRICE_START.match(l.strip())]
    ok = lit = 0; miss = []
    for l in lines:
        r = label(d, l)
        if r:
            ok += 1; lit += r[1] == HANJA
        else:
            miss.append(l)
    n = max(len(lines), 1)
    print(f"{path}: {ok}/{len(lines)} = {ok / n * 100:.2f}%  (직역 {lit / n * 100:.1f}%)")
    if verbose and miss:
        print('  못 붙임:', ' / '.join(miss))
