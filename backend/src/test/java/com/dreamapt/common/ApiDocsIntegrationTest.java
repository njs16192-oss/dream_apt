package com.dreamapt.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.dreamapt.support.TestcontainersConfiguration;

/** API 문서 그룹: public은 항상, admin은 설정이 켜졌을 때만. */
class ApiDocsIntegrationTest {

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(TestcontainersConfiguration.class)
    class 기본_설정 {

        @Autowired
        MockMvc mockMvc;

        @Test
        void public_그룹에는_조회_API만_있고_관리_API는_없다() throws Exception {
            String doc = body("/v3/api-docs/public");

            assertThat(doc).contains("\"/api/ping\"").doesNotContain("/api/admin");
        }

        @Test
        void admin_그룹은_만들지_않는다() throws Exception {
            mockMvc.perform(get("/v3/api-docs/admin")).andExpect(status().isNotFound());
            mockMvc.perform(get("/v3/api-docs/swagger-config"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.urls[*].name").value(org.hamcrest.Matchers.contains("조회 API")));
        }

        @Test
        void 그룹_없는_기본_문서에도_관리_API_경로와_응답_스키마가_없다() throws Exception {
            for (String path : new String[] { "/v3/api-docs", "/v3/api-docs/public" }) {
                assertThat(body(path)).contains("\"/api/ping\"")
                        .doesNotContain("/api/admin", "CollectResult", "CollectJobResponse");
            }
        }

        @Test
        void Swagger_UI가_열린다() throws Exception {
            mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        }

        private String body(String path) throws Exception {
            return mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
        }
    }

    @Nested
    @SpringBootTest(properties = "dreamapt.docs.admin-enabled=true")
    @AutoConfigureMockMvc
    @Import(TestcontainersConfiguration.class)
    class 관리_API_문서를_켜면 {

        @Autowired
        MockMvc mockMvc;

        @Test
        void admin_그룹에_관리_API가_있다() throws Exception {
            mockMvc.perform(get("/v3/api-docs/admin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/api/admin/collect']").exists())
                    .andExpect(jsonPath("$.paths['/api/admin/collect/jobs']").exists())
                    .andExpect(jsonPath("$.paths['/api/ping']").doesNotExist());
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("local")
    @AutoConfigureMockMvc
    @Import(TestcontainersConfiguration.class)
    class local_프로필이면 {

        @Autowired
        MockMvc mockMvc;

        @Test
        void admin_그룹이_있다() throws Exception {
            mockMvc.perform(get("/v3/api-docs/admin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/api/admin/collect']").exists());
        }
    }

    @Nested
    @SpringBootTest(properties = "springdoc.swagger-ui.enabled=false")
    @AutoConfigureMockMvc
    @Import(TestcontainersConfiguration.class)
    class Swagger_UI를_끄면 {

        @Autowired
        MockMvc mockMvc;

        @Test
        void Swagger_UI가_없다() throws Exception {
            mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        }
    }
}
