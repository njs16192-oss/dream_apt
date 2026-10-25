package com.dreamapt.collect.batch;

import java.time.LocalDate;
import java.util.List;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * batch 프로필: 기동 후 collectTradeJob을 한 번 실행하고, 결과를 프로세스 종료 코드로 돌려준다 (ADR 006 3-⑤, 3-⑥).
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=batch --mode=daily [--baseDate=2026-10-25]'
 * </pre>
 * Boot의 자동 실행(spring.batch.job.enabled)을 쓰지 않는 이유: run.id를 직접 넣어야 하고({@link CollectJobParameters}),
 * Boot 기본 종료 코드(BatchStatus 순번)로는 일부 실패를 구분할 수 없다.
 * 잘못된 인자는 예외로 기동을 실패시켜 Boot 기본 종료 코드 1로 끝난다.
 */
@Slf4j
@Component
@Profile("batch")
@RequiredArgsConstructor
public class CollectJobCommandLineRunner implements ApplicationRunner, ExitCodeGenerator {

    private final CollectJobLauncher launcher;

    private int exitCode = CollectJobExitCodes.SUCCESS;

    @Override
    public void run(ApplicationArguments args) {
        CollectMode mode = CollectMode.from(singleOption(args, CollectJobParameters.MODE));
        String baseDate = singleOption(args, CollectJobParameters.BASE_DATE);

        JobExecution execution = launcher.run(mode, baseDate == null ? null : LocalDate.parse(baseDate));
        exitCode = CollectJobExitCodes.of(execution);
        log.info("collectTradeJob 종료 jobExecutionId={} status={} exitStatus={} 종료 코드={}",
                execution.getId(), execution.getStatus(), execution.getExitStatus().getExitCode(), exitCode);
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    private static String singleOption(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new IllegalArgumentException("--" + name + "는 한 번만 지정: " + values);
        }
        return values.get(0);
    }
}
