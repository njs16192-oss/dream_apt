package com.dreamapt.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.dreamapt.common.QueryApiExceptionHandler;

@WebMvcTest({ RegionController.class, QueryApiExceptionHandler.class })
class RegionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RegionQueryService service;

    @Test
    void 시군구_목록_응답_형식() throws Exception {
        given(service.listRegions()).willReturn(List.of(new RegionItem("11110", "서울특별시", "종로구")));

        mockMvc.perform(get("/api/regions"))
                .andExpect(status().isOk())
                .andExpect(json("""
                        [{"sggCd":"11110","sidoName":"서울특별시","sggName":"종로구"}]"""));
    }

    @Test
    void 월별_거래량_응답_형식() throws Exception {
        given(service.getMonthlyVolumes("11710", null, null)).willReturn(new MonthlySeries<>(
                LocalDate.of(2026, 10, 25), YearMonth.of(2026, 9), YearMonth.of(2023, 11), YearMonth.of(2026, 10),
                List.of(new MonthlyVolume(YearMonth.of(2026, 9), 314, 170, true))));

        mockMvc.perform(get("/api/regions/11710/monthly"))
                .andExpect(status().isOk())
                .andExpect(json("""
                        {"baseDate":"2026-10-25","provisionalFrom":"2026-09","from":"2023-11","to":"2026-10",\
                        "months":[{"month":"2026-09","count":314,"bulkCount":170,"provisional":true}]}"""));
    }

    @Test
    void 기간이_너무_길면_ProblemDetail_400() throws Exception {
        given(service.getMonthlyVolumes("11710", YearMonth.of(2016, 10), YearMonth.of(2026, 10)))
                .willThrow(new BadRequestException("기간은 최대 120개월입니다 (요청: 121개월)"));

        mockMvc.perform(get("/api/regions/11710/monthly").param("from", "2016-10").param("to", "2026-10"))
                .andExpect(status().isBadRequest())
                .andExpect(json("""
                        {"detail":"기간은 최대 120개월입니다 (요청: 121개월)","instance":"/api/regions/11710/monthly",\
                        "status":400,"title":"Bad Request"}"""));
    }

    @Test
    void 없는_구면_ProblemDetail_404() throws Exception {
        given(service.getMonthlyVolumes("99999", null, null)).willThrow(new NotFoundException("구가 없습니다: 99999"));

        mockMvc.perform(get("/api/regions/99999/monthly"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("구가 없습니다: 99999"));
    }

    private static ResultMatcher json(String expected) {
        return result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(expected);
    }
}
