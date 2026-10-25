package com.dreamapt.collect.batch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 수집 Job 관리 API (ADR 006 3-⑤, 3-⑦). 인증이 없으므로 외부 배포 전에 보호 장치가 필요하다.
 */
@RestController
@RequestMapping("/api/admin/collect/jobs")
@RequiredArgsConstructor
public class AdminCollectJobController {

    private final CollectJobLauncher launcher;
    private final RunningCollectJobs runningJobs;
    private final JobRepository jobRepository;
    private final JobOperator jobOperator;

    /**
     * 비동기로 시작하고 202를 돌려준다. 같은 모드로 실행 중이면 409.
     * 확인과 시작 사이에 다른 실행이 끼어들면 Job 시작 리스너가 막는다 (STOPPED/ALREADY_RUNNING).
     */
    @PostMapping
    public ResponseEntity<CollectJobResponse> start(
            @RequestParam String mode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baseDate) {
        CollectMode collectMode = CollectMode.from(mode);
        List<JobExecution> running = runningJobs.findRunning(collectMode, null);
        if (!running.isEmpty()) {
            throw new ConflictException("같은 모드(" + collectMode.parameterValue() + ")로 실행 중: jobExecutionId="
                    + running.get(0).getId());
        }
        JobExecution execution = launcher.startAsync(collectMode, baseDate);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(CollectJobResponse.of(execution));
    }

    @GetMapping("/{id}")
    public CollectJobResponse get(@PathVariable long id) {
        return CollectJobResponse.of(find(id));
    }

    /**
     * 강제 종료로 STARTED에 남은 기록을 FAILED로 정리한다 (JobOperator.recover).
     * recover는 실행이 실제로 죽었는지 확인하지 않으므로 오래된 기록만 허용한다.
     */
    @PostMapping("/{id}/recover")
    public CollectJobResponse recover(@PathVariable long id) {
        JobExecution execution = find(id);
        if (!runningJobs.isStale(execution)) {
            throw new ConflictException("오래된 실행 기록만 정리할 수 있음: jobExecutionId=" + id
                    + ", status=" + execution.getStatus() + ", startTime=" + execution.getStartTime());
        }
        return CollectJobResponse.of(jobOperator.recover(execution));
    }

    private JobExecution find(long id) {
        JobExecution execution;
        try {
            execution = jobRepository.getJobExecution(id);
        } catch (EmptyResultDataAccessException e) {
            // Batch 6.0.5의 JDBC 저장소는 없는 id에 null 대신 이 예외를 던진다
            execution = null;
        }
        if (execution == null || !RunningCollectJobs.JOB_NAME.equals(execution.getJobInstance().getJobName())) {
            throw new NotFoundException("collectTradeJob 실행이 없음: jobExecutionId=" + id);
        }
        return execution;
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    static class ConflictException extends RuntimeException {
        ConflictException(String message) {
            super(message);
        }
    }

    static class NotFoundException extends RuntimeException {
        NotFoundException(String message) {
            super(message);
        }
    }

    /**
     * @param summary step ExecutionContext의 summary.* 값 (대상·성공·실패·건너뜀·남음·호출 수)
     */
    public record CollectJobResponse(
            long jobExecutionId,
            String mode,
            String baseDate,
            String status,
            String exitCode,
            String exitDescription,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Map<String, Object> summary) {

        static CollectJobResponse of(JobExecution execution) {
            Map<String, Object> summary = new TreeMap<>();
            execution.getStepExecutions().forEach(step -> step.getExecutionContext().toMap().forEach((key, value) -> {
                if (key.startsWith("summary.")) {
                    summary.put(key.substring("summary.".length()), value);
                }
            }));
            return new CollectJobResponse(
                    execution.getId(),
                    execution.getJobParameters().getString(CollectJobParameters.MODE),
                    execution.getJobParameters().getString(CollectJobParameters.BASE_DATE),
                    execution.getStatus().name(),
                    execution.getExitStatus().getExitCode(),
                    execution.getExitStatus().getExitDescription(),
                    execution.getStartTime(),
                    execution.getEndTime(),
                    summary);
        }
    }
}
