package com.dreamapt.collect.batch;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.parameters.JobParametersValidator;

/**
 * collectTradeJob 파라미터 (ADR 006 3-③).
 * Batch 6의 {@code JobOperator.start}는 Job에 incrementer가 있으면 넘긴 파라미터를 버리므로 incrementer를 쓰지 않고,
 * 실행마다 고유한 run.id(시작 시각 ms)를 직접 넣어 매번 새 JobInstance가 되게 한다.
 */
public final class CollectJobParameters {

    public static final String MODE = "mode";
    /** 기준일 yyyy-MM-dd (Asia/Seoul 날짜) */
    public static final String BASE_DATE = "baseDate";
    public static final String RUN_ID = "run.id";

    private CollectJobParameters() {
    }

    public static JobParameters of(CollectMode mode, LocalDate baseDate, long runId) {
        return new JobParametersBuilder()
                .addString(MODE, mode.parameterValue())
                .addString(BASE_DATE, baseDate.toString())
                .addLong(RUN_ID, runId)
                .toJobParameters();
    }

    public static CollectMode mode(JobParameters parameters) {
        return CollectMode.from(parameters.getString(MODE));
    }

    /** 기준일. 파라미터에 없으면 null (Tasklet이 Clock의 오늘로 채운다) */
    public static LocalDate baseDate(JobParameters parameters) {
        String value = parameters.getString(BASE_DATE);
        return value == null ? null : LocalDate.parse(value);
    }

    /** mode는 필수, baseDate는 있으면 yyyy-MM-dd */
    public static JobParametersValidator validator() {
        return parameters -> {
            try {
                mode(parameters);
                baseDate(parameters);
            } catch (IllegalArgumentException | DateTimeParseException e) {
                throw new InvalidJobParametersException(e.getMessage());
            }
        };
    }
}
