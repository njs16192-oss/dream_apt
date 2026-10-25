"""3단계(조회 API + 화면) 설계 전 데이터 분석: 면적 묶기, 일괄 거래 판별, 단지 분포.

로컬 DB의 apt_trade·apt_complex를 읽어 집계한다 (SELECT만 실행한다).
DB 드라이버 없이 psql의 --csv 출력을 읽는다. 표준 라이브러리만 사용한다.

사용법은 scripts/README.md 참고.
"""

import argparse
import csv
import io
import shlex
import subprocess
import sys
from bisect import bisect_left, bisect_right
from collections import Counter, defaultdict
from datetime import date, timedelta
from statistics import median

DEFAULT_PSQL = "docker exec -i dreamapt-postgres psql -U dreamapt -d dreamapt"

DEALING_FROM = date(2021, 11, 1)  # dealing_gbn(직거래/중개거래)이 채워지기 시작한 계약일
PARTY_FROM = date(2024, 1, 1)     # buyer_gbn·seller_gbn이 채워지기 시작한 계약일
STABILITY_CUTOFF = date(2025, 12, 31)
PRICE_WINDOW_DAYS = 90            # 시세 기준: 같은 단지·면적(R1) ±90일 중앙값 (같은 날 거래 제외)
NON_PERSON = {"법인", "공공기관"}
CLOSE = 100                       # 면적은 0.01㎡ 단위 정수로 다룬다. 100 = 1㎡
EXAMPLES = 10

TRADE_SQL = """
SELECT t.id, t.apt_seq, c.sgg_cd, t.deal_date, t.deal_amount, round(t.exclu_use_ar * 100)::int AS ar,
       t.floor, coalesce(t.buyer_gbn, '') AS buyer, coalesce(t.seller_gbn, '') AS seller,
       coalesce(t.dealing_gbn, '') AS dealing, t.cancelled, t.missing_since IS NOT NULL AS missing
FROM apt_trade t JOIN apt_complex c ON c.apt_seq = t.apt_seq
ORDER BY t.id
"""

COMPLEX_SQL = """
SELECT c.apt_seq, c.name, c.sgg_cd, coalesce(r.sgg_nm, c.sgg_cd) AS sgg_nm, coalesce(c.umd_nm, '') AS umd_nm,
       coalesce(c.build_year::text, '') AS build_year
FROM apt_complex c LEFT JOIN region r ON r.sgg_cd = c.sgg_cd
ORDER BY c.apt_seq
"""


# ---------------------------------------------------------------- DB

def query(psql, sql):
    result = subprocess.run(shlex.split(psql) + ["--csv", "-v", "ON_ERROR_STOP=1"], input=sql.encode("utf-8"),
                            capture_output=True, check=False)
    if result.returncode != 0:
        sys.exit(f"psql 실패: {result.stderr.decode('utf-8', 'replace')}")
    return list(csv.DictReader(io.StringIO(result.stdout.decode("utf-8"))))


class Trade:
    __slots__ = ("id", "apt", "sgg", "date", "amount", "ar", "floor", "buyer", "seller", "dealing",
                 "cancelled", "missing")

    def __init__(self, row):
        self.id = int(row["id"])
        self.apt = row["apt_seq"]
        self.sgg = row["sgg_cd"]
        self.date = date.fromisoformat(row["deal_date"])
        self.amount = int(row["deal_amount"])
        self.ar = int(row["ar"])
        self.floor = int(row["floor"])
        self.buyer = row["buyer"]
        self.seller = row["seller"]
        self.dealing = row["dealing"]
        self.cancelled = row["cancelled"] == "t"
        self.missing = row["missing"] == "t"

    @property
    def valid(self):
        return not self.cancelled and not self.missing

    @property
    def ym(self):
        return self.date.year * 100 + self.date.month


# ---------------------------------------------------------------- 공통

def r1(ar):
    """정수 반올림 (HALF_UP)."""
    return (ar + 50) // 100


def r2(ar):
    """정수 버림."""
    return ar // 100


def fmt_ar(ar):
    return f"{ar / 100:.2f}"


def pct(part, whole, digits=1):
    return f"{100 * part / whole:.{digits}f}%" if whole else "-"


def num(n):
    return f"{n:,}"


def table(headers, rows, align=None):
    align = align or "l" + "r" * (len(headers) - 1)
    sep = ["---:" if a == "r" else "---" for a in align]
    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join(sep) + " |"]
    lines += ["| " + " | ".join(str(c) for c in row) + " |" for row in rows]
    return lines + [""]


def bucket(n, edges):
    """edges = [(상한 포함, 라벨), ...], 마지막 라벨은 그 이상."""
    for upper, label in edges[:-1]:
        if n <= upper:
            return label
    return edges[-1][1]


def party(t):
    """매도 → 매수."""
    return f"{t.seller or '?'}→{t.buyer or '?'}"


def complex_label(c):
    return f"`{c['apt_seq']}` {c['name']} ({c['sgg_nm']} {c['umd_nm']}, {c['build_year'] or '?'})"


SIZE_EDGES = [(1, "1건"), (2, "2건"), (4, "3–4건"), (9, "5–9건"), (None, "10건+")]
SIZE_LABELS = [label for _, label in SIZE_EDGES]


def size_bucket(n):
    return bucket(n, SIZE_EDGES)


# ---------------------------------------------------------------- 1. 면적 묶기

def chain_groups(areas, gap):
    """정렬된 면적 목록에서 이웃한 값의 차이가 gap 미만이면 같은 그룹 (단일 연결)."""
    gids, g = [], 0
    for i, a in enumerate(areas):
        if i and a - areas[i - 1] >= gap:
            g += 1
        gids.append(g)
    return gids


RULES = [
    ("R0 원값", lambda areas: list(range(len(areas)))),
    ("R1 정수 반올림", lambda areas: [r1(a) for a in areas]),
    ("R2 정수 버림", lambda areas: [r2(a) for a in areas]),
    ("R3 근접 묶기 (<0.5㎡)", lambda areas: chain_groups(areas, 50)),
    ("R3 근접 묶기 (<1.0㎡)", lambda areas: chain_groups(areas, 100)),
]


def area_counts(valid):
    by_complex = defaultdict(Counter)
    for t in valid:
        by_complex[t.apt][t.ar] += 1
    return by_complex


def render_area(valid, complexes, out):
    by_complex = area_counts(valid)
    out += ["### 1. 면적 묶기", "",
            f"모집단: 유효 거래 {num(len(valid))}건, 유효 거래가 있는 단지 {num(len(by_complex))}개. "
            "면적은 DB 값(`NUMERIC(7,2)`) 그대로.", ""]

    # 단지별 서로 다른 면적 개수
    edges = [(1, "1"), (2, "2"), (3, "3"), (5, "4–5"), (9, "6–9"), (None, "10+")]
    c_cnt, t_cnt = Counter(), Counter()
    for counts in by_complex.values():
        b = bucket(len(counts), edges)
        c_cnt[b] += 1
        t_cnt[b] += sum(counts.values())
    out += ["#### 1-1. 단지별 서로 다른 전용면적 개수", ""]
    out += table(["면적 개수", "단지 수", "단지 비율", "거래 수", "거래 비율"],
                 [[label, num(c_cnt[label]), pct(c_cnt[label], len(by_complex)), num(t_cnt[label]),
                   pct(t_cnt[label], len(valid))] for _, label in edges])
    top = sorted(by_complex.items(), key=lambda kv: -len(kv[1]))[:5]
    out += ["면적 개수가 가장 많은 단지: " + ", ".join(f"{complexes[a]['name']}({complexes[a]['sgg_nm']}) {len(c)}개"
                                           for a, c in top), ""]

    # 1㎡ 미만 쌍
    diff_edges = [(9, "< 0.1㎡"), (49, "0.1 – 0.5㎡"), (None, "0.5 – 1.0㎡")]
    pair_cnt, pair_complex = Counter(), defaultdict(set)
    close_pairs, boundary = [], []
    for apt, counts in by_complex.items():
        areas = sorted(counts)
        for i, a in enumerate(areas):
            for b in areas[i + 1:]:
                d = b - a
                if d >= CLOSE:
                    break
                label = bucket(d, diff_edges)
                pair_cnt[label] += 1
                pair_complex[label].add(apt)
                pair_complex["all"].add(apt)
                item = (apt, a, b, counts[a], counts[b])
                close_pairs.append(item)
                if r1(a) != r1(b):
                    boundary.append(item)
    total_pairs = sum(pair_cnt.values())
    out += ["#### 1-2. 한 단지 안에서 차이가 1㎡ 미만인 면적 쌍", "",
            f"쌍 {num(total_pairs)}개, 그런 쌍이 있는 단지 {num(len(pair_complex['all']))}개 "
            f"(유효 거래가 있는 단지의 {pct(len(pair_complex['all']), len(by_complex))}).", ""]
    out += table(["차이", "쌍 수", "단지 수"],
                 [[label, num(pair_cnt[label]), num(len(pair_complex[label]))] for _, label in diff_edges])
    out += ["거래가 많은 쌍 (두 면적 중 적은 쪽 거래 수 기준):", ""]
    out += pair_table(sorted(close_pairs, key=lambda p: -min(p[3], p[4]))[:EXAMPLES], complexes)

    b_cnt = len(boundary)
    out += ["#### 1-3. 정수 반올림(R1) 경계에 걸려 갈라지는 쌍", "",
            f"1㎡ 미만 쌍 {num(total_pairs)}개 중 반올림 값이 다른 쌍 {num(b_cnt)}개 ({pct(b_cnt, total_pairs)}), "
            f"단지 {num(len({p[0] for p in boundary}))}개. 차이 0.1㎡ 미만인데 갈라지는 쌍 "
            f"{num(sum(1 for p in boundary if p[2] - p[1] < 10))}개.", ""]
    out += ["차이가 작은 순 (같으면 거래 많은 순):", ""]
    out += pair_table(sorted(boundary, key=lambda p: (p[2] - p[1], -min(p[3], p[4])))[:EXAMPLES], complexes)
    out += ["거래가 많은 순:", ""]
    out += pair_table(sorted(boundary, key=lambda p: -min(p[3], p[4]))[:EXAMPLES], complexes)

    render_rules(by_complex, len(valid), complexes, out)
    render_stability(valid, complexes, out)


def pair_table(pairs, complexes):
    return table(["단지", "면적 A", "면적 B", "차이", "A 거래", "B 거래", "R1 (A/B)"],
                 [[complex_label(complexes[apt]), fmt_ar(a), fmt_ar(b), fmt_ar(b - a), na, nb, f"{r1(a)}/{r1(b)}"]
                  for apt, a, b, na, nb in pairs], "lrrrrrr")


def render_rules(by_complex, n_valid, complexes, out):
    rows = []
    wide_examples = {}
    for name, rule in RULES:
        groups_total, max_groups, merged, small_groups, small_trades, wide = 0, 0, 0, 0, 0, 0
        split = Counter()
        max_span, max_span_at = 0, None
        for apt, counts in by_complex.items():
            areas = sorted(counts)
            gids = rule(areas)
            members = defaultdict(list)
            for a, g in zip(areas, gids):
                members[g].append(a)
            groups_total += len(members)
            max_groups = max(max_groups, len(members))
            for ms in members.values():
                n = sum(counts[a] for a in ms)
                if n < 5:
                    small_groups += 1
                    small_trades += n
                span = ms[-1] - ms[0]
                if span >= CLOSE:
                    wide += 1
                if span > max_span:
                    max_span, max_span_at = span, (apt, ms)
            for i, a in enumerate(areas):
                for j in range(i + 1, len(areas)):
                    d = areas[j] - a
                    same = gids[i] == gids[j]
                    if d < CLOSE and not same:
                        split[d < 10] += 1
                        split[d < 50, "half"] += 1
                    if d >= 50 and same:
                        merged += 1
        rows.append([name, num(groups_total), f"{groups_total / len(by_complex):.2f}", max_groups,
                     num(split[True] + split[False]), num(split[True, "half"]), num(split[True]),
                     num(merged), num(wide), fmt_ar(max_span), pct(small_groups, groups_total),
                     pct(small_trades, n_valid)])
        if max_span_at:
            wide_examples[name] = max_span_at
    out += ["#### 1-4. 후보 규칙 비교", "",
            "- **갈라짐**: 한 단지 안에서 차이 1㎡ 미만인 면적 쌍이 다른 그룹으로 나뉜 수. "
            "차이가 작을수록 같은 타입이 갈라졌을 가능성이 크므로 0.5㎡ 미만, 0.1㎡ 미만을 따로 센다 "
            "(0.5–1.0㎡ 쌍은 다른 타입일 수도 있다. 예: 84.15 / 84.92)",
            "- **합쳐짐**: 차이 0.5㎡ 이상인 면적 쌍이 같은 그룹에 들어간 수 (다른 평형 타입일 수 있음)",
            "- **폭 1㎡+ 그룹**: 그룹 안 최대 − 최소 면적이 1㎡ 이상인 그룹 수",
            "- **소표본 그룹**: 그룹의 유효 거래가 5건 미만", ""]
    out += table(["규칙", "그룹 수", "단지당 평균", "단지당 최대", "갈라짐 (<1㎡)", "갈라짐 (<0.5㎡)", "갈라짐 (<0.1㎡)",
                  "합쳐짐", "폭 1㎡+ 그룹", "최대 폭(㎡)",
                  "소표본 그룹 비율", "소표본 그룹 거래 비율"], rows)
    out += ["규칙별 폭이 가장 넓은 그룹:", ""]
    out += table(["규칙", "단지", "그룹 면적"],
                 [[name, complex_label(complexes[apt]), ", ".join(fmt_ar(a) for a in ms)]
                  for name, (apt, ms) in wide_examples.items() if not name.startswith("R0")], "lll")


def render_stability(valid, complexes, out):
    out += ["#### 1-5. R3 안정성: 2025년까지 데이터로 묶은 그룹 vs 전체 데이터로 묶은 그룹", "",
            f"2025-12-31까지 계약분(현재 유효 거래 기준)의 면적으로 R3 그룹을 만들고, 전체 데이터({valid_range(valid)})로 "
            "다시 만들어 비교한다. R3는 단일 연결이라 면적 값이 늘어도 기존 그룹이 쪼개지지는 않고, "
            "새 값이 기존 그룹에 붙거나 그룹 사이를 이어 합칠 수만 있다.", "",
            "- **합쳐짐**: 2025년 그룹 두 개 이상이 전체 데이터에서 한 그룹이 됨 (그룹 ID·라벨이 바뀜)",
            "- **범위 넓어짐**: 합쳐지지는 않았지만 새 면적이 기존 그룹 범위 밖에 붙어 그룹의 최소·최대가 바뀜",
            "- **새 그룹만**: 새 면적이 기존 그룹과 떨어져 새 그룹이 됨 (기존 그룹 그대로)",
            "- **변화 없음**: 새 면적이 없거나 기존 그룹 범위 안에만 들어옴",
            "- R1·R2는 면적 값 하나만으로 그룹이 정해지므로 데이터가 늘어도 기존 값의 그룹이 바뀌지 않는다 → **해당 없음**", ""]
    old_counts = area_counts([t for t in valid if t.date <= STABILITY_CUTOFF])
    full_counts = area_counts(valid)
    rows, examples = [], {}
    for gap in (50, 100):
        status = Counter()
        merged_examples = []
        for apt, old in old_counts.items():
            old_areas, full_areas = sorted(old), sorted(full_counts[apt])
            old_g = dict(zip(old_areas, chain_groups(old_areas, gap)))
            full_g = dict(zip(full_areas, chain_groups(full_areas, gap)))
            old_by_full = defaultdict(set)
            for a in old_areas:
                old_by_full[full_g[a]].add(old_g[a])
            merged = [g for g, olds in old_by_full.items() if len(olds) > 1]
            if merged:
                status["합쳐짐"] += 1
                for g in merged:
                    members = [a for a in full_areas if full_g[a] == g]
                    new = [a for a in members if a not in old]
                    olds = defaultdict(list)
                    for a in members:
                        if a in old:
                            olds[old_g[a]].append(a)
                    merged_examples.append((apt, [olds[k] for k in sorted(olds)], new,
                                            sum(full_counts[apt][a] for a in members)))
                continue
            new_areas = [a for a in full_areas if a not in old]
            ranges = defaultdict(list)
            for a in old_areas:
                ranges[old_g[a]].append(a)
            widened = False
            for a in new_areas:
                olds = old_by_full.get(full_g[a])
                if olds:
                    g = next(iter(olds))
                    if a < ranges[g][0] or a > ranges[g][-1]:
                        widened = True
            if widened:
                status["범위 넓어짐"] += 1
            elif any(full_g[a] not in old_by_full for a in new_areas):
                status["새 그룹만"] += 1
            else:
                status["변화 없음"] += 1
        n = len(old_counts)
        label = f"R3 (<{gap / 100:.1f}㎡)"
        rows.append([label, num(n)] + [f"{num(status[k])} ({pct(status[k], n)})"
                                       for k in ("합쳐짐", "범위 넓어짐", "새 그룹만", "변화 없음")])
        examples[label] = sorted(merged_examples, key=lambda e: -e[3])
    rows += [["R1 정수 반올림", "-", "해당 없음", "해당 없음", "-", "-"],
             ["R2 정수 버림", "-", "해당 없음", "해당 없음", "-", "-"]]
    out += table(["규칙", "2025년까지 거래가 있는 단지", "합쳐짐", "범위 넓어짐", "새 그룹만", "변화 없음"], rows)
    for label, items in examples.items():
        out += [f"{label} 합쳐진 사례 {num(len(items))}건 중 거래가 많은 순:", ""]
        out += table(["단지", "2025년까지 그룹들", "2026년에 새로 들어온 면적", "합쳐진 그룹 거래"],
                     [[complex_label(complexes[apt]), " / ".join("[" + ", ".join(fmt_ar(a) for a in g) + "]"
                                                                 for g in olds),
                       ", ".join(fmt_ar(a) for a in new) or "-", n]
                      for apt, olds, new, n in items[:EXAMPLES]], "lllr")


def valid_range(valid):
    return f"{min(t.date for t in valid)} – {max(t.date for t in valid)}"


# ---------------------------------------------------------------- 2. 일괄 거래 판별

def same_day_groups(trades):
    groups = defaultdict(list)
    for t in trades:
        groups[(t.apt, t.date)].append(t)
    return groups


def reference_ratios(valid):
    """거래 id → 금액 / (같은 단지·R1 면적 ±90일 유효 거래 중앙값, 같은 날 거래 제외)."""
    series = defaultdict(list)
    for t in valid:
        series[(t.apt, r1(t.ar))].append(t)
    ratios = {}
    for ts in series.values():
        ts.sort(key=lambda t: t.date)
        days = [t.date.toordinal() for t in ts]
        amounts = [t.amount for t in ts]
        cache = {}
        for t, d in zip(ts, days):
            if d not in cache:
                lo, hi = bisect_left(days, d - PRICE_WINDOW_DAYS), bisect_right(days, d + PRICE_WINDOW_DAYS)
                s_lo, s_hi = bisect_left(days, d), bisect_right(days, d)
                window = amounts[lo:s_lo] + amounts[s_hi:hi]
                cache[d] = median(window) if window else None
            if cache[d]:
                ratios[t.id] = t.amount / cache[d]
    return ratios


def flag_groups(trades, min_size):
    flagged = set()
    for ts in same_day_groups(trades).values():
        if len(ts) >= min_size:
            flagged.update(t.id for t in ts)
    return flagged


def criteria(valid):
    """기준 후보: (이름, 설명, 적용 시작 계약일, 걸린 거래 id 집합)."""
    first = min(t.date for t in valid)
    return [
        ("C1", "같은 단지·같은 날 유효 거래 ≥ 5건", first, flag_groups(valid, 5)),
        ("C2", "같은 단지·같은 날 유효 거래 ≥ 10건", first, flag_groups(valid, 10)),
        ("C3", "같은 단지·같은 날 직거래 ≥ 3건 (그 직거래들)", DEALING_FROM,
         flag_groups([t for t in valid if t.date >= DEALING_FROM and t.dealing == "직거래"], 3)),
        ("C4", "같은 단지·같은 날 법인·공공기관이 낀 거래 ≥ 3건 (그 거래들)", PARTY_FROM,
         flag_groups([t for t in valid if t.date >= PARTY_FROM and (t.buyer in NON_PERSON or t.seller in NON_PERSON)],
                     3)),
    ]


def render_bulk(trades, valid, complexes, out):
    groups = same_day_groups(valid)
    ratios = reference_ratios(valid)
    out += ["### 2. 일괄 거래 판별", "",
            "같은 날 그룹 = 같은 단지(`apt_seq`)·같은 계약일의 유효 거래. "
            f"시세 대비 = 거래 금액 / 같은 단지·면적(R1)의 ±{PRICE_WINDOW_DAYS}일 유효 거래 중앙값 (같은 날 거래 제외, "
            f"비교 대상이 없으면 제외). 유효 거래 중 시세 대비 값이 있는 거래 {pct(len(ratios), len(valid))}.", ""]

    # 2-1. 그룹 크기 분포
    g_cnt, t_cnt = Counter(), Counter()
    same_area, same_amount, multi = Counter(), Counter(), Counter()
    for ts in groups.values():
        b = size_bucket(len(ts))
        g_cnt[b] += 1
        t_cnt[b] += len(ts)
        if len(ts) > 1:
            multi[b] += 1
            same_area[b] += len({t.ar for t in ts}) == 1
            same_amount[b] += len({t.amount for t in ts}) == 1
    out += ["#### 2-1. 같은 단지·같은 계약일 거래 수 분포", ""]
    out += table(["같은 날 거래 수", "그룹 수", "거래 수", "거래 비율", "그룹 안 면적 모두 같음", "그룹 안 금액 모두 같음"],
                 [[b, num(g_cnt[b]), num(t_cnt[b]), pct(t_cnt[b], len(valid)),
                   pct(same_area[b], multi[b]) if multi[b] else "-",
                   pct(same_amount[b], multi[b]) if multi[b] else "-"] for b in SIZE_LABELS])

    # 2-2. 구간별 직거래·당사자·해제·시세 대비
    bucket_of = {t.id: size_bucket(len(ts)) for ts in groups.values() for t in ts}
    dealing, dealing_n = Counter(), Counter()
    parties, party_n = defaultdict(Counter), Counter()
    ratio_by = defaultdict(list)
    for t in valid:
        b = bucket_of[t.id]
        if t.date >= DEALING_FROM:
            dealing_n[b] += 1
            dealing[b] += t.dealing == "직거래"
        if t.date >= PARTY_FROM:
            party_n[b] += 1
            parties[b][party_label(t)] += 1
        if t.id in ratios:
            ratio_by[b].append(ratios[t.id])
    # 해제 비율은 해제 포함 모집단(missing 제외)으로 그룹을 다시 만든다
    with_cancel = same_day_groups([t for t in trades if not t.missing])
    cancel, cancel_n = Counter(), Counter()
    for ts in with_cancel.values():
        b = size_bucket(len(ts))
        cancel_n[b] += len(ts)
        cancel[b] += sum(t.cancelled for t in ts)
    party_keys = ["개인→개인", "법인→개인", "개인→법인", "법인→법인", "법인→공공기관", "개인→공공기관", "기타"]
    out += ["#### 2-2. 같은 날 거래 수 구간별 거래 성격", "",
            f"- 직거래 비율: 계약일 {DEALING_FROM} 이후 유효 거래만 (그 전에는 값 없음)",
            f"- 매도→매수 구분: 계약일 {PARTY_FROM} 이후 유효 거래만",
            "- 해제 비율: 해제 거래를 포함해 같은 날 그룹을 다시 만든 기준 (사라진 거래 제외)",
            "- 시세 대비: 구간 안 거래들의 값의 중앙값과, 0.8 미만인 거래 비율", ""]
    rows = []
    for b in SIZE_LABELS:
        rs = sorted(ratio_by[b])
        rows.append([b, num(dealing_n[b]), pct(dealing[b], dealing_n[b]), num(party_n[b])]
                    + [pct(parties[b][k], party_n[b]) for k in party_keys]
                    + [pct(cancel[b], cancel_n[b]), f"{median(rs):.3f}" if rs else "-",
                       pct(sum(r < 0.8 for r in rs), len(rs))])
    out += table(["같은 날", "거래(2021-11+)", "직거래", "거래(2024+)"] + party_keys
                 + ["해제 비율", "시세 대비 중앙값", "시세 대비 < 0.8"], rows)

    # 2-3. 기준 후보
    cs = criteria(valid)
    by_id = {t.id: t for t in valid}
    out += ["#### 2-3. 기준 후보별로 걸리는 거래", ""]
    rows = []
    for name, desc, start, ids in cs:
        in_period = sum(1 for t in valid if t.date >= start)
        rs = sorted(ratios[i] for i in ids if i in ratios)
        rows.append([name, desc, f"{start} –", num(len(ids)), pct(len(ids), in_period, 2),
                     num(len({(by_id[i].apt, by_id[i].date) for i in ids})), num(len({by_id[i].apt for i in ids})),
                     f"{median(rs):.3f}" if rs else "-"])
    out += table(["기준", "조건", "적용 계약일", "걸린 거래", "기간 내 유효 거래 대비", "그룹(단지·날짜)", "단지",
                  "시세 대비 중앙값"], rows, "llrrrrrr")

    render_false_positive(groups, ratios, cs, complexes, out)
    render_overlap(valid, cs, out)
    render_effect(valid, cs, complexes, out)
    render_bulk_complexes(valid, cs, complexes, out)
    return cs


def party_label(t):
    label = party(t)
    known = {"개인→개인", "법인→개인", "개인→법인", "법인→법인", "법인→공공기관", "개인→공공기관"}
    return label if label in known else "기타"


def group_summary(ts, ratios):
    rs = [ratios[t.id] for t in ts if t.id in ratios]
    amounts = [t.amount for t in ts]
    return {
        "n": len(ts),
        "areas": len({t.ar for t in ts}),
        "floors": len({t.floor for t in ts}),
        "amount": f"{min(amounts):,}–{max(amounts):,}" if min(amounts) != max(amounts) else f"{amounts[0]:,}",
        "ratio": median(rs) if rs else None,
        "dealing": "/".join(f"{k} {v}" for k, v in Counter(t.dealing or "?" for t in ts).most_common()),
        "party": "/".join(f"{k} {v}" for k, v in Counter(party(t) for t in ts).most_common(2)),
    }


def group_rows(items, complexes, ratios):
    rows = []
    for (apt, d), ts in items:
        s = group_summary(ts, ratios)
        rows.append([complex_label(complexes[apt]), d, s["n"], s["areas"], s["floors"], s["amount"],
                     f"{s['ratio']:.2f}" if s["ratio"] else "-", s["dealing"], s["party"]])
    return table(["단지", "계약일", "거래", "면적 종류", "층 종류", "금액(만원)", "시세 대비", "거래 유형", "매도→매수"],
                 rows, "llrrrrrll")


def looks_ordinary(ts, ratios):
    """중개거래만, (2024+) 개인→개인만, 시세 대비 중앙값 0.9–1.1, 면적·층 조합이 모두 다름."""
    if any(t.dealing != "중개거래" for t in ts):
        return False
    if ts[0].date >= PARTY_FROM and any(party(t) != "개인→개인" for t in ts):
        return False
    rs = [ratios[t.id] for t in ts if t.id in ratios]
    if not rs or not 0.9 <= median(rs) <= 1.1:
        return False
    return len({(t.ar, t.floor) for t in ts}) == len(ts)


def looks_bulk(ts, ratios):
    """2–4건인데 일괄 거래처럼 보임: 모두 법인·공공기관이 낌(2024+), 또는 모두 직거래이고 시세 대비 < 0.8."""
    if ts[0].date >= PARTY_FROM and all(t.buyer in NON_PERSON or t.seller in NON_PERSON for t in ts):
        return True
    rs = [ratios[t.id] for t in ts if t.id in ratios]
    return all(t.dealing == "직거래" for t in ts) and bool(rs) and median(rs) < 0.8


def render_false_positive(groups, ratios, cs, complexes, out):
    out += ["#### 2-4. 일반 거래로 보이는데 걸리는 사례 / 안 걸리는데 일괄로 보이는 사례", "",
            f"**일반 거래처럼 보이는 조건**: 중개거래만, ({PARTY_FROM} 이후) 매도·매수 모두 개인, 시세 대비 중앙값 0.9–1.1, "
            f"면적·층 조합이 모두 다름. 거래 유형 값이 있는 {DEALING_FROM} 이후 그룹만 판단할 수 있다.", ""]
    rows = []
    for name, min_size in (("C1", 5), ("C2", 10)):
        hit = [(k, ts) for k, ts in groups.items() if len(ts) >= min_size and k[1] >= DEALING_FROM]
        ordinary = [(k, ts) for k, ts in hit if looks_ordinary(ts, ratios)]
        rows.append([name, num(len(hit)), num(sum(len(ts) for _, ts in hit)), num(len(ordinary)),
                     pct(len(ordinary), len(hit)), num(sum(len(ts) for _, ts in ordinary))])
        if name == "C1":
            c1_ordinary = ordinary
    out += table(["기준", f"걸린 그룹 ({DEALING_FROM}+)", "걸린 거래", "일반처럼 보이는 그룹", "비율",
                  "일반처럼 보이는 그룹의 거래"], rows)
    out += ["C1에 걸렸지만 일반 거래처럼 보이는 그룹 (거래 많은 순):", ""]
    out += group_rows(sorted(c1_ordinary, key=lambda kv: (-len(kv[1]), kv[0][1]))[:EXAMPLES], complexes, ratios)

    flagged = set().union(*(ids for _, _, _, ids in cs))
    small = [(k, ts) for k, ts in groups.items() if 2 <= len(ts) <= 4 and k[1] >= DEALING_FROM
             and not any(t.id in flagged for t in ts) and looks_bulk(ts, ratios)]
    out += [f"**어느 기준(C1–C4)에도 안 걸리지만 일괄 거래처럼 보이는 2–4건 그룹**: 모두 법인·공공기관이 낌({PARTY_FROM} 이후) "
            f"또는 모두 직거래이고 시세 대비 중앙값 < 0.8. {num(len(small))}그룹, {num(sum(len(ts) for _, ts in small))}건.",
            ""]
    out += group_rows(sorted(small, key=lambda kv: (-len(kv[1]), kv[0][1]))[:EXAMPLES], complexes, ratios)


def render_overlap(valid, cs, out):
    ids_2024 = {t.id for t in valid if t.date >= PARTY_FROM}
    named = {name: ids & ids_2024 for name, _, _, ids in cs}
    named["C3∪C4"] = named["C3"] | named["C4"]
    out += ["#### 2-5. 필드 없는 기준(C1·C2)과 필드 기준(C3·C4)의 겹침", "",
            f"계약일 {PARTY_FROM} 이후 유효 거래 {num(len(ids_2024))}건 (거래 유형·매도/매수 구분이 모두 있는 기간). "
            "과거 데이터에는 C1·C2만 쓸 수 있으므로, 필드가 있는 기간에 두 종류 기준이 얼마나 같은 거래를 잡는지 본다.", ""]
    rows = []
    for a in ("C1", "C2"):
        for b in ("C3", "C4", "C3∪C4"):
            A, B = named[a], named[b]
            both = len(A & B)
            rows.append([a, b, num(len(A)), num(len(B)), num(both), num(len(A - B)), num(len(B - A)),
                         pct(both, len(A)), pct(both, len(B))])
    out += table(["A (필드 없음)", "B (필드)", "A 거래", "B 거래", "양쪽 다", "A만", "B만", "A 중 B에도 걸림",
                  "B 중 A에도 걸림"], rows, "llrrrrrrr")


def change_bucket(rate):
    if rate == 0:
        return "0"
    if rate < 0.01:
        return "< 1%"
    if rate < 0.03:
        return "1 – 3%"
    if rate < 0.05:
        return "3 – 5%"
    if rate < 0.10:
        return "5 – 10%"
    if rate < 0.20:
        return "10 – 20%"
    return "20%+"


CHANGE_LABELS = ["0", "< 1%", "1 – 3%", "3 – 5%", "5 – 10%", "10 – 20%", "20%+"]


def render_effect(valid, cs, complexes, out):
    sgg_names = {c["sgg_cd"]: c["sgg_nm"] for c in complexes.values()}
    out += ["#### 2-6. 걸러내기의 실제 효과 (제외 전 vs 제외 후)", "",
            "- **중앙값 셀** = 단지 × 면적(R1) × 계약월. 제외 전에 유효 거래가 있던 셀을 분모로 한다",
            "  - 사라짐: 셀의 거래가 모두 걸려서 제외 후 값이 없음",
            "  - 변화 폭: |제외 후 중앙값 / 제외 전 중앙값 − 1|",
            "- **거래량 셀** = 구 × 계약월. 변화 폭: 감소율 (제외 건수 / 제외 전 건수)",
            "- 각 기준의 적용 기간(C3 2021-11 이후, C4 2024 이후)에 든 셀만 센다", ""]
    med_rows, vol_rows, vol_examples = [], [], {}
    for name, _, start, ids in cs:
        start_ym = start.year * 100 + start.month
        cells = defaultdict(list)
        vols = Counter()
        for t in valid:
            if t.ym >= start_ym:
                cells[(t.apt, r1(t.ar), t.ym)].append(t)
                vols[(t.sgg, t.ym)] += 1
        dist, gone, touched = Counter(), 0, 0
        for ts in cells.values():
            rest = [t.amount for t in ts if t.id not in ids]
            if len(rest) < len(ts):
                touched += 1
            if not rest:
                gone += 1
                continue
            before = median(t.amount for t in ts)
            dist[change_bucket(abs(median(rest) / before - 1))] += 1
        n = len(cells)
        changed = n - gone - dist["0"]
        med_rows.append([name, num(n), f"{num(touched)} ({pct(touched, n, 2)})", f"{num(gone)} ({pct(gone, n, 2)})",
                         f"{num(changed)} ({pct(changed, n, 2)})"] + [num(dist[k]) for k in CHANGE_LABELS[1:]])
        removed = Counter()
        for t in valid:
            if t.id in ids:
                removed[(t.sgg, t.ym)] += 1
        vdist = Counter(change_bucket(removed[k] / v) for k, v in vols.items())
        vn = len(vols)
        vol_rows.append([name, num(vn), f"{num(vn - vdist['0'])} ({pct(vn - vdist['0'], vn)})"]
                        + [num(vdist[k]) for k in CHANGE_LABELS[1:]])
        vol_examples[name] = sorted(((removed[k] / v, k, v, removed[k]) for k, v in vols.items()), reverse=True)[:5]
    out += ["단지 × 면적(R1) × 월 중앙값:", ""]
    out += table(["기준", "셀", "걸린 거래가 있는 셀", "사라진 셀", "중앙값이 바뀐 셀"] + CHANGE_LABELS[1:], med_rows)
    out += ["구 × 월 거래량:", ""]
    out += table(["기준", "셀", "바뀐 셀"] + [f"감소 {k}" for k in CHANGE_LABELS[1:]], vol_rows)
    out += ["감소율이 가장 큰 구 × 월:", ""]
    out += table(["기준", "구·월 (제외 전 → 제외 후, 감소율)"],
                 [[name, "; ".join(f"{sgg_names.get(s, s)} {ym // 100}-{ym % 100:02d} ({v}→{v - r}, −{100 * rate:.0f}%)"
                                   for rate, (s, ym), v, r in items)] for name, items in vol_examples.items()], "ll")


def render_bulk_complexes(valid, cs, complexes, out):
    out += ["#### 2-7. 거래 대부분이 일괄 거래 그룹인 단지", "",
            "단지의 유효 거래(기준의 적용 기간 안) 중 기준에 걸린 거래 비율. "
            "이런 단지는 걸러내면 거래가 거의 남지 않고, 걸러내지 않으면 중앙값도 일괄 거래 값이 된다.", ""]
    rows, examples = [], {}
    for name, _, start, ids in cs:
        total, hit = Counter(), Counter()
        for t in valid:
            if t.date >= start:
                total[t.apt] += 1
                hit[t.apt] += t.id in ids
        ge80 = [a for a in total if hit[a] / total[a] >= 0.8]
        ge50 = [a for a in total if hit[a] / total[a] >= 0.5]
        rows.append([name, num(len(ge80)), num(sum(total[a] for a in ge80)), num(len(ge50)),
                     num(sum(total[a] for a in ge50))])
        examples[name] = sorted(((total[a], hit[a], a) for a in ge80), reverse=True)
    out += table(["기준", "80%+ 단지", "그 단지의 거래", "50%+ 단지", "그 단지의 거래"], rows)
    by_apt = defaultdict(list)
    for t in valid:
        by_apt[t.apt].append(t)
    for name in ("C1", "C4"):
        out += [f"{name} 기준 80%+ 단지 (거래 많은 순):", ""]
        rows = []
        for total, hit, apt in examples[name][:EXAMPLES]:
            ts = by_apt[apt]
            days = Counter(t.date for t in ts)
            top_day, top_n = days.most_common(1)[0]
            rows.append([complex_label(complexes[apt]), total, hit, f"{min(t.date for t in ts)} – {max(t.date for t in ts)}",
                         f"{top_day} {top_n}건", "/".join(f"{k} {v}" for k, v in Counter(party(t) for t in ts).most_common(2))])
        out += table(["단지", "거래", "걸린 거래", "계약일 범위", "가장 많은 날", "매도→매수 (상위)"], rows, "lrrlll")


# ---------------------------------------------------------------- 3. 참고

def render_reference(trades, valid, complexes, out):
    last = max(t.date for t in valid)
    recent_from = last - timedelta(days=365)
    per = Counter(t.apt for t in valid)
    recent = {t.apt for t in valid if t.date > recent_from}
    out += ["### 3. 참고: 단지 수와 단지별 거래 수", ""]
    out += table(["구분", "단지 수"],
                 [["`apt_complex` 전체", num(len(complexes))],
                  ["거래(해제·사라짐 포함)가 있는 단지", num(len({t.apt for t in trades}))],
                  ["유효 거래가 있는 단지", num(len(per))],
                  [f"최근 12개월({recent_from + timedelta(days=1)} – {last}) 유효 거래가 있는 단지", num(len(recent))]])

    edges = [(1, "1"), (4, "2–4"), (9, "5–9"), (49, "10–49"), (199, "50–199"), (999, "200–999"), (None, "1,000+")]
    c_cnt, t_cnt = Counter(), Counter()
    for n in per.values():
        b = bucket(n, edges)
        c_cnt[b] += 1
        t_cnt[b] += n
    out += ["단지별 유효 거래 수 (2020-01 이후 전체 기간):", ""]
    out += table(["유효 거래 수", "단지 수", "단지 비율", "거래 수", "거래 비율"],
                 [[label, num(c_cnt[label]), pct(c_cnt[label], len(per)), num(t_cnt[label]), pct(t_cnt[label], len(valid))]
                  for _, label in edges])
    out += ["유효 거래가 많은 단지:", ""]
    out += table(["단지", "유효 거래"], [[complex_label(complexes[a]), num(n)] for a, n in per.most_common(EXAMPLES)])

    by_sgg = defaultdict(lambda: [0, 0, 0])
    for apt, c in complexes.items():
        row = by_sgg[c["sgg_nm"]]
        row[0] += 1
        row[1] += apt in per
        row[2] += apt in recent
    out += ["구별 단지 수:", ""]
    out += table(["구", "단지", "유효 거래 있음", "최근 12개월 거래 있음"],
                 [[k, num(v[0]), num(v[1]), num(v[2])] for k, v in sorted(by_sgg.items(), key=lambda kv: -kv[1][0])])

    names = defaultdict(list)
    for c in complexes.values():
        names[c["name"]].append(c)
    dup = {n: cs for n, cs in names.items() if len(cs) > 1}
    same_sgg = {n: cs for n, cs in dup.items() if len({c["sgg_cd"] for c in cs}) < len(cs)}
    out += ["#### 같은 단지명을 쓰는 단지", "",
            f"단지명(`apt_complex.name`, 원문 그대로)이 두 단지 이상에 쓰이는 경우: 이름 {num(len(dup))}개, "
            f"단지 {num(sum(len(cs) for cs in dup.values()))}개 (전체 단지의 {pct(sum(len(cs) for cs in dup.values()), len(complexes))}). "
            f"그중 같은 구 안에서도 겹치는 이름 {num(len(same_sgg))}개.", ""]
    out += table(["단지명", "단지 수", "구 (단지 수)"],
                 [[n, len(cs), ", ".join(f"{k} {v}" for k, v in Counter(c["sgg_nm"] for c in cs).most_common(6))
                   + (" …" if len({c["sgg_nm"] for c in cs}) > 6 else "")]
                  for n, cs in sorted(dup.items(), key=lambda kv: (-len(kv[1]), kv[0]))[:EXAMPLES]], "lrl")


# ---------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser(description="3단계 설계 전 데이터 분석 (면적 묶기, 일괄 거래, 단지 분포)")
    parser.add_argument("--psql", default=DEFAULT_PSQL, help=f"psql 실행 명령 (기본: {DEFAULT_PSQL})")
    parser.add_argument("--out", help="출력 파일 (없으면 표준 출력)")
    args = parser.parse_args()

    complexes = {row["apt_seq"]: row for row in query(args.psql, COMPLEX_SQL)}
    trades = [Trade(row) for row in query(args.psql, TRADE_SQL)]
    valid = [t for t in trades if t.valid]

    out = [f"- 대상: `apt_trade` {num(len(trades))}건 (해제 {num(sum(t.cancelled for t in trades))}, "
           f"사라짐 {num(sum(t.missing for t in trades))}), 유효 거래 {num(len(valid))}건, 계약일 {valid_range(valid)}",
           f"- 단지 `apt_complex` {num(len(complexes))}개", ""]
    render_area(valid, complexes, out)
    render_bulk(trades, valid, complexes, out)
    render_reference(trades, valid, complexes, out)

    text = "\n".join(out).rstrip() + "\n"
    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    else:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
