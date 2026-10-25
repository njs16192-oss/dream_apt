package com.dreamapt.region;

import java.time.Clock;
import java.time.YearMonth;
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
     * @param from 시작 월 (포함). null이면 처음부터
     * @param to   끝 월 (포함). null이면 기준일이 속한 달
     */
    public MonthlySeries<MonthlyVolume> getMonthlyVolumes(String sggCd, YearMonth from, YearMonth to) {
        ReportingPeriod period = ReportingPeriod.of(clock);
        YearMonth end = to == null ? period.baseMonth() : to;
        if (from != null && from.isAfter(end)) {
            throw new BadRequestException("from(" + from + ")이 to(" + end + ")보다 늦습니다");
        }
        if (!regionRepository.existsById(sggCd)) {
            throw new NotFoundException("구가 없습니다: " + sggCd);
        }
        List<MonthlyVolume> months = queryRepository.findMonthlyVolumes(sggCd,
                        from == null ? null : from.atDay(1), end.plusMonths(1).atDay(1)).stream()
                .map(r -> new MonthlyVolume(r.month(), r.count(), r.bulkCount(), period.isProvisional(r.month())))
                .toList();
        return MonthlySeries.of(period, months);
    }
}
