package com.dreamapt.complex;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;

import com.dreamapt.common.BadRequestException;
import com.dreamapt.common.MonthlySeries;
import com.dreamapt.common.NotFoundException;
import com.dreamapt.common.PageResponse;
import com.dreamapt.common.ReportingPeriod;
import com.dreamapt.complex.ComplexQueryRepository.ComplexRow;
import com.dreamapt.complex.ComplexQueryRepository.NearbyRows;

import lombok.RequiredArgsConstructor;

/** 단지 검색·상세·월별 가격·거래 목록 (ADR 007). */
@Service
@RequiredArgsConstructor
public class ComplexQueryService {

    /** 유효 거래 중 일괄 거래가 이 비율 이상이면 일괄 거래 위주 단지로 본다 */
    public static final double BULK_DOMINANT_RATIO = 0.8;

    /** 검색 정렬에 쓰는 최근 거래 수의 기간 */
    static final int RECENT_MONTHS = 12;

    private final ComplexQueryRepository repository;
    private final Clock clock;

    public List<ComplexSearchItem> search(String q, String sggCd, int limit) {
        ReportingPeriod period = ReportingPeriod.of(clock);
        return repository.search(q.strip(), sggCd, period.baseDate().minusMonths(RECENT_MONTHS), limit);
    }

    public ComplexDetail getDetail(String aptSeq) {
        ComplexRow c = findComplex(aptSeq);
        List<AreaGroupSummary> areaGroups = repository.findAreaGroups(aptSeq);
        int tradeCount = areaGroups.stream().mapToInt(AreaGroupSummary::tradeCount).sum();
        int bulkCount = areaGroups.stream().mapToInt(AreaGroupSummary::bulkCount).sum();
        int total = tradeCount + bulkCount;
        double bulkRatio = total == 0 ? 0 : (double) bulkCount / total;
        return new ComplexDetail(c.aptSeq(), c.name(), c.sggCd(), c.sggName(), c.umdName(), c.buildYear(),
                c.roadAddress(), c.lat(), c.lng(), tradeCount, bulkCount, bulkRatio,
                total > 0 && bulkRatio >= BULK_DOMINANT_RATIO, areaGroups);
    }

    /** 반경 안 단지를 가까운 순으로 최대 limit개. 최근 거래 수는 검색과 같은 기준(기준일 전 12개월, 일괄 제외) */
    public NearbyComplexes nearby(double lat, double lng, int radius, int limit) {
        ReportingPeriod period = ReportingPeriod.of(clock);
        NearbyRows rows = repository.findNearby(lat, lng, radius, limit, period.baseDate().minusMonths(RECENT_MONTHS));
        return new NearbyComplexes(lat, lng, radius, rows.totalCount(), rows.items());
    }

    /**
     * 면적 그룹의 월별 중앙값·건수, 일괄 거래 제외.
     *
     * @param from 시작 월 (포함). null이면 처음부터
     * @param to   끝 월 (포함). null이면 기준일이 속한 달
     */
    public MonthlySeries<MonthlyPrice> getMonthlyPrices(String aptSeq, int areaGroup, YearMonth from, YearMonth to) {
        ReportingPeriod period = ReportingPeriod.of(clock);
        YearMonth end = to == null ? period.baseMonth() : to;
        if (from != null && from.isAfter(end)) {
            throw new BadRequestException("from(" + from + ")이 to(" + end + ")보다 늦습니다");
        }
        findComplex(aptSeq);
        if (!repository.existsAreaGroup(aptSeq, areaGroup)) {
            throw new NotFoundException("면적 그룹이 없습니다: " + aptSeq + " " + areaGroup + "㎡");
        }
        List<MonthlyPrice> months = repository.findMonthlyPrices(aptSeq, areaGroup,
                        from == null ? null : from.atDay(1), end.plusMonths(1).atDay(1)).stream()
                .map(r -> new MonthlyPrice(r.month(), r.medianAmount(), r.count(), period.isProvisional(r.month())))
                .toList();
        return MonthlySeries.of(period, from, end, months);
    }

    /**
     * 거래 목록, 최신순. 일괄 거래도 포함한다.
     *
     * @param areaGroup null이면 모든 면적. 단지에 없는 면적 그룹이면 빈 목록 (필터라서 404로 보지 않는다)
     */
    public PageResponse<TradeItem> getTrades(String aptSeq, Integer areaGroup, int page, int size) {
        findComplex(aptSeq);
        long total = repository.countTrades(aptSeq, areaGroup);
        List<TradeItem> content = repository.findTrades(aptSeq, areaGroup, size, (long) page * size);
        return PageResponse.of(content, page, size, total);
    }

    private ComplexRow findComplex(String aptSeq) {
        return repository.findComplex(aptSeq)
                .orElseThrow(() -> new NotFoundException("단지가 없습니다: " + aptSeq));
    }
}
