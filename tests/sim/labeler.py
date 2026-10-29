"""
android/.../Labeler.kt 와 같은 규칙의 파이썬 판. 폰 없이 라벨 결과와 화면 모양을 확인하는 용도.
규칙을 바꾸면 Kotlin 쪽도 같이 바꾼다 (tests/sim/README.md).
"""
import json, re, os

ROOT = os.path.join(os.path.dirname(__file__), '..', '..')
DICT, FOOD, MT, HANJA = 0, 1, 2, 3


def is_han(c):
    return '㐀' <= c <= '鿿'


class Dict:
    def __init__(self):
        d = lambda p: json.load(open(os.path.join(ROOT, 'data', p), encoding='utf-8'))
        self.map = {}
        for g in d('glossary.json')['items']:
            self.map[g['zh']] = (g['ko'], None)
        for r in d('requests.json')['items']:
            self.map[r['zh']] = (r['ko'], None)
        for f in d('foods.json'):
            ko = f['ko'] + (' ' + '🌶' * f['spicy'] if f.get('spicy') else '')
            self.map[f['zh']] = (ko, f['id'])
            if f.get('q') and f['q'] != f['zh']:
                self.map[f['q']] = (f['ko'], f['id'])
        self.max_len = max(len(k) for k in self.map)
        self.hanja = d('hanja.json')

    def exact(self, zh):
        return self.map.get(zh)


def strip_note(ko):
    s = re.sub(r'\s*[(（][^)）]*[)）]', '', ko).strip()
    return s or ko


RULES = [
    (re.compile(r'(\d+(?:\.\d+)?)折'), lambda m: f"-{round((10 - float(m.group(1))) * 10)}%" if 0.1 <= float(m.group(1)) <= 9.9 else None),
    (re.compile(r'已减(\d+(?:\.\d+)?)元'), lambda m: f"{m.group(1)}위안 할인됨"),
    (re.compile(r'半年售\s*(\d+)\+?'), lambda m: f"반년 {m.group(1)}+ 판매"),
    (re.compile(r'月售\s*(\d+)\+?'), lambda m: f"월 {m.group(1)}+ 판매"),
    (re.compile(r'年售\s*(\d+)\+?'), lambda m: f"연 {m.group(1)}+ 판매"),
    (re.compile(r'(\d+)%\s*好评'), lambda m: f"좋은 평 {m.group(1)}%"),
    (re.compile(r'(\d+)选(\d+)'), lambda m: f"{m.group(1)}택{m.group(2)}"),
    (re.compile(r'(\d+)人餐'), lambda m: f"{m.group(1)}인 세트"),
    (re.compile(r'[¥￥]\s*\d+(?:\.\d+)?'), lambda m: None),   # 가격은 그대로 읽히니 라벨 없음
    (re.compile(r'\d+(?:\.\d+)?'), lambda m: None),
]


def longest(dic, s, i):
    n = min(dic.max_len, len(s) - i)
    while n >= 1:
        w = s[i:i + n]
        if all(is_han(c) for c in w):
            h = dic.exact(w)
            if h:
                if n > 1:
                    return w, h
                prev_han = i > 0 and is_han(s[i - 1])
                next_han = i + 1 < len(s) and is_han(s[i + 1])
                if not prev_han and not next_han:
                    return w, h
        n -= 1
    return None


def label(dic, raw):
    """한 줄 → (라벨 글, kind) 또는 None.
    사전 조각(2자 이상) 또는 숫자 규칙이 하나는 있어야 라벨을 붙인다.
    한자음은 덮어쓰기 라벨에서는 쓰지 않는다 (잘못 읽힌 글자·사진 속 글씨가 전부 소음이 됨). '읽은 글자' 탭에서만 보여 준다."""
    s = raw.strip()
    if not any(is_han(c) for c in s):
        return None
    if re.search(r'[¥￥]\s*\d', s):
        return None   # 가격이 섞인 줄은 덮지 않는다 (가격이 더 중요)
    parts, kinds = [], []
    strong = 0   # 2자 이상 사전 조각 또는 규칙
    i = 0
    while i < len(s):
        matched = False
        for re_, f in RULES:
            m = re_.match(s, i)
            if not m:
                continue
            t = f(m)
            if t:
                parts.append(t); kinds.append(DICT); strong += 1
            i = m.end(); matched = True
            break
        if matched:
            continue
        c = s[i]
        if not is_han(c):
            i += 1; continue
        hit = longest(dic, s, i)
        if hit:
            w, (ko, food) = hit
            parts.append(strip_note(ko).replace('🌶', '').strip()); kinds.append(FOOD if food else DICT)
            if len(w) >= 2 or food:
                strong += 1
            i += len(w); continue
        i += 1   # 사전에 없는 한자: 건너뜀
    if not parts or strong == 0:
        return None
    text = ' · '.join(parts)
    kind = FOOD if FOOD in kinds else DICT
    return text, kind
