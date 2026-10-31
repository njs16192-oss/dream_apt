package com.dreamapt.complex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import com.dreamapt.common.BadRequestException;
import com.dreamapt.common.MonthlySeries;
import com.dreamapt.common.NotFoundException;
import com.dreamapt.common.PageResponse;
import com.dreamapt.common.QueryApiExceptionHandler;

@WebMvcTest({ ComplexController.class, QueryApiExceptionHandler.class })
class ComplexControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ComplexQueryService service;

    @Test
    void 검색_응답_형식과_기본_개수() throws Exception {
        given(service.search("헬리오", null, 20)).willReturn(List.of(
                new ComplexSearchItem("11710-8865", "헬리오시티", "11710", "송파구", "가락동", 2018, 152),
                new ComplexSearchItem("11500-1", "헬리오", "11500", "강서구", null, null, 0)));

        mockMvc.perform(get("/api/complexes").param("q", "헬리오"))
                .andExpect(status().isOk())
                .andExpect(json("""
                        [{"aptSeq":"11710-8865","name":"헬리오시티","sggCd":"11710","sggName":"송파구",\
                        "umdName":"가락동","buildYear":2018,"recentTradeCount":152},\
                        {"aptSeq":"11500-1","name":"헬리오","sggCd":"11500","sggName":"강서구",\
                        "umdName":null,"buildYear":null,"recentTradeCount":0}]"""));
    }

    @Test
    void 검색은_한_글자부터_구와_개수를_받는다() throws Exception {
        given(service.search("현", "11710", 50)).willReturn(List.of());

        mockMvc.perform(get("/api/complexes").param("q", "현").param("sggCd", "11710").param("limit", "50"))
                .andExpect(status().isOk());

        verify(service).search("현", "11710", 50);
    }

    @Test
    void 검색_파라미터가_잘못되면_ProblemDetail_400() throws Exception {
        for (String[] params : new String[][] {
                {}, { "q", " " }, { "q", "가".repeat(51) }, { "q", "현대", "limit", "0" },
                { "q", "현대", "limit", "51" }, { "q", "현대", "sggCd", "117" } }) {
            var request = get("/api/complexes");
            for (int i = 0; i < params.length; i += 2) {
                request.param(params[i], params[i + 1]);
            }
            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType("application/problem+json"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void 단지_상세_응답_형식() throws Exception {
        given(service.getDetail("11710-8865")).willReturn(new ComplexDetail("11710-8865", "헬리오시티", "11710", "송파구",
                "가락동", 2018, "송파대로 345", 37.499077, 127.112296, 1, 10, 10.0 / 11, true, List.of(
                        new AreaGroupSummary(39, List.of(new BigDecimal("39.12")), 0, 10, null, null),
                        new AreaGroupSummary(85, List.of(new BigDecimal("84.90"), new BigDecimal("84.99")), 1, 0,
                                LocalDate.of(2026, 8, 1), 245_000))));

        mockMvc.perform(get("/api/complexes/11710-8865"))
                .andExpect(status().isOk())
                .andExpect(json("""
                        {"aptSeq":"11710-8865","name":"헬리오시티","sggCd":"11710","sggName":"송파구",\
                        "umdName":"가락동","buildYear":2018,"roadAddress":"송파대로 345",\
                        "lat":37.499077,"lng":127.112296,"tradeCount":1,\
                        "bulkCount":10,"bulkRatio":0.9090909090909091,"bulkDominant":true,"areaGroups":[\
                        {"areaGroup":39,"exclusiveAreas":[39.12],"tradeCount":0,"bulkCount":10,\
                        "lastDealDate":null,"lastDealAmount":null},\
                        {"areaGroup":85,"exclusiveAreas":[84.90,84.99],"tradeCount":1,"bulkCount":0,\
                        "lastDealDate":"2026-08-01","lastDealAmount":245000}]}"""));
    }

    @Test
    void 반경_검색_응답_형식과_기본값() throws Exception {
        given(service.nearby(37.499077, 127.112296, 1000, 300)).willReturn(new NearbyComplexes(37.499077, 127.112296,
                1000, 51, List.of(new NearbyComplex("11710-8865", "헬리오시티", "송파구", "가락동", 2018, 37.499077,
                        127.112296, 0, 152))));

        mockMvc.perform(get("/api/complexes/nearby").param("lat", "37.499077").param("lng", "127.112296"))
                .andExpect(status().isOk())
                .andExpect(json("""
                        {"lat":37.499077,"lng":127.112296,"radius":1000,"totalCount":51,"items":[\
                        {"aptSeq":"11710-8865","name":"헬리오시티","sggName":"송파구","umdName":"가락동",\
                        "buildYear":2018,"lat":37.499077,"lng":127.112296,"distanceM":0,"recentTradeCount":152}]}"""));
        // "/nearby"가 단지 상세(/{aptSeq})로 잡히지 않는다
        verify(service, never()).getDetail(any());
    }

    @Test
    void 반경_검색은_반경과_개수를_받는다() throws Exception {
        given(service.nearby(37.5, 127.0, 3000, 500)).willReturn(new NearbyComplexes(37.5, 127.0, 3000, 0, List.of()));

        mockMvc.perform(get("/api/complexes/nearby").param("lat", "37.5").param("lng", "127.0")
                .param("radius", "3000").param("limit", "500"))
                .andExpect(status().isOk());

        verify(service).nearby(37.5, 127.0, 3000, 500);
    }

    @Test
    void 반경_검색_파라미터가_잘못되면_ProblemDetail_400() throws Exception {
        for (String[] params : new String[][] {
                { "lng", "127" }, { "lat", "37.5" },
                { "lat", "32.9", "lng", "127" }, { "lat", "39.1", "lng", "127" },
                { "lat", "37.5", "lng", "123.9" }, { "lat", "37.5", "lng", "132.1" },
                { "lat", "37.5", "lng", "127", "radius", "99" }, { "lat", "37.5", "lng", "127", "radius", "3001" },
                { "lat", "37.5", "lng", "127", "limit", "0" }, { "lat", "37.5", "lng", "127", "limit", "501" },
                { "lat", "abc", "lng", "127" } }) {
            var request = get("/api/complexes/nearby");
            for (int i = 0; i < params.length; i += 2) {
                request.param(params[i], params[i + 1]);
            }
            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType("application/problem+json"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void 없는_단지면_ProblemDetail_404() throws Exception {
        given(service.getDetail("99999-1")).willThrow(new NotFoundException("단지가 없습니다: 99999-1"));

        mockMvc.perform(get("/api/complexes/99999-1"))
                .andExpect(status().isNotFound())
                .andExpect(json("""
                        {"detail":"단지가 없습니다: 99999-1","instance":"/api/complexes/99999-1",\
                        "status":404,"title":"Not Found"}"""));
    }

    @Test
    void 월별_가격_응답_형식() throws Exception {
        given(service.getMonthlyPrices("11710-8865", 85, YearMonth.of(2026, 8), YearMonth.of(2026, 9)))
                .willReturn(new MonthlySeries<>(LocalDate.of(2026, 10, 25), YearMonth.of(2026, 9),
                        YearMonth.of(2026, 8), YearMonth.of(2026, 9), List.of(
                                new MonthlyPrice(YearMonth.of(2026, 8), 100_003, 2, false),
                                new MonthlyPrice(YearMonth.of(2026, 9), 245_000, 1, true))));

        mockMvc.perform(get("/api/complexes/11710-8865/areas/85/monthly").param("from", "2026-08")
                        .param("to", "2026-09"))
                .andExpect(status().isOk())
                .andExpect(json("""
                        {"baseDate":"2026-10-25","provisionalFrom":"2026-09","from":"2026-08","to":"2026-09",\
                        "months":[{"month":"2026-08","medianAmount":100003,"count":2,"provisional":false},\
                        {"month":"2026-09","medianAmount":245000,"count":1,"provisional":true}]}"""));
    }

    @Test
    void 월별_가격은_from_to를_생략할_수_있다() throws Exception {
        given(service.getMonthlyPrices("11710-8865", 85, null, null)).willReturn(new MonthlySeries<>(
                LocalDate.of(2026, 10, 25), YearMonth.of(2026, 9), null, YearMonth.of(2026, 10), List.of()));

        mockMvc.perform(get("/api/complexes/11710-8865/areas/85/monthly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").isEmpty())
                .andExpect(jsonPath("$.to").value("2026-10"));
    }

    @Test
    void 월_형식이_틀리거나_서비스가_거부하면_ProblemDetail_400() throws Exception {
        mockMvc.perform(get("/api/complexes/11710-8865/areas/85/monthly").param("from", "2026-13"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"));
        mockMvc.perform(get("/api/complexes/11710-8865/areas/eighty/monthly"))
                .andExpect(status().isBadRequest());

        given(service.getMonthlyPrices("11710-8865", 85, YearMonth.of(2026, 9), YearMonth.of(2026, 8)))
                .willThrow(new BadRequestException("from(2026-09)이 to(2026-08)보다 늦습니다"));
        mockMvc.perform(get("/api/complexes/11710-8865/areas/85/monthly").param("from", "2026-09")
                        .param("to", "2026-08"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("from(2026-09)이 to(2026-08)보다 늦습니다"));
    }

    @Test
    void 거래_목록_응답_형식과_기본_페이지() throws Exception {
        given(service.getTrades("11710-8865", null, 0, 20)).willReturn(PageResponse.of(List.of(
                new TradeItem(7L, LocalDate.of(2026, 9, 18), 70_000, new BigDecimal("84.90"), 85, 12, null, "직거래",
                        "공공기관", "법인", null, true)), 0, 20, 1));

        mockMvc.perform(get("/api/complexes/11710-8865/trades"))
                .andExpect(status().isOk())
                .andExpect(json("""
                        {"content":[{"id":7,"dealDate":"2026-09-18","dealAmount":70000,"exclusiveArea":84.90,\
                        "areaGroup":85,"floor":12,"aptDong":null,"dealingGbn":"직거래","buyerGbn":"공공기관",\
                        "sellerGbn":"법인","registeredDate":null,"bulk":true}],\
                        "page":0,"size":20,"totalElements":1,"totalPages":1}"""));
    }

    @Test
    void 거래_목록_페이지_파라미터가_잘못되면_400() throws Exception {
        mockMvc.perform(get("/api/complexes/11710-8865/trades").param("page", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/complexes/11710-8865/trades").param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/complexes/11710-8865/trades").param("size", "0")).andExpect(status().isBadRequest());

        verify(service, never()).getTrades(any(), any(), anyInt(), anyInt());
    }

    private static ResultMatcher json(String expected) {
        return result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(expected);
    }
}
