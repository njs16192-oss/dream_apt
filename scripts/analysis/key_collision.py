"""거래 unique 키 충돌 분석.

ADR 002의 거래 키 (aptSeq, 계약일, 전용면적, 층, 금액)를 실거래가 API 원본에 적용해
키가 겹치는 레코드 그룹을 찾고 유형별로 분류한다. 표준 라이브러리만 사용한다.

사용법은 scripts/README.md 참고.
"""

import argparse
import json
import os
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from datetime import date, datetime
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

API_URL = "https://apis.data.go.kr/1613000/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev"
PAGE_SIZE = 1000
MAX_PAGES = 20
DEFAULT_RAW_DIR = "data/raw"

# 나중에 채워지거나 바뀌는 값. 분류는 이 필드를 뺀 나머지(불변 필드)로 한다.
MUTABLE_FIELDS = ("cdealType", "cdealDay", "rgstDate", "aptDong")

TYPE_ORDER = ("a", "b", "b'", "c")
TYPE_LABELS = {
    "a": "(a) 해제 후 재신고 후보",
    "b": "(b) 모두 정상",
    "b'": "(b′) 일부만 해제",
    "c": "(c) 기타",
}


# ---------------------------------------------------------------- 조회 / 캐시

def raw_path(raw_dir, sgg, ym, page):
    return raw_dir / f"{sgg}_{ym}_p{page}.xml"


def fetch_page(sgg, ym, page):
    api_key = os.environ.get("DATA_GO_KR_API_KEY", "")
    if not api_key:
        sys.exit("환경변수 DATA_GO_KR_API_KEY가 없습니다 (공공데이터포털 Decoding 키).")
    # Decoding 키를 urlencode로 한 번만 인코딩한다 ('+' 등이 %2B로 바뀌어야 인증된다)
    query = urllib.parse.urlencode({
        "serviceKey": api_key,
        "LAWD_CD": sgg,
        "DEAL_YMD": ym,
        "pageNo": page,
        "numOfRows": PAGE_SIZE,
    })
    with urllib.request.urlopen(f"{API_URL}?{query}", timeout=30) as res:
        return res.read()


def parse_page(xml_bytes, source):
    root = ET.fromstring(xml_bytes)
    code = root.findtext("header/resultCode")
    if code != "000":
        raise RuntimeError(f"{source}: resultCode={code} {root.findtext('header/resultMsg')}")
    items = [{child.tag: (child.text or "").strip() for child in item}
             for item in root.iter("item")]
    return items, int(root.findtext("body/totalCount") or 0)


def load_fetched_at(raw_dir):
    path = raw_dir / "fetched_at.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}


def save_fetched_at(raw_dir, fetched_at):
    path = raw_dir / "fetched_at.json"
    path.write_text(json.dumps(fetched_at, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")


def load_records(raw_dir, sgg, ym, fetched_at):
    """한 구·월의 전체 레코드. 캐시된 페이지는 다시 호출하지 않는다."""
    records = []
    for page in range(1, MAX_PAGES + 1):
        path = raw_path(raw_dir, sgg, ym, page)
        if path.exists():
            data = path.read_bytes()
        else:
            data = fetch_page(sgg, ym, page)
            parse_page(data, path.name)  # 실패 응답은 저장하지 않는다
            raw_dir.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            fetched_at.setdefault(f"{sgg}_{ym}", datetime.now().astimezone().isoformat(timespec="seconds"))
            print(f"조회: {path.name}", file=sys.stderr)
        items, total = parse_page(data, path.name)
        records.extend(items)
        if not items or len(records) >= total:
            break
    else:
        raise RuntimeError(f"{sgg}_{ym}: 페이지 수가 {MAX_PAGES}를 넘었습니다")

    for idx, r in enumerate(records):
        r["_idx"] = idx
        r["_sgg"] = sgg
        r["_ym"] = ym
    return records


# ---------------------------------------------------------------- 키 / 분류

def area2(r):
    # 수집 코드(TradeXmlParser)와 같이 소수 둘째 자리 HALF_UP
    return Decimal(r["excluUseAr"]).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def deal_date(r):
    return f"{int(r['dealYear']):04d}-{int(r['dealMonth']):02d}-{int(r['dealDay']):02d}"


def trade_key(r):
    return (r["aptSeq"], deal_date(r), area2(r), int(r["floor"]), int(r["dealAmount"].replace(",", "")))


def is_cancelled(r):
    return bool(r.get("cdealType"))


def field_names(records):
    names = set()
    for r in records:
        names.update(k for k in r if not k.startswith("_"))
    return sorted(names)


def differing_fields(records, fields):
    return [f for f in fields if len({r.get(f, "") for r in records}) > 1]


def classify(records):
    fields = field_names(records)
    immutable = [f for f in fields if f not in MUTABLE_FIELDS]
    diff_immutable = differing_fields(records, immutable)
    diff_mutable = differing_fields(records, [f for f in MUTABLE_FIELDS if f in fields])
    cancelled = sum(1 for r in records if is_cancelled(r))
    normal = len(records) - cancelled

    if diff_immutable:
        gtype, reason = "c", "불변 필드 차이: " + ", ".join(diff_immutable)
    elif cancelled == 0:
        gtype, reason = "b", ""
    elif normal == 1:
        gtype, reason = "a", ""
    elif normal >= 2:
        gtype, reason = "b'", ""
    else:
        gtype, reason = "c", "전부 해제"
    return {
        "type": gtype,
        "reason": reason,
        "diff_immutable": diff_immutable,
        "diff_mutable": diff_mutable,
        "normal": normal,
        "cancelled": cancelled,
    }


def presence(records, field):
    filled = sum(1 for r in records if r.get(field))
    if filled == 0:
        return "모두 공백"
    if filled < len(records):
        return "일부만 채워짐"
    return "모두 채워짐"


def apt_dong_state(records):
    state = presence(records, "aptDong")
    if state == "모두 채워짐":
        distinct = len({r["aptDong"] for r in records})
        if distinct == len(records):
            return "모두 채워짐·전부 다름"
        return "모두 채워짐·모두 같음" if distinct == 1 else "모두 채워짐·일부 같음"
    return state


def find_groups(records):
    by_key = defaultdict(list)
    for r in records:
        by_key[trade_key(r)].append(r)
    dups = [rs for rs in by_key.values() if len(rs) > 1]
    dups.sort(key=lambda rs: rs[0]["_idx"])

    same_day = Counter((r["aptSeq"], deal_date(r)) for r in records)
    groups = []
    for n, rs in enumerate(dups, start=1):
        first = rs[0]
        g = {
            "id": f"{first['_sgg']}-{first['_ym']}-G{n:02d}",
            "sgg": first["_sgg"],
            "ym": first["_ym"],
            "records": rs,
            "key": trade_key(first),
            "same_day": same_day[(first["aptSeq"], deal_date(first))],
        }
        g.update(classify(rs))
        groups.append(g)
    return groups


def find_modified_rereports(records):
    """해제 레코드와 aptSeq·면적·층이 같지만 키는 다른 정상 레코드 (값을 바꿔 다시 신고한 후보).

    키가 같은 정상 레코드가 이미 있는 해제 레코드는 충돌 그룹에서 다루므로 뺀다.
    """
    normals = defaultdict(list)
    normal_keys = set()
    for r in records:
        if not is_cancelled(r):
            normals[(r["aptSeq"], area2(r), int(r["floor"]))].append(r)
            normal_keys.add(trade_key(r))
    rows = []
    for c in records:
        if not is_cancelled(c) or trade_key(c) in normal_keys:
            continue
        matches = [n for n in normals[(c["aptSeq"], area2(c), int(c["floor"]))] if trade_key(n) != trade_key(c)]
        if matches:
            rows.append((c, matches))
    return rows


# ---------------------------------------------------------------- 출력

def cell(value):
    return f"`{value}`" if value else "(공백)"


def amount(r):
    return f"{r['dealAmount']}만원"


def group_title(g):
    r = g["records"][0]
    return (f"{g['id']}. `{r['aptSeq']}` {r['aptNm']} — {deal_date(r)}, {area2(r)}㎡, "
            f"{r['floor']}층, {amount(r)} ({len(g['records'])}건)")


def group_detail(g, out):
    rs = g["records"]
    r0 = rs[0]
    out.append(f"#### {group_title(g)}")
    out.append("")
    out.append(f"- 유형: {TYPE_LABELS[g['type']]}" + (f" — {g['reason']}" if g["reason"] else ""))
    out.append(f"- 정상 {g['normal']}건 / 해제 {g['cancelled']}건")
    out.append(f"- 다른 불변 필드: {', '.join(g['diff_immutable']) or '없음'}")
    out.append(f"- 다른 가변 필드: {', '.join(g['diff_mutable']) or '없음'}")
    out.append(f"- 같은 단지·같은 날 거래: {g['same_day']}건 / 매도 `{r0['slerGbn']}` → 매수 `{r0['buyerGbn']}`, "
               f"`{r0['dealingGbn']}`, 준공 {r0['buildYear']}")
    out.append("")
    out.append("<details><summary>전체 필드 비교</summary>")
    out.append("")
    out.append("| 필드 | " + " | ".join(f"응답 #{r['_idx']}" for r in rs) + " |")
    out.append("|---|" + "---|" * len(rs))
    differs = set(g["diff_immutable"]) | set(g["diff_mutable"])
    for f in field_names(rs):
        name = f"{f} (가변)" if f in MUTABLE_FIELDS else f
        vals = [cell(r.get(f, "")) for r in rs]
        if f in differs:
            vals = [f"**{v}**" for v in vals]
        out.append(f"| {name} | " + " | ".join(vals) + " |")
    out.append("")
    out.append("</details>")
    out.append("")


def group_row_header():
    return ("| 그룹 | 유형 | 단지 | 계약일 | 면적 | 층 | 금액 | 정상/해제 | 다른 가변 필드 | aptDong | rgstDate | 같은 날 단지 거래 | 매도→매수 | 거래 유형 |\n"
            "|---|---|---|---|---:|---:|---:|---|---|---|---|---:|---|---|")


def group_row(g):
    r = g["records"][0]
    return (f"| {g['id']} | {TYPE_LABELS[g['type']].split(' ')[0]} | `{r['aptSeq']}` {r['aptNm']} | {deal_date(r)} | "
            f"{area2(r)} | {r['floor']} | {r['dealAmount']} | {g['normal']}/{g['cancelled']} | "
            f"{', '.join(g['diff_mutable']) or '-'} | {apt_dong_state(g['records'])} | {presence(g['records'], 'rgstDate')} | "
            f"{g['same_day']} | {r['slerGbn']}→{r['buyerGbn']} | {r['dealingGbn']} |")


def stats(groups):
    return (len(groups), sum(len(g["records"]) for g in groups), sum(len(g["records"]) - 1 for g in groups))


def render_summary(combos, groups, rereports, out):
    sggs = sorted({c["sgg"] for c in combos})

    out.append("### 구×월 요약")
    out.append("")
    out.append("| 구 | 월 | 전체 | 충돌 그룹 | 관련 레코드 | 병합 | 충돌 비율 |")
    out.append("|---|---|---:|---:|---:|---:|---:|")
    for c in combos:
        gs = [g for g in groups if g["sgg"] == c["sgg"] and g["ym"] == c["ym"]]
        n, recs, merged = stats(gs)
        ratio = f"{merged / c['total'] * 100:.1f}%" if c["total"] else "-"
        out.append(f"| {c['sgg']} | {c['ym']} | {c['total']} | {n} | {recs} | {merged} | {ratio} |")
    total = sum(c["total"] for c in combos)
    n, recs, merged = stats(groups)
    ratio = f"{merged / total * 100:.1f}%" if total else "-"
    out.append(f"| **합계** | | **{total}** | **{n}** | **{recs}** | **{merged}** | **{ratio}** |")
    out.append("")
    out.append("충돌 비율 = 병합(현재 키로 DB에서 사라지는 건수) / 전체")
    out.append("")

    out.append("### 유형별 집계")
    out.append("")
    out.append("| 유형 | 그룹 | 관련 레코드 | 병합 |")
    out.append("|---|---:|---:|---:|")
    for t in TYPE_ORDER:
        n, recs, merged = stats([g for g in groups if g["type"] == t])
        out.append(f"| {TYPE_LABELS[t]} | {n} | {recs} | {merged} |")
    n, recs, merged = stats(groups)
    out.append(f"| **합계** | **{n}** | **{recs}** | **{merged}** |")
    out.append("")
    if len(sggs) > 1:
        out.append("구별 (그룹 수 / 병합 건수):")
        out.append("")
        out.append("| 구 | " + " | ".join(TYPE_LABELS[t] for t in TYPE_ORDER) + " | 합계 |")
        out.append("|---|" + "---:|" * (len(TYPE_ORDER) + 1))
        for s in sggs:
            gs = [g for g in groups if g["sgg"] == s]
            cells = []
            for t in TYPE_ORDER:
                n, _, merged = stats([g for g in gs if g["type"] == t])
                cells.append(f"{n} / {merged}")
            n, _, merged = stats(gs)
            out.append(f"| {s} | " + " | ".join(cells) + f" | {n} / {merged} |")
        out.append("")
    c_reasons = Counter(g["reason"] for g in groups if g["type"] == "c")
    if c_reasons:
        out.append("(c) 사유: " + ", ".join(f"{k} {v}그룹" for k, v in sorted(c_reasons.items())))
        out.append("")

    out.append("### 가변 필드 차이")
    out.append("")
    out.append("그룹 안에서 레코드끼리 값이 다른 가변 필드별 그룹 수.")
    out.append("")
    out.append("| 유형 | 그룹 | " + " | ".join(MUTABLE_FIELDS) + " | 차이 없음 |")
    out.append("|---|---:|" + "---:|" * (len(MUTABLE_FIELDS) + 1))
    for t in TYPE_ORDER:
        gs = [g for g in groups if g["type"] == t]
        counts = [sum(1 for g in gs if f in g["diff_mutable"]) for f in MUTABLE_FIELDS]
        none = sum(1 for g in gs if not g["diff_mutable"])
        out.append(f"| {TYPE_LABELS[t]} | {len(gs)} | " + " | ".join(map(str, counts)) + f" | {none} |")
    out.append("")

    out.append("### (b) 그룹의 aptDong × rgstDate")
    out.append("")
    out.append("동 정보로 레코드를 구분할 수 있는지 (\"모두 채워짐·전부 다름\"이면 동으로 구분 가능). 그룹 수.")
    out.append("")
    dong_states = ("모두 채워짐·전부 다름", "모두 채워짐·일부 같음", "모두 채워짐·모두 같음", "일부만 채워짐", "모두 공백")
    rgst_states = ("모두 채워짐", "일부만 채워짐", "모두 공백")
    bs = [g for g in groups if g["type"] == "b"]
    out.append("| aptDong \\ rgstDate | " + " | ".join(rgst_states) + " | 합계 |")
    out.append("|---|" + "---:|" * (len(rgst_states) + 1))
    for d in dong_states:
        row = [sum(1 for g in bs if apt_dong_state(g["records"]) == d and presence(g["records"], "rgstDate") == rs)
               for rs in rgst_states]
        out.append(f"| {d} | " + " | ".join(map(str, row)) + f" | {sum(row)} |")
    out.append("")

    out.append("### 그룹 목록")
    out.append("")
    out.append(group_row_header())
    for g in groups:
        out.append(group_row(g))
    out.append("")

    out.append("### 원본 비교 — (b′)·(c) 전부, (a)·(b) 대표")
    out.append("")
    for g in representative_groups(groups):
        group_detail(g, out)

    out.append("### 보조: 값이 바뀐 재신고 후보")
    out.append("")
    out.append("키가 같은 정상 레코드가 없는 해제 레코드 중, 같은 구·조회 기간 안에 aptSeq·면적·층이 같은 정상 레코드가 있는 경우. "
               "같은 타입의 다른 호실일 수도 있어서 후보일 뿐이다. 현재 키로는 서로 다른 거래로 저장된다.")
    out.append("")
    if not rereports:
        out.append("없음")
        out.append("")
        return
    out.append("| 해제 레코드 | 단지 | 면적 | 층 | 해제 건 (계약일 / 금액 / 해제일) | 정상 건 (계약일 / 금액 / 계약일 차이 / 응답) |")
    out.append("|---|---|---:|---:|---|---|")
    for c, matches in rereports:
        normal_desc = "<br>".join(f"{deal_date(n)} / {n['dealAmount']} / {days_between(c, n):+d}일 / {n['_ym']} #{n['_idx']}"
                                  for n in matches)
        out.append(f"| {c['_sgg']}-{c['_ym']} #{c['_idx']} | `{c['aptSeq']}` {c['aptNm']} | {area2(c)} | {c['floor']} | "
                   f"{deal_date(c)} / {c['dealAmount']} / {c['cdealDay']} | {normal_desc} |")
    out.append("")


def days_between(a, b):
    return (date.fromisoformat(deal_date(b)) - date.fromisoformat(deal_date(a))).days


def representative_groups(groups):
    picked = [g for g in groups if g["type"] in ("b'", "c")]
    # (a)는 해제가 여러 번인 그룹을 먼저
    picked += sorted((g for g in groups if g["type"] == "a"), key=lambda g: (-g["cancelled"], g["id"]))[:2]
    # (b)는 동으로 구분되는 그룹을 먼저, 그다음 큰 그룹
    bs = sorted((g for g in groups if g["type"] == "b"),
                key=lambda g: ("aptDong" not in g["diff_mutable"], -len(g["records"]), g["id"]))
    picked += bs[:2]
    order = {g["id"]: i for i, g in enumerate(groups)}
    return sorted(picked, key=lambda g: order[g["id"]])


def render_full(groups, out):
    for g in groups:
        group_detail(g, out)


# ---------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser(description="거래 unique 키 충돌 분석")
    parser.add_argument("--sgg", required=True, help="시군구 코드, 쉼표로 구분 (예: 11500,11680)")
    parser.add_argument("--months", required=True, help="계약월 YYYYMM, 쉼표로 구분 (예: 202607,202608)")
    parser.add_argument("--raw-dir", default=DEFAULT_RAW_DIR, help=f"원본 XML 캐시 디렉터리 (기본 {DEFAULT_RAW_DIR})")
    parser.add_argument("--mode", choices=("summary", "full"), default="summary",
                        help="summary: 본문용 요약, full: 부록용 전체 그룹 비교")
    parser.add_argument("--out", help="출력 파일 (없으면 표준 출력)")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

    raw_dir = Path(args.raw_dir)
    sggs = [s.strip() for s in args.sgg.split(",") if s.strip()]
    months = [m.strip() for m in args.months.split(",") if m.strip()]

    fetched_at = load_fetched_at(raw_dir)
    combos, groups, rereports = [], [], []
    for sgg in sggs:
        sgg_records = []
        for ym in months:
            records = load_records(raw_dir, sgg, ym, fetched_at)
            combos.append({"sgg": sgg, "ym": ym, "total": len(records)})
            groups.extend(find_groups(records))
            sgg_records.extend(records)
        rereports.extend(find_modified_rereports(sgg_records))
    if fetched_at:
        save_fetched_at(raw_dir, fetched_at)

    out = []
    if args.mode == "full":
        command = "python scripts/analysis/key_collision.py " + " ".join(sys.argv[1:])
        out.append("# 거래 unique 키 충돌 — 전체 그룹 원본 비교 (부록)")
        out.append("")
        out.append(f"- 생성 명령어: `{command}`")
        out.append("- 데이터 조회 일시:")
        for c in combos:
            out.append(f"  - {c['sgg']}_{c['ym']}: {fetched_at.get(c['sgg'] + '_' + c['ym'], '알 수 없음')} ({c['total']}건)")
        out.append("- `응답 #n`은 해당 구·월 API 응답 `items` 안의 순번(0부터, 페이지를 이어 붙인 순서)이다.")
        out.append("- 굵게 표시한 값은 그룹 안에서 서로 다른 필드다. `(가변)`은 나중에 채워지거나 바뀌는 필드다.")
        out.append("")
        render_full(groups, out)
    else:
        render_summary(combos, groups, rereports, out)

    text = "\n".join(out).rstrip() + "\n"
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8", newline="\n")
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
