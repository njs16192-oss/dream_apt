package com.dreamapt.common;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;

/**
 * API 문서 (springdoc). Swagger UI: /swagger-ui.html
 * <ul>
 * <li>public: 조회 API. 운영에서도 공개한다 (포트폴리오에서 배포 주소로 직접 호출해 볼 수 있게)</li>
 * <li>admin: 관리 API. dreamapt.docs.admin-enabled = true일 때만 만든다 (로컬 프로필)</li>
 * </ul>
 * 문서에서 숨기는 것은 보안이 아니다. 관리 API 엔드포인트 자체는 인증(5단계)이나 배포 시 외부 노출 차단으로 보호한다.
 */
@Configuration(proxyBeanMethods = false)
public class ApiDocsConfig {

    static final String ADMIN_PREFIX = "/api/admin/";
    static final String ADMIN_PATHS = ADMIN_PREFIX + "**";

    private static final Pattern SCHEMA_REF = Pattern.compile("\"#/components/schemas/([^\"]+)\"");

    @Bean
    OpenAPI dreamAptOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Dream APT API")
                .version("v1")
                .description("""
                        서울 아파트 실거래가 조회 API. 출처: 국토교통부 아파트 매매 실거래가 상세 자료.

                        - 금액은 모두 **만원** 단위 정수다
                        - 거래는 유효 거래(해제되지 않았고 원본에서 사라지지 않은 거래)만 다룬다
                        - **일괄 거래**: 같은 단지·같은 계약일 유효 거래가 10건 이상인 거래. \
                        요약값·정렬·통계(중앙값, 거래 수, 최근 거래가, 검색 정렬)에서는 빼고, \
                        거래 목록에는 포함해 `bulk`로 표시한다
                        - **provisional(집계 중)**: 기준일(서울 날짜)이 속한 달과 그 전 달. \
                        신고(계약 후 30일 안)·해제가 더 반영되어 값이 바뀔 수 있다
                        - 면적 그룹(`areaGroup`)은 전용면적(㎡)을 정수로 반올림한 값이다
                        """));
    }

    @Bean
    GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public")
                .displayName("조회 API")
                .pathsToMatch("/api/**")
                .pathsToExclude(ADMIN_PATHS)
                .build();
    }

    @Bean
    @ConditionalOnBooleanProperty("dreamapt.docs.admin-enabled")
    GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .displayName("관리 API (로컬 전용)")
                .pathsToMatch(ADMIN_PATHS)
                .build();
    }

    /**
     * 그룹 없는 기본 문서(/v3/api-docs)는 그룹과 별개로 모든 경로를 담는다. admin 그룹이 없을 때는 관리 API 경로와
     * 그 경로만 쓰던 스키마를 모든 문서에서 뺀다. 전역 springdoc.paths-to-exclude는 admin 그룹에도 적용되어 쓸 수 없다.
     */
    @Bean
    @ConditionalOnBooleanProperty(name = "dreamapt.docs.admin-enabled", havingValue = false, matchIfMissing = true)
    GlobalOpenApiCustomizer hideAdminPaths() {
        return openApi -> {
            if (openApi.getPaths() != null) {
                openApi.getPaths().keySet().removeIf(path -> path.startsWith(ADMIN_PREFIX));
            }
            removeUnreferencedSchemas(openApi);
        };
    }

    /** 남은 경로에서 $ref로 (스키마끼리 이어지는 것까지) 닿지 않는 스키마를 지운다. */
    static void removeUnreferencedSchemas(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null || components.getSchemas() == null) {
            return;
        }
        Map<String, Schema> schemas = components.getSchemas();
        Set<String> used = new HashSet<>(schemaRefs(openApi.getPaths()));
        Deque<String> pending = new ArrayDeque<>(used);
        while (!pending.isEmpty()) {
            Schema<?> schema = schemas.get(pending.pop());
            if (schema != null) {
                schemaRefs(schema).stream().filter(used::add).forEach(pending::push);
            }
        }
        schemas.keySet().retainAll(used);
    }

    private static Set<String> schemaRefs(Object node) {
        Set<String> refs = new HashSet<>();
        if (node == null) {
            return refs;
        }
        try {
            // OpenAPI 모델은 swagger-core 자체 직렬화기(Jackson 2)로 JSON을 만든다. 응답 JSON과는 무관하다
            Matcher m = SCHEMA_REF.matcher(Json.mapper().writeValueAsString(node));
            while (m.find()) {
                refs.add(m.group(1));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OpenAPI 문서 직렬화 실패", e);
        }
        return refs;
    }
}
