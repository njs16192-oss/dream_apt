package com.dreamapt.collect.batch;

import org.springframework.batch.core.ExitStatus;

/**
 * 이 Job에서 쓰는 ExitStatus 코드 (ADR 006 3-⑥). 프로세스 종료 코드 매핑은 {@link CollectJobExitCodes}.
 */
public final class CollectExitStatus {

    /** 일부 구×월이 실패했고 나머지는 끝까지 처리함. BatchStatus는 COMPLETED */
    public static final String COMPLETED_WITH_FAILURES = "COMPLETED_WITH_FAILURES";
    /** 같은 모드의 다른 실행이 진행 중이라 시작하지 않음. BatchStatus는 STOPPED */
    public static final String ALREADY_RUNNING = "ALREADY_RUNNING";

    private CollectExitStatus() {
    }

    static ExitStatus completedWithFailures(String description) {
        return new ExitStatus(COMPLETED_WITH_FAILURES, description);
    }

    static ExitStatus alreadyRunning(String description) {
        return new ExitStatus(ALREADY_RUNNING, description);
    }
}
