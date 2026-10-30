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
        self.zi = d('zi.json')['chars']      # 글자 뜻 (마지막 단계)
        self.t2s = d('t2s.json')               # 번체 → 간체 (대만·홍콩식 메뉴: 餃子·月餅)
        self.sound = d('sound.json')['chars']   # 한자음 없는 글자의 중국어 발음 한글 표기 (마지막 수단)

    def exact(self, zh):
        return self.map.get(zh)


def strip_note(ko):
    """라벨용 뜻: 괄호 설명은 뗀다. 괄호뿐인 뜻("(메이퇀)")은 덮지 않는다 → ''"""
    return re.sub(r'\s*[(（][^)）]*[)）]', '', ko).strip()


def _zhe(v):
    """N折 → -X%. 10 이상이면 OCR이 앞의 ↓ 화살표를 숫자로 읽은 것(44.1折): 앞자리를 버린다"""
    x = float(v)
    if x > 9.9 and '.' in v:
        x = float(v[-3:]) if len(v.split('.')[0]) >= 2 else x
    return f"-{round((10 - x) * 10)}%" if 0.1 <= x <= 9.9 else None


_CN = {'零': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9}


def _cnum(t):
    """한자 숫자 → 아라비아 숫자 (三十六 → 36, 一百二 → 120)"""
    total, cur = 0, 0
    for c in t:
        if c in _CN:
            cur = _CN[c]
        else:
            u = {'十': 10, '百': 100, '千': 1000}[c]
            total += (cur or 1) * u; cur = 0
    return str(total + cur)


# 숫자 규칙. 위에서부터 먼저 맞는 것. None 이면 그 자리엔 라벨 없이 지나감. 'keep' 이면 원문 그대로 둔다.
CNUM = re.compile(r'(?=[一二三四五六七八九两十百千]{2})[一二三四五六七八九两]?[十百千](?:[一二三四五六七八九两]?[十百])*[一二三四五六七八九]?')
RULES = [
    (CNUM, lambda m: _cnum(m.group(0))),
    (re.compile(r'(\d+(?:\.\d+)?)折'), lambda m: _zhe(m.group(1))),
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
    (re.compile(r'[Tt]骨'), lambda m: "티본"),
    (re.compile(r'(\d+)\s*-\s*(\d+)(?=专区|神券|神劵|券)'), lambda m: f"{m.group(1)}↑ -{m.group(2)}"),
    (re.compile(r'立省\s*[¥￥]?\s*(\d+(?:\.\d+)?)'), lambda m: f"¥{m.group(1)} 즉시 할인"),
    (re.compile(r'(\d+:\d+)\s*后配送'), lambda m: f"{m.group(1)} 이후 배달"),
    (re.compile(r'(\d+)人觉得'), lambda m: f"{m.group(1)}명이"),
    (re.compile(r'低至\s*[¥￥]?(\d+(?:\.\d+)?)'), lambda m: f"최저 ¥{m.group(1)}"),
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
    (re.compile(r'(\d+(?:\.\d+)?)\s*(?:公里|千米)'), lambda m: f"{m.group(1)}km"),
    (re.compile(r'(\d+(?:\.\d+)?)\s*米'), lambda m: f"{m.group(1)}m"),
    (re.compile(r'(\d+)\s*两'), lambda m: f"{m.group(1)}량({int(m.group(1)) * 50}g)"),
    (re.compile(r'(\d+(?:\.\d+)?)\s*斤'), lambda m: f"{m.group(1)}근"),
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
    (re.compile(r'[(（][一-鿿]{2,8}店[)）]'), lambda m: '지점'),   # (鲜鱼口店) 괄호 속 지점 이름 → "지점"      # 지점 이름(鲜鱼口店): 뜻 없이 지나가되 '읽은 것'으로 친다
    (re.compile(r'\d+:\d+\s*[-~－]\s*\d+:\d+'), 'keep'),       # 07:00-21:00
    (re.compile(r'[+＋-]\s*[¥￥]\s*\d+(?:\.\d+)?'), 'keep'),     # +¥4 같은 추가 요금
    (re.compile(r'[¥￥]\s*\d+(?:\.\d+)?'), 'keep'),             # 가격은 그대로
    (re.compile(r'\d+:\d+'), 'keep'),
    (re.compile(r'\d+(?:\.\d+)?(?:ml|ML|L|g|kg|G)\b'), 'keep'),
    (re.compile(r'\d+(?:\.\d+)?\+?'), 'keep'),                  # 그 밖의 숫자도 그대로
]


# 음역(외래어 소리 옮기기)에 자주 쓰는 글자. 이런 글자가 사전 말 없이 3자 이상 이어지면 뜻 대신 중국어 발음 한글로 읽는다
# (洪都拉斯 → 훙더우라쓰, 蒙布朗 → 멍부랑. 뜻으로 풀면 "큰 모두 당긴 사").
TRANSLIT = set('阿埃艾奥澳巴芭班邦保堡贝本比彼毕宾波伯博布查达代戴丹德迪顿多厄恩尔法范菲费芬丰佛夫弗盖甘冈戈哥格根古圭哈汉豪赫亨洪胡霍基吉加嘉贾杰卡凯坎康柯科克肯库夸奎昆拉莱朗劳勒雷蕾黎里利莉丽连列琳隆卢鲁路伦罗洛玛迈麦曼蒙密摩莫墨姆穆纳娜奈内尼妮涅宁纽努诺欧帕潘佩彭皮普奇齐恰乔切琴丘萨塞桑瑟森莎尚舍施什斯丝苏索塔坦韦亚俄鲁')


# 단어 안에서도 한 글자로 읽는 글자: 조리 동사·모양·기본 음식 (메뉴 이름은 'A炒B', 'A丁' 식의 조합)
BOUND = set('店派塔挞皮豆粽冰露盅果糖酥芹笋菇配小大酱盒夹团圆角馍菜藕花膏饮糊杯扒干醋茶汁奶冻鸡鸭鱼虾肉蛋粉鲜香嫩脆酥辣炒蒸炖煎烧拌煲焖炸烤卤爆煮炝熘涮焗烩酿熏丁丝片块条球卷段末泥饭面粥汤羹饼包糕')


def best(dic, s, i, any_single=False):
    """가장 긴 말. 단 그 말 바로 뒤가 읽히지 않으면, 더 짧게 잘라 다음 말이 더 멀리 이어지는 쪽을 고른다
    (家常菜谱: 家常菜+谱 → 家常+菜谱, 大肉馅: 大肉+馅 → 大+肉馅)"""
    hit = longest(dic, s, i, 99, any_single)
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


def longest(dic, s, i, maxn=99, any_single=False, minn=1):
    n = min(dic.max_len, len(s) - i, maxn)
    while n >= minn:
        w = s[i:i + n]
        if all(is_han(c) for c in w):
            h = dic.exact(w)
            if h:
                if n > 1:
                    return w, h
                prev_han = i > 0 and is_han(s[i - 1])
                next_han = i + 1 < len(s) and is_han(s[i + 1])
                if (not prev_han and not next_han) or w in BOUND or any_single:
                    return w, h
        n -= 1
    return None


PRICE_START = re.compile(r'^[+＋-]?\s*[¥￥]\s*\d+(?:\.\d+)?(?![\d.]|\s*起送)')   # 가격으로 시작하는 줄 (￥20起送 은 아님)


def _label(dic, raw, use_zi):
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
    zrun = []            # 글자 뜻으로 읽는 중인 연속 글자
    used_zi = False
    zi_left = 3          # 한자음으로 읽을 수 있는 드문 글자 수
    lit = 0              # 글자 뜻·발음으로 읽은 글자 수 (직역)
    multi = False        # 두 글자 이상 말이나 숫자 규칙이 있었나
    loose = 0            # 일반 한 글자·글자 뜻·발음으로 읽은 한자 수 (잡음 판정)

    def flush():
        nonlocal zrun
        if not zrun:
            return
        # 뜻 있는 글자는 뜻, 소리만 빌린 글자('~')·뜻 표에 없는 드문 글자는 한자음으로 이어 읽는다
        out, snd = [], ''
        for c in zrun:
            m = dic.zi.get(c, '~')
            if m == '~':                       # 소리만 빌린 글자: 중국어 발음 (慕斯 → 무쓰), 뜻 표에 없는 드문 글자: 한국 한자음
                snd += (dic.sound.get(c) or dic.hanja.get(c, '')) if c in dic.zi else (dic.hanja.get(c) or dic.sound.get(c, ''))
                continue
            if snd:
                out.append(snd); snd = ''
            if m != '-':
                out.append(m)
        if snd:
            out.append(snd)
        if out:
            parts.append(' '.join(out)); kinds.append(HANJA)
        zrun = []
    # 줄 전체에서 가장 자연스러운 분절을 고른다 (동적 계획법). 0.26까지는 앞에서부터 가장 긴 말을 먼저 잡아
    # 炒米粉 → 炒米+粉("볶은 쌀 · 쌀국수")처럼 틀렸다. 비용: 규칙 0.5, 여러 글자 말 1, 한 글자 말 1(조리 동사 등·홀로) / 1.6(그 밖),
    # 음역어 2, 글자 뜻 2.5, 드문 글자 발음 3, 못 읽은 한자 5, 한자 아닌 글자 0.
    for e in _segment(dic, s, use_zi):
        kind_, j, data = e
        if kind_ == 'rule':
            re_, f, m = data
            if any(is_han(c) for c in m.group(0)) or f != 'keep':
                multi = True
            flush()
            if f == 'keep':
                parts.append(m.group(0).replace('￥', '¥').replace(' ', '')); kinds.append(DICT)
            else:
                t = f(m)
                covered += sum(1 for c in m.group(0) if is_han(c))
                if any(is_han(c) for c in m.group(0)): cov_end = m.end()
                if t:
                    parts.append(t); kinds.append(DICT); strong += 1
        elif kind_ == 'skip':
            if is_han(s[data]):
                flush()
        elif kind_ == 'translit':
            flush()
            i0 = data
            parts.append(''.join(dic.sound.get(c) or dic.hanja.get(c, '') for c in s[i0:j])); kinds.append(HANJA)
            covered += j - i0; cov_end = j; lit += j - i0; loose += j - i0
        elif kind_ == 'word':
            i0, w, (short, ko, food) = data
            if len(w) >= 2:
                multi = True
            elif w not in BOUND:
                loose += 1
            flush()
            ko = strip_note(short).replace('🌶', '').strip()
            covered += len(w); cov_end = i0 + len(w)
            if ko:
                parts.append(ko); kinds.append(FOOD if food else DICT)
                if len(w) >= 2 or food or len(s) == 1:   # 한 글자 칩(热·大·小)은 줄 전체가 그 글자일 때만 인정
                    strong += 1
        elif kind_ in ('zi', 'rare'):
            i0 = data
            zrun.append(s[i0]); covered += 1; cov_end = i0 + 1; used_zi = True; lit += 1; loose += 1
    flush()
    if use_zi:
        # 글자 뜻 단계: 거의 모든 한자를 읽었을 때만 (모르는 글자 하나쯤은 허용)
        if not parts or covered < max(total - 1, 0.9 * total):
            return None
        # 잡음 막기: 두 글자 이상 말·숫자 규칙이 하나도 없고, 일반 한 글자(조리 동사·모양·기본 음식 글자 BOUND 제외)·글자 뜻·발음으로
        # 읽은 한자가 70%를 넘으면 덮지 않는다 (한자 3자 이하 짧은 줄 제외). 폰에서 사진 속 글씨·잘린 줄이 "막 · 찾은 · 지난…"이 됐다.
        # 폰에서 사진 속 글씨·화면 위에 잘린 줄이 "막 · 찾은 · 지난 · 동료…"가 됐다.
        if not multi and loose > 0.7 * total and total > 3:
            return None
        kind = HANJA if kinds.count(HANJA) * 2 >= len(kinds) else (FOOD if FOOD in kinds else DICT)
        return _join(parts), kind, covered, total, lit
    if not parts or strong == 0:
        return None
    need = 0.75 if total > 12 else (0.5 if total <= 8 else 0.6)   # 짧은 메뉴 이름은 모르는 수식어 하나쯤 있어도 덮는다
    if covered < need * total:
        return None
    last_han = max(k for k, c in enumerate(s) if is_han(c))
    if total <= 8 and cov_end <= last_han:   # 짧은 이름은 끝(음식 본체)을 읽어야 덮는다: 湖南糖粑 → "후난" 같은 오해 방지
        return None
    text = _join(parts)
    kind = FOOD if FOOD in kinds else DICT
    return text, kind, covered, total, 0


def _rule_at(dic, s, i):
    for re_, f in RULES:
        m = re_.match(s, i)
        if not m:
            continue
        if re_ is CNUM:                      # 한자 숫자: 그보다 길거나 같은 사전 말(二十四节气·三十六味)이 있으면 사전 우선
            w2 = longest(dic, s, i, 99, False, 2)
            if w2 and len(w2[0]) >= m.end() - i:
                continue
        return re_, f, m
    return None


def _segment(dic, s, use_zi):
    """가장 비용이 낮은 분절. [(종류, 끝 위치, 자료)]"""
    n = len(s)
    INF = float('inf')
    cost = [INF] * (n + 1); back = [None] * (n + 1)
    cost[0] = 0.0
    rare_ok = use_zi
    for i in range(n):
        if cost[i] == INF:
            continue
        c0 = cost[i]
        def relax(j, cst, e):
            if c0 + cst < cost[j] - 1e-9:
                cost[j] = c0 + cst; back[j] = (i, e)
        r = _rule_at(dic, s, i)
        if r:
            relax(r[2].end(), 0.5, ('rule', r[2].end(), r))
            continue                          # 규칙이 맞는 자리는 규칙만 (0.26과 같음)
        ch = s[i]
        if not is_han(ch):
            relax(i + 1, 0.0, ('skip', i + 1, i)); continue
        if use_zi:
            j = i
            while j < n and s[j] in TRANSLIT and not longest(dic, s, j, 99, False, 2):
                j += 1
            if j - i >= 3:
                relax(j, 2.0, ('translit', j, i))
        nmax = min(dic.max_len, n - i)
        for L in range(nmax, 0, -1):
            w = s[i:i + L]
            if not all(is_han(x) for x in w):
                continue
            h = dic.exact(w)
            if not h:
                continue
            # 같은 비용이면 앞쪽 말을 길게 잡는 쪽 (0.26의 '가장 긴 말 먼저'와 같은 결과): 짧은 말은 앞에 올수록 아주 조금 비싸게
            tie = 0.001 * (n - i) / L
            if L >= 2:
                relax(i + L, 1.0 + tie, ('word', i + L, (i, w, h)))
            else:
                prev_han = i > 0 and is_han(s[i - 1]); next_han = i + 1 < n and is_han(s[i + 1])
                if (not prev_han and not next_han) or w in BOUND:
                    relax(i + 1, 1.0 + tie, ('word', i + 1, (i, w, h)))
                elif use_zi:
                    relax(i + 1, 1.6 + tie, ('word', i + 1, (i, w, h)))
        if use_zi and ch in dic.zi:
            relax(i + 1, 2.5, ('zi', i + 1, i))
        elif use_zi and (ch in dic.hanja or ch in dic.sound):
            relax(i + 1, 3.0, ('rare', i + 1, i))
        relax(i + 1, 5.0, ('skip', i + 1, i))
    out = []; j = n
    while j > 0:
        i, e = back[j]; out.append(e); j = i
    return out[::-1]


def _join(parts):
    out = []
    for p in parts:
        if p and (not out or out[-1] != p):      # 같은 말 되풀이 하나로 ("쿠키 · 쿠키")
            out.append(p)
    return ' · '.join(out)


# 폰 OCR(ML Kit)이 자주 틀리게 읽는 글자 묶음 → 바로잡아 읽는다 (2026-10-30 폰 화면: 月售를 月昔으로)
OCR_FIX = [('月昔', '月售'), ('已昔', '已售'), ('年昔', '年售'), ('选本格', '选规格'), ('选规恪', '选规格'), ('选现格', '选规格')]


def fix_ocr(raw):
    for a, b in OCR_FIX:
        raw = raw.replace(a, b)
    return raw


def label(dic, raw):
    raw = fix_ocr(''.join(dic.t2s.get(c, c) for c in raw))
    return _label2(dic, raw)


def _label2(dic, raw):
    """단어·규칙으로 먼저, 안 되면 한자 14자 이하 줄은 글자 뜻으로 빈 곳을 채운다 (회색 띠 = 직역).
    더 긴 문장은 기계 번역 몫 (리뷰·안내문)."""
    s = raw.strip()
    if not any(is_han(c) for c in s) or PRICE_START.match(s):
        return None
    w = _label(dic, raw, False)
    total = sum(1 for c in s if is_han(c))
    if not (w and w[2] >= total):
        z = _label(dic, raw, True)        # 단어로 다 못 읽었으면 빈 글자를 뜻으로 채운 쪽을 우선 (清蒸猪舌汤: "찜 · 탕" → 돼지 혀까지)
        if z:
            return z[0], z[1]
    return (w[0], w[1]) if w else None


def strict(dic, raw):
    """엄격한 번역: 사전 항목(한 글자 음식어 포함)과 규칙만으로 한자를 전부 읽었나 — 글자 뜻·발음 직역이 하나도 없이. (라벨, 참/거짓)"""
    raw = fix_ocr(''.join(dic.t2s.get(c, c) for c in raw))
    s = raw.strip()
    if not any(is_han(c) for c in s) or PRICE_START.match(s):
        return None, True
    total = sum(1 for c in s if is_han(c))
    w = _label(dic, raw, False)
    if w and w[2] >= total:
        return w[0], True
    z = _label(dic, raw, True)
    return (z[0] if z else (w[0] if w else None)), bool(z and z[4] == 0 and z[2] >= total)
