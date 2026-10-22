package com.dreamapt.collect;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminCollectController.class)
class AdminCollectControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TradeCollectService collectService;

    @Test
    void 수집_결과를_반환한다() throws Exception {
        given(collectService.collect("11500", "202609"))
                .willReturn(new CollectResult("11500", "202609", 252, 120, 230, 10, 2, 10));

        mockMvc.perform(post("/api/admin/collect").param("sggCd", "11500").param("dealYm", "202609"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fetched").value(252))
                .andExpect(jsonPath("$.inserted").value(230))
                .andExpect(jsonPath("$.updated").value(10))
                .andExpect(jsonPath("$.unchanged").value(2))
                .andExpect(jsonPath("$.merged").value(10));
    }

    @Test
    void 파라미터_형식이_틀리면_400() throws Exception {
        mockMvc.perform(post("/api/admin/collect").param("sggCd", "115").param("dealYm", "202609"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/collect").param("sggCd", "11500").param("dealYm", "202613"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 외부_API_오류는_502() throws Exception {
        given(collectService.collect("11500", "202609")).willThrow(new TradeApiException("API 오류 응답"));

        mockMvc.perform(post("/api/admin/collect").param("sggCd", "11500").param("dealYm", "202609"))
                .andExpect(status().isBadGateway());
    }
}
