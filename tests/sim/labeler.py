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
        self.map = {}          # zh -> (라벨용 짧은 뜻, 전체 뜻, food id)
        # 요청사항 → 단어장(짧은 표기가 있어 우선) → 메뉴 순으로 덮어쓴다
        for r in d('requests.json')['items']:
            self.map[r['zh']] = (r['ko'], r['ko'], None)
        for g in d('glossary.json')['items']:
            self.map[g['zh']] = (g.get('short') or g['ko'], g['ko'], None)
        for f in d('foods.json'):
            self.map[f['zh']] = (f['ko'], f['ko'], f['id'])
            if f.get('q') and f['q'] != f['zh']:
                self.map[f['q']] = (f['ko'], f['ko'], f['id'])
        self.max_len = max(len(k) for k in self.map)
        self.hanja = d('hanja.json')

    def exact(self, zh):
        return self.map.get(zh)


def strip_note(ko):
    s = re.sub(r'\s*[(（][^)）]*[)）]', '', ko).strip()
    return s or ko


# 숫자 규칙. 위에서부터 먼저 맞는 것. None 이면 그 자리엔 라벨 없이 지나감. 'keep' 이면 원문 그대로 둔다.
RULES = [
    (re.compile(r'(\d+(?:\.\d+)?)折'), lambda m: f"-{round((10 - float(m.group(1))) * 10)}%" if 0.1 <= float(m.group(1)) <= 9.9 else None),
    (re.compile(r'已减(\d+(?:\.\d+)?)元'), lambda m: f"¥{m.group(1)} 할인됨"),
    (re.compile(r'已优惠[¥￥]?(\d+(?:\.\d+)?)'), lambda m: f"¥{m.group(1)} 할인됨"),
    (re.compile(r'还差[¥￥]?(\d+(?:\.\d+)?)起送'), lambda m: f"¥{m.group(1)} 더 담으면 주문 가능"),
    (re.compile(r'[¥￥]?(\d+(?:\.\d+)?)起送'), lambda m: f"최소 ¥{m.group(1)}"),
    (re.compile(r'起送[¥￥](\d+(?:\.\d+)?)'), lambda m: f"최소 ¥{m.group(1)}"),
    (re.compile(r'配送费[¥￥](\d+(?:\.\d+)?)'), lambda m: f"배달비 ¥{m.group(1)}"),
    (re.compile(r'人均[¥￥]?(\d+(?:\.\d+)?)'), lambda m: f"1인 ¥{m.group(1)}"),
    (re.compile(r'半年售\s*(\d+)\+?'), lambda m: f"반년 {m.group(1)}+ 판매"),
    (re.compile(r'月售\s*(\d+)(\+?)'), lambda m: f"월 {m.group(1)}{m.group(2)} 판매"),
    (re.compile(r'年售\s*(\d+)\+?'), lambda m: f"연 {m.group(1)}+ 판매"),
    (re.compile(r'季售\s*(\d+)\+?'), lambda m: f"시즌 {m.group(1)}+ 판매"),
    (re.compile(r'已售\s*(\d+)\+?'), lambda m: f"{m.group(1)}+ 판매"),
    (re.compile(r'(\d+)条评价'), lambda m: f"리뷰 {m.group(1)}개"),
    (re.compile(r'(\d+)%\s*好评率?'), lambda m: f"호평 {m.group(1)}%"),
    (re.compile(r'好评率\s*(\d+)%'), lambda m: f"호평 {m.group(1)}%"),
    (re.compile(r'(\d+)选(\d+)'), lambda m: f"{m.group(1)}택{m.group(2)}"),
    (re.compile(r'(\d+)\s*[-~]\s*(\d+)\s*人餐'), lambda m: f"{m.group(1)}~{m.group(2)}인 세트"),
    (re.compile(r'(\d+)\s*人餐'), lambda m: f"{m.group(1)}인 세트"),
    (re.compile(r'约\s*(\d+)\s*克'), lambda m: f"약 {m.group(1)}g"),
    (re.compile(r'(\d+)\s*克'), lambda m: f"{m.group(1)}g"),
    (re.compile(r'满(\d+)减(\d+)'), lambda m: f"{m.group(1)}↑ -{m.group(2)}"),
    (re.compile(r'立减(\d+)元?'), lambda m: f"즉시 -{m.group(1)}"),
    (re.compile(r'(\d+)分钟送达'), lambda m: f"{m.group(1)}분 내 배달"),
    (re.compile(r'约\s*(\d+)分钟'), lambda m: f"약 {m.group(1)}분"),
    (re.compile(r'(\d+)分钟'), lambda m: f"{m.group(1)}분"),
    (re.compile(r'(\d+(?:\.\d+)?)分(?!钟)'), lambda m: f"{m.group(1)}점"),
    (re.compile(r'预计\s*(\d+:\d+)\s*送达'), lambda m: f"{m.group(1)} 도착 예정"),
    (re.compile(r'距您\s*(\d+(?:\.\d+)?)\s*km'), lambda m: f"{m.group(1)}km 거리"),
    (re.compile(r'(\d+(?:\.\d+)?)\s*km'), lambda m: f"{m.group(1)}km"),
    (re.compile(r'(\d+)号楼'), lambda m: f"{m.group(1)}동"),
    (re.compile(r'(\d+)个'), lambda m: f"{m.group(1)}개"),
    (re.compile(r'(\d+)\s*次'), lambda m: f"{m.group(1)}회"),
    (re.compile(r'[xX×]\s*(\d+)'), lambda m: f"x{m.group(1)}"),
    (re.compile(r'[+＋-]\s*[¥￥]\s*\d+(?:\.\d+)?'), 'keep'),     # +¥4 같은 추가 요금
    (re.compile(r'[¥￥]\s*\d+(?:\.\d+)?'), 'keep'),             # 가격은 그대로
    (re.compile(r'\d+:\d+'), 'keep'),
    (re.compile(r'\d+(?:\.\d+)?(?:ml|ML|L|g|kg|G)\b'), 'keep'),
    (re.compile(r'\d+(?:\.\d+)?\+?'), 'keep'),                  # 그 밖의 숫자도 그대로
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
    2자 이상 사전 조각이나 뜻 있는 숫자 규칙이 하나는 있어야 라벨을 붙인다. 가격·숫자는 그대로 둔다.
    한자음은 덮어쓰기 라벨에서는 쓰지 않는다 (잘못 읽힌 글자·사진 속 글씨가 전부 소음이 됨)."""
    s = raw.strip()
    if not any(is_han(c) for c in s):
        return None
    parts, kinds = [], []
    strong = 0
    i = 0
    while i < len(s):
        matched = False
        for re_, f in RULES:
            m = re_.match(s, i)
            if not m:
                continue
            if f == 'keep':
                parts.append(m.group(0).replace('￥', '¥').replace(' ', '')); kinds.append(DICT)
            else:
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
            w, (short, ko, food) = hit
            parts.append(strip_note(short).replace('🌶', '').strip()); kinds.append(FOOD if food else DICT)
            if len(w) >= 2 or food or len(s) == 1:   # 한 글자 칩(热·大·小)은 줄 전체가 그 글자일 때만 인정
                strong += 1
            i += len(w); continue
        i += 1   # 사전에 없는 한자: 건너뜀
    if not parts or strong == 0:
        return None
    text = ' · '.join(parts)
    kind = FOOD if FOOD in kinds else DICT
    return text, kind
