package com.dreamapt.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import com.dreamapt.collect.CollectResult;
import com.dreamapt.collect.ReconcileResult;
import com.dreamapt.collect.TradeApiException;
import com.dreamapt.collect.batch.CollectJobLauncher;
import com.dreamapt.collect.batch.CollectJobParameters;
import com.dreamapt.collect.batch.CollectMode;
import com.dreamapt.collect.batch.CollectTarget;
import com.dreamapt.collect.batch.CollectTargetRunner;
import com.dreamapt.collect.batch.RunningCollectJobs;
import com.dreamapt.support.TestcontainersConfiguration;

/**
 * 기존 API 응답 형식 고정 (springdoc 추가 전에 캡처한 값).
 * springdoc은 swagger-core를 통해 Jackson 2를 classpath에 올린다. 응답 JSON은 계속 Jackson 3이 만들어야 하고
 * 필드 순서·날짜 형식·null 처리가 바뀌면 안 된다. 전체 컨텍스트로 띄워서 springdoc 자동 설정의 영향까지 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiJsonCompatibilityTest {

    private static final CollectTarget TARGET = new CollectTarget("11500", YearMonth.of(2026, 9));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ApplicationContext context;

    @MockitoBean
    CollectTargetRunner runner;

    @MockitoBean
    CollectJobLauncher launcher;

    @MockitoBean
    RunningCollectJobs runningJobs;

    @Test
    void 응답_JSON은_Jackson_3_변환기가_만든다() {
        List<String> converters = context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters().stream()
                .map(c -> c.getClass().getName())
                .toList();

        assertThat(converters)
                .contains("org.springframework.http.converter.json.JacksonJsonHttpMessageConverter")
                .doesNotContain("org.springframework.http.converter.json.MappingJackson2HttpMessageConverter");
        // Jackson 2 ObjectMapper가 classpath에 있어도(springdoc) 빈으로 등록되어 응답에 쓰이면 안 된다
        String jackson2 = "com.fasterxml.jackson.databind.ObjectMapper";
        if (ClassUtils.isPresent(jackson2, null)) {
            assertThat(context.getBeanNamesForType(ClassUtils.resolveClassName(jackson2, null), true, false)).isEmpty();
        }
    }

    @Test
    void 문자열_응답() throws Exception {
        assertResponse(get("/api/ping"), 200, "text/plain;charset=UTF-8", "pong");
    }

    @Test
    void 수집_결과() throws Exception {
        given(runner.collect(TARGET, null)).willReturn(new CollectResult("11500", "202609", 252, 120, 230, 10, 12, 7,
                List.of(ReconcileResult.MISSING_RATIO_EXCEEDED)));

        assertResponse(post("/api/admin/collect").param("sggCd", "11500").param("dealYm", "202609"),
                200, "application/json", """
                        {"sggCd":"11500","dealYm":"202609","fetched":252,"complexes":120,"inserted":230,"updated":10,\
                        "unchanged":12,"missing":7,"warnings":["MISSING_RATIO_EXCEEDED"]}""");
    }

    @Test
    void 파라미터_검증_실패는_본문_없는_400() throws Exception {
        assertResponse(post("/api/admin/collect").param("sggCd", "115").param("dealYm", "202609"), 400, null, "");
    }

    @Test
    void 외부_API_오류는_ProblemDetail_502() throws Exception {
        given(runner.collect(TARGET, null)).willThrow(new TradeApiException("API 오류 응답"));

        assertResponse(post("/api/admin/collect").param("sggCd", "11500").param("dealYm", "202609"),
                502, "application/problem+json", """
                        {"detail":"API 오류 응답","instance":"/api/admin/collect","status":502,"title":"Bad Gateway"}""");
    }

    @Test
    void Job_시작_응답의_날짜_시각_null_Map_형식() throws Exception {
        given(runningJobs.findRunning(CollectMode.DAILY, null)).willReturn(List.of());
        given(launcher.startAsync(CollectMode.DAILY, LocalDate.of(2026, 10, 25))).willReturn(execution(11L));

        assertResponse(post("/api/admin/collect/jobs").param("mode", "daily").param("baseDate", "2026-10-25"),
                202, "application/json", """
                        {"jobExecutionId":11,"mode":"daily","baseDate":"2026-10-25","status":"STARTED",\
                        "exitCode":"UNKNOWN","exitDescription":"","startTime":"2026-10-25T06:00:01.123",\
                        "endTime":null,"summary":{"calls":150,"failed":2}}""");
    }

    @Test
    void 실행_중이면_ProblemDetail_409() throws Exception {
        given(runningJobs.findRunning(CollectMode.WEEKLY, null)).willReturn(List.of(execution(5L)));

        assertResponse(post("/api/admin/collect/jobs").param("mode", "weekly"), 409, "application/problem+json", """
                {"detail":"같은 모드(weekly)로 실행 중: jobExecutionId=5","instance":"/api/admin/collect/jobs",\
                "status":409,"title":"Conflict"}""");
    }

    @Test
    void 없는_실행은_ProblemDetail_404() throws Exception {
        assertResponse(get("/api/admin/collect/jobs/999999"), 404, "application/problem+json", """
                {"detail":"collectTradeJob 실행이 없음: jobExecutionId=999999",\
                "instance":"/api/admin/collect/jobs/999999","status":404,"title":"Not Found"}""");
    }

    /** 상태 코드, Content-Type, 본문 문자열(필드 순서 포함)이 모두 같아야 한다. */
    private void assertResponse(RequestBuilder request, int status, String contentType, String body)
            throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).isEqualTo(contentType);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(body);
    }

    private static JobExecution execution(long id) {
        JobExecution execution = MetaDataInstanceFactory.createJobExecution(RunningCollectJobs.JOB_NAME, 1L, id,
                CollectJobParameters.of(CollectMode.DAILY, LocalDate.of(2026, 10, 25), 1L));
        execution.setStatus(BatchStatus.STARTED);
        execution.setStartTime(LocalDateTime.of(2026, 10, 25, 6, 0, 1, 123_000_000));
        StepExecution step = MetaDataInstanceFactory.createStepExecution(execution, "collectTargetsStep", 1L);
        step.getExecutionContext().putInt("summary.failed", 2);
        step.getExecutionContext().putLong("summary.calls", 150L);
        execution.addStepExecution(step);
        return execution;
    }
}
