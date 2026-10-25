package com.dreamapt.collect.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminCollectJobController.class)
class AdminCollectJobControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CollectJobLauncher launcher;

    @MockitoBean
    RunningCollectJobs runningJobs;

    @MockitoBean
    JobRepository jobRepository;

    @MockitoBean
    JobOperator jobOperator;

    @Test
    void 비동기로_시작하고_202와_실행_id를_돌려준다() throws Exception {
        given(runningJobs.findRunning(CollectMode.DAILY, null)).willReturn(List.of());
        given(launcher.startAsync(CollectMode.DAILY, LocalDate.of(2026, 10, 25)))
                .willReturn(execution(11L, "daily", BatchStatus.STARTING));

        mockMvc.perform(post("/api/admin/collect/jobs").param("mode", "daily").param("baseDate", "2026-10-25"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobExecutionId").value(11))
                .andExpect(jsonPath("$.mode").value("daily"))
                .andExpect(jsonPath("$.status").value("STARTING"));
    }

    @Test
    void 같은_모드로_실행_중이면_409() throws Exception {
        given(runningJobs.findRunning(CollectMode.BACKFILL, null))
                .willReturn(List.of(execution(5L, "backfill", BatchStatus.STARTED)));

        mockMvc.perform(post("/api/admin/collect/jobs").param("mode", "backfill"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("같은 모드(backfill)로 실행 중: jobExecutionId=5"));

        verify(launcher, never()).startAsync(any(), any());
    }

    @Test
    void 모드가_틀리면_400() throws Exception {
        mockMvc.perform(post("/api/admin/collect/jobs").param("mode", "monthly"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 실행_상태와_요약을_조회한다() throws Exception {
        JobExecution execution = execution(11L, "daily", BatchStatus.COMPLETED);
        StepExecution step = MetaDataInstanceFactory.createStepExecution(execution, "collectTargetsStep", 1L);
        step.getExecutionContext().putInt("summary.failed", 2);
        execution.addStepExecution(step);
        given(jobRepository.getJobExecution(11L)).willReturn(execution);

        mockMvc.perform(get("/api/admin/collect/jobs/11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.summary.failed").value(2));
    }

    @Test
    void 없는_실행은_404() throws Exception {
        given(jobRepository.getJobExecution(99L)).willThrow(new EmptyResultDataAccessException(1));

        mockMvc.perform(get("/api/admin/collect/jobs/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 오래된_기록만_recover_한다() throws Exception {
        JobExecution stale = execution(5L, "daily", BatchStatus.STARTED);
        JobExecution recovered = execution(5L, "daily", BatchStatus.FAILED);
        given(jobRepository.getJobExecution(5L)).willReturn(stale);
        given(runningJobs.isStale(stale)).willReturn(true);
        given(jobOperator.recover(stale)).willReturn(recovered);

        mockMvc.perform(post("/api/admin/collect/jobs/5/recover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void 아직_오래되지_않은_실행은_recover_하지_않고_409() throws Exception {
        JobExecution running = execution(5L, "daily", BatchStatus.STARTED);
        given(jobRepository.getJobExecution(5L)).willReturn(running);
        given(runningJobs.isStale(running)).willReturn(false);

        mockMvc.perform(post("/api/admin/collect/jobs/5/recover"))
                .andExpect(status().isConflict());

        verify(jobOperator, never()).recover(any());
    }

    private static JobExecution execution(long id, String mode, BatchStatus status) {
        JobExecution execution = MetaDataInstanceFactory.createJobExecution(RunningCollectJobs.JOB_NAME, 1L, id,
                CollectJobParameters.of(CollectMode.from(mode), LocalDate.of(2026, 10, 25), 1L));
        execution.setStatus(status);
        return execution;
    }
}
