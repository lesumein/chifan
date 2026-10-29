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
    """라벨용 뜻: 괄호 설명은 뗀다. 괄호뿐인 뜻("(메이퇀)")은 덮지 않는다 → ''"""
    return re.sub(r'\s*[(（][^)）]*[)）]', '', ko).strip()


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
    (re.compile(r'好评\s*(\d+)%'), lambda m: f"호평 {m.group(1)}%"),
    (re.compile(r'(\d+)号(?!楼)'), lambda m: f"{m.group(1)}번지"),
    (re.compile(r'仅剩\s*(\d+:\d+:\d+)'), lambda m: f"{m.group(1)} 남음"),
    (re.compile(r'仅剩\s*(\d+)\s*份'), lambda m: f"{m.group(1)}개 남음"),
    (re.compile(r'\d+:\d+:\d+'), 'keep'),
    (re.compile(r'(\d+)瓶'), lambda m: f"{m.group(1)}병"),
    (re.compile(r'(\d+)份'), lambda m: f"{m.group(1)}인분"),
    (re.compile(r'\d{4}[/.-]\d{1,2}[/.-]\d{1,2}'), 'keep'),       # 날짜
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
    (re.compile(r'第(\d+)名'), lambda m: f"{m.group(1)}위"),
    (re.compile(r'(\d+)人份'), lambda m: f"{m.group(1)}인분"),
    (re.compile(r'(\d+)号楼'), lambda m: f"{m.group(1)}동"),
    (re.compile(r'(\d+)个'), lambda m: f"{m.group(1)}개"),
    (re.compile(r'(\d+)\s*次'), lambda m: f"{m.group(1)}회"),
    (re.compile(r'[xX×]\s*(\d+)'), lambda m: f"x{m.group(1)}"),
    (re.compile(r'再减(\d+(?:\.\d+)?)元'), lambda m: f"¥{m.group(1)} 추가 할인"),
    (re.compile(r'省(?:了)?(\d+(?:\.\d+)?)元'), lambda m: f"¥{m.group(1)} 절약"),
    (re.compile(r'(\d+(?:\.\d+)?)元'), lambda m: f"¥{m.group(1)}"),
    (re.compile(r'(\d+)天低价'), lambda m: f"{m.group(1)}일 최저가"),
    (re.compile(r'购买之后(\d+)天有效'), lambda m: f"구매 후 {m.group(1)}일 유효"),
    (re.compile(r'(\d+)天'), lambda m: f"{m.group(1)}일"),
    (re.compile(r'(\d+)人推荐'), lambda m: f"{m.group(1)}명 추천"),
    (re.compile(r'(\d+)家门店通用'), lambda m: f"{m.group(1)}개 매장 공통"),
    (re.compile(r'半年销量\s*(\d+)(\+?)'), lambda m: f"반년 {m.group(1)}{m.group(2)} 판매"),
    (re.compile(r'[(（]?[一-鿿]{2,8}店[)）]?$'), lambda m: ''),      # 지점 이름(鲜鱼口店): 뜻 없이 지나가되 '읽은 것'으로 친다
    (re.compile(r'\d+:\d+\s*[-~－]\s*\d+:\d+'), 'keep'),       # 07:00-21:00
    (re.compile(r'[+＋-]\s*[¥￥]\s*\d+(?:\.\d+)?'), 'keep'),     # +¥4 같은 추가 요금
    (re.compile(r'[¥￥]\s*\d+(?:\.\d+)?'), 'keep'),             # 가격은 그대로
    (re.compile(r'\d+:\d+'), 'keep'),
    (re.compile(r'\d+(?:\.\d+)?(?:ml|ML|L|g|kg|G)\b'), 'keep'),
    (re.compile(r'\d+(?:\.\d+)?\+?'), 'keep'),                  # 그 밖의 숫자도 그대로
]


# 단어 안에서도 한 글자로 읽는 글자: 조리 동사·모양·기본 음식 (메뉴 이름은 'A炒B', 'A丁' 식의 조합)
BOUND = set('小大酱盒夹团圆角馍菜藕花膏饮糊杯扒干醋茶汁奶冻鸡鸭鱼虾肉蛋粉鲜香嫩脆酥辣炒蒸炖煎烧拌煲焖炸烤卤爆煮炝熘涮焗烩酿熏丁丝片块条球卷段末泥饭面粥汤羹饼包糕')


def best(dic, s, i):
    """가장 긴 말. 단 그 말 바로 뒤가 읽히지 않으면, 더 짧게 잘라 다음 말이 더 멀리 이어지는 쪽을 고른다
    (家常菜谱: 家常菜+谱 → 家常+菜谱, 大肉馅: 大肉+馅 → 大+肉馅)"""
    hit = longest(dic, s, i)
    if not hit:
        return None
    end = i + len(hit[0])
    if end < len(s) and is_han(s[end]) and not longest(dic, s, end):
        for n in range(len(hit[0]) - 1, 0, -1):
            h2 = longest(dic, s, i, n)
            if not h2:
                continue
            e2 = i + len(h2[0]); nxt = longest(dic, s, e2)
            if nxt and e2 + len(nxt[0]) > end:
                return h2
    return hit


def longest(dic, s, i, maxn=99):
    n = min(dic.max_len, len(s) - i, maxn)
    while n >= 1:
        w = s[i:i + n]
        if all(is_han(c) for c in w):
            h = dic.exact(w)
            if h:
                if n > 1:
                    return w, h
                prev_han = i > 0 and is_han(s[i - 1])
                next_han = i + 1 < len(s) and is_han(s[i + 1])
                if (not prev_han and not next_han) or w in BOUND:
                    return w, h
        n -= 1
    return None


PRICE_START = re.compile(r'^[+＋-]?\s*[¥￥]\s*\d')


def label(dic, raw):
    """한 줄 → (라벨 글, kind) 또는 None.
    2자 이상 사전 조각이나 뜻 있는 숫자 규칙이 하나는 있어야 하고, 사전·규칙이 읽은 한자가 줄의 한자 60%(12자 넘는 문장은 75%)
    이상이어야 덮는다 — 모르는 문장(리뷰·안내문)에 조각 몇 개만 붙여 덮으면 원문도 뜻도 못 읽는다. 가격으로 시작하는 줄(¥1.79 6折 ¥3)은 덮지 않는다.
    한자음은 덮어쓰기 라벨에서는 쓰지 않는다 (잘못 읽힌 글자·사진 속 글씨가 전부 소음이 됨)."""
    s = raw.strip()
    if not any(is_han(c) for c in s):
        return None
    if PRICE_START.match(s):
        return None
    parts, kinds = [], []
    strong = 0
    covered = 0
    cov_end = 0          # 읽은 한자 구간의 끝 (메뉴 이름의 머리 명사는 끝에 온다)
    total = sum(1 for c in s if is_han(c))
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
                covered += sum(1 for c in m.group(0) if is_han(c))
                if any(is_han(c) for c in m.group(0)): cov_end = m.end()
                if t:
                    parts.append(t); kinds.append(DICT); strong += 1
            i = m.end(); matched = True
            break
        if matched:
            continue
        c = s[i]
        if not is_han(c):
            i += 1; continue
        hit = best(dic, s, i)
        if hit:
            w, (short, ko, food) = hit
            ko = strip_note(short).replace('🌶', '').strip()
            covered += len(w); cov_end = i + len(w)
            if ko:
                parts.append(ko); kinds.append(FOOD if food else DICT)
                if len(w) >= 2 or food or len(s) == 1:   # 한 글자 칩(热·大·小)은 줄 전체가 그 글자일 때만 인정
                    strong += 1
            i += len(w); continue
        i += 1   # 사전에 없는 한자: 건너뜀
    if not parts or strong == 0:
        return None
    need = 0.75 if total > 12 else (0.5 if total <= 8 else 0.6)   # 짧은 메뉴 이름은 모르는 수식어 하나쯤 있어도 덮는다
    if covered < need * total:
        return None
    last_han = max(k for k, c in enumerate(s) if is_han(c))
    if total <= 8 and cov_end <= last_han:   # 짧은 이름은 끝(음식 본체)을 읽어야 덮는다: 湖南糖粑 → "후난" 같은 오해 방지
        return None
    text = ' · '.join(parts)
    kind = FOOD if FOOD in kinds else DICT
    return text, kind
