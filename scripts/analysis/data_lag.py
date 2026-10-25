"""계약 → 등기·해제 지연 분석.

실거래가 API 원본에서 계약일과 나중에 채워지는 등기일(rgstDate)·해제일(cdealDay)의 간격을 재고,
조회일이 다른 두 원본을 비교해 사건 날짜와 API 반영 사이의 시차를 본다. 수집 재조회 기간을 정하는 근거 자료다.
표준 라이브러리만 사용한다.

사용법은 scripts/README.md 참고.
"""

import argparse
import sys
from collections import Counter, defaultdict
from datetime import date, datetime
from pathlib import Path

from key_collision import (DEFAULT_RAW_DIR, DEFAULT_SNAPSHOT_DIR, deal_date, is_cancelled, load_fetched_at,
                           load_records, parse_page, save_fetched_at, trade_key)

SGG_NAMES = {"11500": "강서", "11680": "강남", "11710": "송파", "11350": "노원"}

# 이 개월 수 이상 지난 계약월은 등기·해제가 거의 끝났다고 보고 기준 분포로 쓴다
MATURE_MONTHS = 12
DAY_BIN = 30
DAY_BINS = 12      # 30일 × 12 = 360일까지, 그 뒤는 하나로 묶는다
MONTH_OFFSETS = 12  # 0 ~ 12개월, 그 뒤는 하나로 묶는다

# TradeReconciler와 같은 기준: 불변으로 비교하는 필드와 "빈 값 → 채워짐"이 허용되는 필드
IMMUTABLE_FIELDS = ("buyerGbn", "slerGbn", "dealingGbn", "estateAgentSggNm")
FILLABLE_FIELDS = ("aptDong", "rgstDate", "cdealDay")


# ---------------------------------------------------------------- 날짜

def parse_short_date(value):
    """'26.10.07' → date(2026, 10, 7). 빈 값이나 형식이 다르면 None."""
    try:
        yy, mm, dd = (int(p) for p in value.split("."))
        return date(2000 + yy, mm, dd)
    except (ValueError, AttributeError):
        return None


def contract_date(r):
    return date.fromisoformat(deal_date(r))


def month_index(d):
    return d.year * 12 + d.month - 1


def ym_index(ym):
    return int(ym[:4]) * 12 + int(ym[4:]) - 1


def fetched_date(fetched_at, sgg, ym):
    return datetime.fromisoformat(fetched_at[f"{sgg}_{ym}"]).date()


# ---------------------------------------------------------------- 이벤트

def collect_events(records, field, wanted):
    """wanted(r)인 레코드에서 field 날짜를 꺼내 (일수 차, 월 차) 목록과 문제 건수를 돌려준다."""
    lags, problems = [], Counter()
    for r in records:
        if not wanted(r):
            continue
        if not r.get(field):
            problems["날짜 없음"] += 1
            continue
        event = parse_short_date(r[field])
        if event is None:
            problems["형식 오류"] += 1
            continue
        start = contract_date(r)
        if event < start:
            problems["계약일보다 이름"] += 1
            continue
        lags.append(((event - start).days, month_index(event) - month_index(start)))
    return lags, problems


def registered_events(records):
    return collect_events(records, "rgstDate", lambda r: not is_cancelled(r))


def cancel_events(records):
    return collect_events(records, "cdealDay", is_cancelled)


def day_bucket(days):
    return min((days - 1) // DAY_BIN if days > 0 else 0, DAY_BINS)


def month_bucket(offset):
    return min(offset, MONTH_OFFSETS + 1)


def cumulative(lags, bucket, size):
    counts = Counter(bucket(v) for v in lags)
    running, rows = 0, []
    for b in range(size):
        running += counts[b]
        rows.append((counts[b], running))
    return rows


def pct(part, whole):
    return f"{part / whole * 100:.1f}%" if whole else "-"


# ---------------------------------------------------------------- 출력: 지연 분포

def day_label(b):
    return f"{b * DAY_BIN + 1}일 이상" if b == DAY_BINS else f"≤{(b + 1) * DAY_BIN}일"


def month_label(b):
    return f"M+{MONTH_OFFSETS + 1} 이후" if b == MONTH_OFFSETS + 1 else f"M+{b}"


def render_distribution(title, note, series, out):
    """series: [(열 이름, 지연 목록)]. 일수·계약월 기준 두 표를 그린다."""
    out.append(f"### {title}")
    out.append("")
    out.append(note)
    out.append("")
    out.append("n: " + ", ".join(f"{name} {len(lags)}건" for name, lags in series))
    out.append("")
    for unit, bucket, size, label in (
            ("계약일부터 일수", day_bucket, DAY_BINS + 1, day_label),
            ("계약월 기준 (M = 계약월)", month_bucket, MONTH_OFFSETS + 2, month_label)):
        out.append(f"{unit}:")
        out.append("")
        out.append(f"| 구간 | " + " | ".join(f"{name} 건수 | {name} 누적" for name, _ in series) + " |")
        out.append("|---|" + "---:|---:|" * len(series))
        tables = [cumulative([v[0] if bucket is day_bucket else v[1] for v in lags], bucket, size)
                  for _, lags in series]
        for b in range(size):
            cells = []
            for (_, lags), rows in zip(series, tables):
                n, run = rows[b]
                cells.append(f"{n} | {pct(run, len(lags))}")
            out.append(f"| {label(b)} | " + " | ".join(cells) + " |")
        out.append("")


def render_problems(label, problems, out):
    if problems:
        out.append(f"{label} 제외: " + ", ".join(f"{k} {v}건" for k, v in sorted(problems.items())))
        out.append("")


# ---------------------------------------------------------------- 출력: 계약월별 반영 비율

def render_by_month(combos, fetched_at, out):
    out.append("### 계약월별 현재 반영 비율")
    out.append("")
    out.append("경과 = 조회월 − 계약월. 등기 비율의 분모는 해제되지 않은 거래.")
    out.append("")
    months = sorted({c["ym"] for c in combos})
    sggs = [s for s in SGG_NAMES if any(c["sgg"] == s for c in combos)]

    def row(cs):
        recs = [r for c in cs for r in c["records"]]
        normal = [r for r in recs if not is_cancelled(r)]
        reg = sum(1 for r in normal if r.get("rgstDate"))
        cancelled = len(recs) - len(normal)
        return len(recs), len(normal), reg, cancelled

    out.append("| 계약월 | 경과 | 전체 | 정상 | 등기 있음 | 등기 비율 | 해제 | 해제 비율 |")
    out.append("|---|---:|---:|---:|---:|---:|---:|---:|")
    for ym in months:
        cs = [c for c in combos if c["ym"] == ym]
        fetched = fetched_date(fetched_at, cs[0]["sgg"], ym)
        total, normal, reg, cancelled = row(cs)
        out.append(f"| {ym} | {month_index(fetched) - ym_index(ym)}개월 | {total} | {normal} | {reg} | "
                   f"{pct(reg, normal)} | {cancelled} | {pct(cancelled, total)} |")
    out.append("")
    out.append("구별 등기 비율 / 해제 비율 (괄호 안은 전체 건수):")
    out.append("")
    out.append("| 계약월 | " + " | ".join(f"{s} {SGG_NAMES[s]}" for s in sggs) + " |")
    out.append("|---|" + "---|" * len(sggs))
    for ym in months:
        cells = []
        for s in sggs:
            total, normal, reg, cancelled = row([c for c in combos if c["ym"] == ym and c["sgg"] == s])
            cells.append(f"{pct(reg, normal)} / {pct(cancelled, total)} ({total})")
        out.append(f"| {ym} | " + " | ".join(cells) + " |")
    out.append("")


# ---------------------------------------------------------------- 출력: 재조회 기간별 반영률

def render_window(reg_lags, cancel_lags, out):
    out.append("### 재조회 기간별 반영률 (판단용)")
    out.append("")
    out.append(f"계약월 M의 데이터를 M+k월 말까지 다시 받으면, 최종 등기·해제 중 사건 날짜가 그 안에 든 비율. "
               f"기준: 경과 {MATURE_MONTHS}개월 이상 계약월 (등기 n={len(reg_lags)}, 해제 n={len(cancel_lags)}).")
    out.append("")
    out.append("| k | 등기 누적 | 해제 누적 |")
    out.append("|---|---:|---:|")
    reg = cumulative([v[1] for v in reg_lags], month_bucket, MONTH_OFFSETS + 1)
    can = cumulative([v[1] for v in cancel_lags], month_bucket, MONTH_OFFSETS + 1)
    for k in range(MONTH_OFFSETS + 1):
        out.append(f"| M+{k} | {pct(reg[k][1], len(reg_lags))} | {pct(can[k][1], len(cancel_lags))} |")
    out.append("")
    out.append("사건 날짜 기준이다. API 반영은 그보다 늦다 (해제는 해제일로부터 30일 안에 신고, 등기는 연계 시차). "
               "실제 반영은 아래 \"API 반영 시차\"만큼 더 늦다.")
    out.append("")


# ---------------------------------------------------------------- 출력: 두 원본 비교

def load_snapshot(snapshot_dir, sgg, ym):
    """캐시된 페이지만 읽는다. API는 호출하지 않는다."""
    paths = sorted(snapshot_dir.glob(f"{sgg}_{ym}_p*.xml"), key=lambda p: int(p.stem.rsplit("_p", 1)[1]))
    if not paths:
        sys.exit(f"{snapshot_dir}에 {sgg}_{ym} 원본이 없습니다")
    records = []
    for p in paths:
        items, _ = parse_page(p.read_bytes(), p.name)
        records.extend(items)
    return records


def same_values(b, a):
    return all(b.get(f, "") == a.get(f, "") for f in IMMUTABLE_FIELDS + FILLABLE_FIELDS + ("cdealType",))


def can_fill(b, a):
    """TradeReconciler.canFill과 같은 규칙: 불변 필드가 같고, 가변 필드는 빈 값 → 채워짐만 허용."""
    if any(b.get(f, "") != a.get(f, "") for f in IMMUTABLE_FIELDS):
        return False
    if is_cancelled(b) and not is_cancelled(a):
        return False
    return all(not b.get(f) or b.get(f) == a.get(f) for f in FILLABLE_FIELDS)


def fill_state(r):
    return tuple(r.get(f, "") for f in FILLABLE_FIELDS) + (is_cancelled(r),)


def max_matching(rows, recs):
    """Kuhn 알고리즘. 행 인덱스별 짝지어진 레코드 인덱스(-1은 없음)."""
    matched = [-1] * len(rows)

    def augment(i, visited):
        for j, b in enumerate(rows):
            if visited[j] or not can_fill(b, recs[i]):
                continue
            visited[j] = True
            if matched[j] < 0 or augment(matched[j], visited):
                matched[j] = i
                return True
        return False

    for i in range(len(recs)):
        augment(i, [False] * len(rows))
    return matched


def reconcile(before, after):
    """ADR 004 대조. (짝 목록 [(이전, 이후, 모호 여부)], 새로 나타난 레코드, 사라진 레코드)."""
    groups = defaultdict(lambda: ([], []))
    for r in before:
        groups[trade_key(r)][0].append(r)
    for r in after:
        groups[trade_key(r)][1].append(r)

    pairs, inserted, missing = [], [], []
    for rows, recs in groups.values():
        rows = list(rows)
        rest = []
        for a in recs:
            same = next((b for b in rows if same_values(b, a)), None)
            if same is None:
                rest.append(a)
            else:
                rows.remove(same)
                pairs.append((same, a, False))
        matched = max_matching(rows, rest)
        used = set()
        for j, i in enumerate(matched):
            if i < 0:
                missing.append(rows[j])
                continue
            used.add(i)
            # 같은 레코드로 채워질 수 있는 다른 이전 행이 상태가 다르면, 무엇이 새로 채워졌는지 하나로 정해지지 않는다
            alternatives = {fill_state(b) for b in rows if can_fill(b, rest[i])}
            pairs.append((rows[j], rest[i], len(alternatives) > 1))
        inserted.extend(a for i, a in enumerate(rest) if i not in used)
    return pairs, inserted, missing


def render_reflection(before, after, before_at, after_at, label, out):
    pairs, inserted, missing = reconcile(before, after)
    events = []
    for b, a, ambiguous in pairs:
        for field, name in (("rgstDate", "등기일"), ("cdealDay", "해제일")):
            if a.get(field) and not b.get(field):
                events.append((name, b, a, parse_short_date(a[field]), ambiguous))
    changed = sum(1 for b, a, _ in pairs if not same_values(b, a))

    out.append(f"### 사건 날짜 → API 반영 시차 ({label})")
    out.append("")
    out.append(f"{before_at} 원본 {len(before)}건과 {after_at} 원본 {len(after)}건을 ADR 004 규칙으로 대조했다. "
               f"짝 {len(pairs)}쌍 (값이 바뀐 쌍 {changed}), 새로 나타남 {len(inserted)}건, 사라짐 {len(missing)}건.")
    out.append("")
    out.append(f"{before_at}에 비어 있다가 {after_at}에 채워진 값의 반영 시차 = "
               f"[{before_at} − 사건일, {after_at} − 사건일]일 (반영은 두 조회 사이 어느 시점). 사건일이 이전 조회보다 늦으면 하한은 0.")
    out.append("")
    out.append("| 필드 | 단지 | 계약일 | 사건일 | 반영 시차(일) | 모호 |")
    out.append("|---|---|---|---|---|---|")
    for name, b, a, event, ambiguous in sorted(events, key=lambda e: (e[0], e[3] or date.min)):
        lo = max((before_at - event).days, 0)
        hi = (after_at - event).days
        out.append(f"| {name} | `{a['aptSeq']}` {a['aptNm']} | {deal_date(a)} | {event} | [{lo}, {hi}] | "
                   f"{'모호' if ambiguous else ''} |")
    out.append("")
    for name in ("등기일", "해제일"):
        es = [e for e in events if e[0] == name]
        if es:
            his = sorted((after_at - e[3]).days for e in es)
            out.append(f"- {name}: n={len(es)} (모호 {sum(1 for e in es if e[4])}), "
                       f"상한 기준 최소 {his[0]}일 / 중앙 {his[len(his) // 2]}일 / 최대 {his[-1]}일")
    out.append("")
    if inserted:
        out.append("새로 나타난 레코드:")
        out.append("")
        out.append("| 단지 | 계약일 | 면적 | 층 | 금액 | 해제 | 등기일 |")
        out.append("|---|---|---:|---:|---:|---|---|")
        for a in inserted:
            out.append(f"| `{a['aptSeq']}` {a['aptNm']} | {deal_date(a)} | {a['excluUseAr']} | {a['floor']} | "
                       f"{a['dealAmount']} | {a.get('cdealDay') or '-'} | {a.get('rgstDate') or '-'} |")
        out.append("")
    if missing:
        out.append(f"사라진 레코드 {len(missing)}건: " + ", ".join(
            f"`{b['aptSeq']}` {deal_date(b)} {b['dealAmount']}" for b in missing))
        out.append("")


# ---------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser(description="계약 → 등기·해제 지연 분석")
    parser.add_argument("--sgg", required=True, help="시군구 코드, 쉼표로 구분 (예: 11500,11680)")
    parser.add_argument("--months", required=True, help="계약월 YYYYMM, 쉼표로 구분 (예: 202507,202607)")
    parser.add_argument("--raw-dir", default=DEFAULT_RAW_DIR, help=f"원본 XML 캐시 디렉터리 (기본 {DEFAULT_RAW_DIR})")
    parser.add_argument("--snapshot-dir", default=DEFAULT_SNAPSHOT_DIR,
                        help=f"새로 받은 원본을 조회일별로 남길 디렉터리 (기본 {DEFAULT_SNAPSHOT_DIR})")
    parser.add_argument("--before-snapshot", help="반영 시차 비교용 이전 원본 디렉터리 (예: data/raw-snapshots/2026-10-22)")
    parser.add_argument("--before-at", help="이전 원본 조회일 YYYY-MM-DD (기본: --before-snapshot 디렉터리 이름)")
    parser.add_argument("--compare", default="11500_202609", help="비교할 구·월 (기본 11500_202609)")
    parser.add_argument("--out", help="출력 파일 (없으면 표준 출력)")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

    raw_dir = Path(args.raw_dir)
    sggs = [s.strip() for s in args.sgg.split(",") if s.strip()]
    months = [m.strip() for m in args.months.split(",") if m.strip()]

    fetched_at = load_fetched_at(raw_dir)
    combos = []
    for sgg in sggs:
        for ym in months:
            records = load_records(raw_dir, sgg, ym, fetched_at, Path(args.snapshot_dir))
            combos.append({"sgg": sgg, "ym": ym, "records": records})
    if fetched_at:
        save_fetched_at(raw_dir, fetched_at)

    def elapsed(c):
        return month_index(fetched_date(fetched_at, c["sgg"], c["ym"])) - ym_index(c["ym"])

    mature = [r for c in combos if elapsed(c) >= MATURE_MONTHS for r in c["records"]]
    recent = [r for c in combos if elapsed(c) < MATURE_MONTHS for r in c["records"]]
    mature_reg, mature_reg_p = registered_events(mature)
    recent_reg, recent_reg_p = registered_events(recent)
    mature_can, mature_can_p = cancel_events(mature)
    recent_can, recent_can_p = cancel_events(recent)
    mature_name = f"경과 {MATURE_MONTHS}개월+"
    recent_name = f"경과 {MATURE_MONTHS}개월 미만(참고)"

    out = []
    out.append("- 생성 명령어: `python scripts/analysis/data_lag.py " + " ".join(sys.argv[1:]) + "`")
    out.append("- 데이터 조회 일시: " + ", ".join(
        f"{c['sgg']}_{c['ym']} {fetched_at.get(c['sgg'] + '_' + c['ym'], '알 수 없음')}" for c in combos))
    out.append("")

    render_distribution(
        "계약 → 등기 지연 분포",
        "해제되지 않은 거래 중 등기일이 있는 건. 누적 비율의 분모는 각 열의 n. "
        f"{recent_name}는 아직 등기가 덜 된 상태라 지연이 짧게 나온다.",
        [(mature_name, mature_reg), (recent_name, recent_reg)], out)
    render_problems(f"{mature_name} 등기", mature_reg_p, out)
    render_problems(f"{recent_name} 등기", recent_reg_p, out)

    out.append(f"구별 ({mature_name}, 계약월 기준 누적):")
    out.append("")
    by_sgg = {s: registered_events([r for c in combos if c["sgg"] == s and elapsed(c) >= MATURE_MONTHS
                                    for r in c["records"]])[0] for s in sggs}
    out.append("| 구간 | " + " | ".join(f"{s} {SGG_NAMES.get(s, '')} (n={len(by_sgg[s])})" for s in sggs) + " |")
    out.append("|---|" + "---:|" * len(sggs))
    tables = {s: cumulative([v[1] for v in by_sgg[s]], month_bucket, MONTH_OFFSETS + 2) for s in sggs}
    for b in range(MONTH_OFFSETS + 2):
        out.append(f"| {month_label(b)} | " + " | ".join(pct(tables[s][b][1], len(by_sgg[s])) for s in sggs) + " |")
    out.append("")

    render_distribution(
        "계약 → 해제 지연 분포",
        "해제된 거래(cdealType 있음)의 계약일 → 해제일. 누적 비율의 분모는 각 열의 n.",
        [(mature_name, mature_can), (recent_name, recent_can)], out)
    render_problems(f"{mature_name} 해제", mature_can_p, out)
    render_problems(f"{recent_name} 해제", recent_can_p, out)

    render_by_month(combos, fetched_at, out)
    render_window(mature_reg, mature_can, out)

    if args.before_snapshot:
        snap = Path(args.before_snapshot)
        sgg, ym = args.compare.split("_")
        before_at = date.fromisoformat(args.before_at or snap.name)
        after_at = fetched_date(fetched_at, sgg, ym)
        after = next(c["records"] for c in combos if c["sgg"] == sgg and c["ym"] == ym)
        render_reflection(load_snapshot(snap, sgg, ym), after, before_at, after_at,
                          f"{sgg} {SGG_NAMES.get(sgg, '')} {ym}", out)

    text = "\n".join(out).rstrip() + "\n"
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8", newline="\n")
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
