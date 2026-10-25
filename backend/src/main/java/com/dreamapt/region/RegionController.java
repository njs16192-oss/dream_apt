package com.dreamapt.region;

import static com.dreamapt.common.ApiDescriptions.BULK;
import static com.dreamapt.common.ApiDescriptions.BULK_EXCLUDED;
import static com.dreamapt.common.ApiDescriptions.PROVISIONAL;
import static com.dreamapt.common.ApiDescriptions.YEAR_MONTH;

import java.time.YearMonth;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dreamapt.common.MonthlySeries;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** 지역(시군구) 조회 API (ADR 007). */
@Tag(name = "지역", description = "시군구 목록, 시군구 월별 거래량")
@RestController
@RequestMapping("/api/regions")
@RequiredArgsConstructor
public class RegionController {

    private final RegionQueryService service;

    @Operation(summary = "시군구 목록", description = "수집 대상 시군구. 검색·필터에 쓴다")
    @GetMapping
    public List<RegionItem> list() {
        return service.listRegions();
    }

    @Operation(summary = "시군구 월별 거래량",
            description = "계약월별 거래 수. " + BULK_EXCLUDED + " 일괄 거래 수는 bulkCount로 따로 준다. " + BULK
                    + " 거래가 없는 달은 빠진다. provisional: " + PROVISIONAL
                    + " 기간: from을 생략하면 to를 포함한 최근 36개월, 최대 120개월 (넘으면 400)")
    @GetMapping("/{sggCd}/monthly")
    public MonthlySeries<MonthlyVolume> monthlyVolumes(
            @Parameter(description = "시군구 코드 (5자리)", example = "11710") @PathVariable String sggCd,
            @Parameter(description = "시작 월 (포함, yyyy-MM). 생략하면 to를 포함한 최근 36개월", example = "2023-11")
            @RequestParam(required = false) @DateTimeFormat(pattern = YEAR_MONTH) YearMonth from,
            @Parameter(description = "끝 월 (포함, yyyy-MM). 생략하면 기준일이 속한 달", example = "2026-10")
            @RequestParam(required = false) @DateTimeFormat(pattern = YEAR_MONTH) YearMonth to) {
        return service.getMonthlyVolumes(sggCd, from, to);
    }
}
