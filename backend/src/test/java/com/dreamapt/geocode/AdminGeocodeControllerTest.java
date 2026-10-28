package com.dreamapt.geocode;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dreamapt.geocode.JusoApiException.Kind;

@WebMvcTest(AdminGeocodeController.class)
class AdminGeocodeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ComplexGeocodeService service;

    @Test
    void 결과_요약을_반환한다() throws Exception {
        given(service.geocode(1000, false)).willReturn(new GeocodeResult(1000, 990, 4, 3, 1, 1, 1, 7591));

        mockMvc.perform(post("/api/admin/geocode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempted").value(1000))
                .andExpect(jsonPath("$.succeeded").value(990))
                .andExpect(jsonPath("$.mismatch").value(3))
                .andExpect(jsonPath("$.remaining").value(7591));
    }

    @Test
    void 파라미터를_넘긴다() throws Exception {
        given(service.geocode(50, true)).willReturn(new GeocodeResult(0, 0, 0, 0, 0, 0, 0, 0));

        mockMvc.perform(post("/api/admin/geocode").param("limit", "50").param("retryFailed", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void limit이_범위를_벗어나면_400() throws Exception {
        mockMvc.perform(post("/api/admin/geocode").param("limit", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/geocode").param("limit", "2001")).andExpect(status().isBadRequest());
    }

    @Test
    void 키_오류는_502() throws Exception {
        given(service.geocode(1000, false)).willThrow(new JusoApiException(Kind.AUTH, "키가 없습니다"));

        mockMvc.perform(post("/api/admin/geocode"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("키가 없습니다"));
    }
}
