package com.dreamapt.complex;

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
import com.dreamapt.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** 단지 조회 API (ADR 007). */
@Tag(name = "단지", description = "단지 검색, 단지 상세, 면적 그룹별 월별 가격, 거래 목록")
@RestController
@RequestMapping("/api/complexes")
@RequiredArgsConstructor
public class ComplexController {

    private final ComplexQueryService service;

    @Operation(summary = "단지 검색",
            description = "단지명 부분 일치 또는 유사도(띄어쓰기·일부 오타 허용)로 찾는다. 1글자부터 검색할 수 있다. "
                    + "정렬: 이름 유사도 → 최근 12개월 거래 수(" + BULK_EXCLUDED + ")")
    @GetMapping
    public List<ComplexSearchItem> search(
            @Parameter(description = "검색어 (1–50자, 앞뒤 공백 무시)", example = "헬리오")
            @RequestParam @NotBlank @Size(max = 50) String q,
            @Parameter(description = "시군구 코드 (5자리)로 거르기", example = "11710")
            @RequestParam(required = false) @Pattern(regexp = "\\d{5}", message = "sggCd는 5자리 숫자") String sggCd,
            @Parameter(description = "최대 건수 (1–50)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return service.search(q, sggCd, limit);
    }

    @Operation(summary = "단지 상세",
            description = "단지 정보와 면적 그룹별 요약. 요약값(거래 수, 마지막 거래)은 " + BULK_EXCLUDED
                    + " 일괄 거래 수와 비율은 따로 준다. " + BULK)
    @GetMapping("/{aptSeq}")
    public ComplexDetail detail(@Parameter(description = "단지 ID", example = "11710-8865") @PathVariable String aptSeq) {
        return service.getDetail(aptSeq);
    }

    @Operation(summary = "면적 그룹 월별 가격",
            description = "계약월별 중앙값과 거래 수. " + BULK_EXCLUDED + " 거래가 없는 달은 빠진다. "
                    + "provisional: " + PROVISIONAL + " 기간은 제한하지 않는다 (from 생략 시 처음부터)")
    @GetMapping("/{aptSeq}/areas/{areaGroup}/monthly")
    public MonthlySeries<MonthlyPrice> monthlyPrices(
            @Parameter(description = "단지 ID", example = "11710-8865") @PathVariable String aptSeq,
            @Parameter(description = "면적 그룹 (전용면적 정수 반올림, ㎡)", example = "85") @PathVariable int areaGroup,
            @Parameter(description = "시작 월 (포함, yyyy-MM). 생략하면 처음부터", example = "2024-01")
            @RequestParam(required = false) @DateTimeFormat(pattern = YEAR_MONTH) YearMonth from,
            @Parameter(description = "끝 월 (포함, yyyy-MM). 생략하면 기준일이 속한 달", example = "2026-10")
            @RequestParam(required = false) @DateTimeFormat(pattern = YEAR_MONTH) YearMonth to) {
        return service.getMonthlyPrices(aptSeq, areaGroup, from, to);
    }

    @Operation(summary = "거래 목록",
            description = "최신순(계약일 내림차순, 같은 날이면 늦게 수집된 순). 일괄 거래도 포함하고 bulk로 표시한다. "
                    + "단지에 없는 면적 그룹으로 거르면 빈 목록이다")
    @GetMapping("/{aptSeq}/trades")
    public PageResponse<TradeItem> trades(
            @Parameter(description = "단지 ID", example = "11710-8865") @PathVariable String aptSeq,
            @Parameter(description = "면적 그룹으로 거르기 (전용면적 정수 반올림, ㎡)", example = "85")
            @RequestParam(required = false) Integer areaGroup,
            @Parameter(description = "페이지 번호 (0부터)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기 (1–100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.getTrades(aptSeq, areaGroup, page, size);
    }
}
