package com.dreamapt.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.springdoc.core.models.GroupedOpenApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.dreamapt.support.TestcontainersConfiguration;

/** API 문서 그룹: public은 항상, admin은 설정이 켜졌을 때만. 아무 설정이 없으면 안전한 쪽(admin 없음)이다. */
class ApiDocsIntegrationTest {

    /**
     * jar 실행(k8s)과 같은 조건: 활성 프로필 없음, spring.profiles.default 없음(bootRun에서만 local),
     * API_DOCS_ADMIN_ENABLED 없음. 테스트 JVM에는 bootRun의 systemProperty가 들어오지 않는다.
     */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(TestcontainersConfiguration.class)
    class 프로필과_설정이_없으면 {

        @Autowired
        MockMvc mockMvc;

        @Autowired
        Environment environment;

        @Autowired
        List<GroupedOpenApi> groups;

        @Test
        void jar_실행과_같은_조건이다() {
            assertThat(System.getProperty("spring.profiles.default")).isNull();
            assertThat(System.getenv("API_DOCS_ADMIN_ENABLED")).isNull();
            assertThat(environment.getActiveProfiles()).isEmpty();
            assertThat(environment.getDefaultProfiles()).containsExactly("default");
        }

        @Test
        void 관리_API_문서는_꺼져_있고_public_그룹만_있다() {
            assertThat(environment.getProperty("dreamapt.docs.admin-enabled", Boolean.class)).isFalse();
            assertThat(groups).extracting(GroupedOpenApi::getGroup).containsExactly("public");
        }

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
