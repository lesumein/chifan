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


# 숫자 규칙. 위에서부터 먼저 맞는 것. None 이면 그 자리엔 라벨 없이 지나감. 'keep' 이면 원문 그대로 둔다.
RULES = [
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
    (re.compile(r'[(（][一-鿿]{2,8}店[)）]'), lambda m: ''),      # (鲜鱼口店) 괄호 속 지점 이름: 뜻 없이 지나간다      # 지점 이름(鲜鱼口店): 뜻 없이 지나가되 '읽은 것'으로 친다
    (re.compile(r'\d+:\d+\s*[-~－]\s*\d+:\d+'), 'keep'),       # 07:00-21:00
    (re.compile(r'[+＋-]\s*[¥￥]\s*\d+(?:\.\d+)?'), 'keep'),     # +¥4 같은 추가 요금
    (re.compile(r'[¥￥]\s*\d+(?:\.\d+)?'), 'keep'),             # 가격은 그대로
    (re.compile(r'\d+:\d+'), 'keep'),
    (re.compile(r'\d+(?:\.\d+)?(?:ml|ML|L|g|kg|G)\b'), 'keep'),
    (re.compile(r'\d+(?:\.\d+)?\+?'), 'keep'),                  # 그 밖의 숫자도 그대로
]


# 음역(외래어 소리 옮기기)에 자주 쓰는 글자. 이런 글자가 사전 말 없이 3자 이상 이어지면 뜻 대신 중국어 발음 한글로 읽는다
# (洪都拉斯 → 훙더우라쓰, 蒙布朗 → 멍부랑. 뜻으로 풀면 "큰 모두 당긴 사").
TRANSLIT = set('阿埃艾安奥澳巴芭班邦保堡贝本比彼毕宾波伯博布查达代戴丹德迪蒂丁顿多厄恩尔法范菲费芬丰佛夫弗福盖甘冈戈哥格根古圭哈汉豪赫亨洪胡霍基吉加嘉贾杰卡凯坎康柯科克肯库夸奎昆拉莱兰朗劳勒雷蕾黎里利莉丽连列林琳隆卢鲁路伦罗洛玛迈麦曼梅门蒙米密摩莫墨姆穆纳娜奈内尼妮涅宁纽努诺欧帕潘佩彭皮普奇齐恰乔切琴丘萨塞桑瑟森莎尚舍施什斯丝松苏索塔泰坦汤唐特提图托瓦万旺威韦维魏温沃乌伍希锡夏谢辛休雅亚扬耶伊因英尤约泽扎詹兹佐俄都律勃艮第甸鲁蒂缇')


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


PRICE_START = re.compile(r'^[+＋-]?\s*[¥￥]\s*\d')


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
    i = 0
    while i < len(s):
        matched = False
        for re_, f in RULES:
            m = re_.match(s, i)
            if not m:
                continue
            flush()
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
        if use_zi:
            j = i
            while j < len(s) and s[j] in TRANSLIT and not longest(dic, s, j, 99, False, 2):
                j += 1
            if j - i >= 3:                    # 음역어: 발음으로
                flush()
                parts.append(''.join(dic.sound.get(c) or dic.hanja.get(c, '') for c in s[i:j])); kinds.append(HANJA)
                covered += j - i; cov_end = j; i = j
                continue
        hit = best(dic, s, i, use_zi)
        if hit:
            flush()
            w, (short, ko, food) = hit
            ko = strip_note(short).replace('🌶', '').strip()
            covered += len(w); cov_end = i + len(w)
            if ko:
                parts.append(ko); kinds.append(FOOD if food else DICT)
                if len(w) >= 2 or food or len(s) == 1:   # 한 글자 칩(热·大·小)은 줄 전체가 그 글자일 때만 인정
                    strong += 1
            i += len(w); continue
        if use_zi and c in dic.zi:
            zrun.append(c); covered += 1; cov_end = i + 1; used_zi = True
            i += 1; continue
        if use_zi and (c in dic.hanja or c in dic.sound) and zi_left > 0:     # 뜻 표에도 없는 드문 글자: 한자음 (고유명사 읽듯). 줄당 2자까지
            zrun.append(c); covered += 1; cov_end = i + 1; used_zi = True; zi_left -= 1
            i += 1; continue
        flush()
        i += 1   # 사전에 없는 한자: 건너뜀
    flush()
    if use_zi:
        # 글자 뜻 단계: 거의 모든 한자를 읽었을 때만 (모르는 글자 하나쯤은 허용)
        if not parts or covered < max(total - 1, 0.9 * total):
            return None
        kind = HANJA if kinds.count(HANJA) * 2 >= len(kinds) else (FOOD if FOOD in kinds else DICT)
        return _join(parts), kind, covered, total
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
    return text, kind, covered, total


def _join(parts):
    out = []
    for p in parts:
        if p and (not out or out[-1] != p):      # 같은 말 되풀이 하나로 ("쿠키 · 쿠키")
            out.append(p)
    return ' · '.join(out)


def label(dic, raw):
    raw = ''.join(dic.t2s.get(c, c) for c in raw)
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
