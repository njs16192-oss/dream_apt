package com.dreamapt.region;

import java.time.Clock;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;

import com.dreamapt.common.BadRequestException;
import com.dreamapt.common.MonthlySeries;
import com.dreamapt.common.NotFoundException;
import com.dreamapt.common.ReportingPeriod;

import lombok.RequiredArgsConstructor;

/** 구 목록·구 월별 거래량 (ADR 007). */
@Service
@RequiredArgsConstructor
public class RegionQueryService {

    /** from을 생략했을 때 기간: to를 포함한 최근 36개월 */
    static final int DEFAULT_MONTHS = 36;

    /**
     * 요청할 수 있는 최대 기간. 구 월별 거래량은 조회 비용이 구 전체 거래 수(와 테이블 크기)에 비례해서
     * 기간을 제한한다 (ADR 007 ③ 측정: 노원구 전 기간 65–88ms)
     */
    static final int MAX_MONTHS = 120;

    private final RegionRepository regionRepository;
    private final RegionQueryRepository queryRepository;
    private final Clock clock;

    /** 수집 대상 구. 데이터가 있는 구만 보여준다. */
    public List<RegionItem> listRegions() {
        return regionRepository.findByCollectEnabledTrueOrderBySggCd().stream().map(RegionItem::from).toList();
    }

    /**
     * 구의 월별 거래량, 일괄 거래는 bulkCount로 따로 센다.
     *
     * @param from 시작 월 (포함). null이면 to를 포함한 최근 {@value #DEFAULT_MONTHS}개월
     * @param to   끝 월 (포함). null이면 기준일이 속한 달
     * @throws BadRequestException from이 to보다 늦거나 기간이 {@value #MAX_MONTHS}개월을 넘을 때
     */
    public MonthlySeries<MonthlyVolume> getMonthlyVolumes(String sggCd, YearMonth from, YearMonth to) {
        ReportingPeriod period = ReportingPeriod.of(clock);
        YearMonth end = to == null ? period.baseMonth() : to;
        YearMonth start = from == null ? end.minusMonths(DEFAULT_MONTHS - 1) : from;
        if (start.isAfter(end)) {
            throw new BadRequestException("from(" + start + ")이 to(" + end + ")보다 늦습니다");
        }
        long months = ChronoUnit.MONTHS.between(start, end) + 1;
        if (months > MAX_MONTHS) {
            throw new BadRequestException("기간은 최대 " + MAX_MONTHS + "개월입니다 (요청: " + months + "개월)");
        }
        if (!regionRepository.existsById(sggCd)) {
            throw new NotFoundException("구가 없습니다: " + sggCd);
        }
        List<MonthlyVolume> volumes = queryRepository.findMonthlyVolumes(sggCd, start.atDay(1),
                        end.plusMonths(1).atDay(1)).stream()
                .map(r -> new MonthlyVolume(r.month(), r.count(), r.bulkCount(), period.isProvisional(r.month())))
                .toList();
        return MonthlySeries.of(period, start, end, volumes);
    }
}
