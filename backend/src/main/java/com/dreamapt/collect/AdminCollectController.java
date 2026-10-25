package com.dreamapt.collect;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dreamapt.collect.batch.CollectTarget;
import com.dreamapt.collect.batch.CollectTargetRunner;

import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 수동 수집용 관리 API. 인증이 없으므로 외부 배포 전에 보호 장치가 필요하다.
 * 배치와 같은 경로(일시 오류 재시도, collect_history 기록)로 수집한다.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminCollectController {

    private static final DateTimeFormatter DEAL_YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final CollectTargetRunner runner;

    @PostMapping("/collect")
    public CollectResult collect(
            @RequestParam @Pattern(regexp = "\\d{5}", message = "sggCd는 5자리 숫자") String sggCd,
            @RequestParam @Pattern(regexp = "\\d{4}(0[1-9]|1[0-2])", message = "dealYm은 YYYYMM") String dealYm) {
        return runner.collect(new CollectTarget(sggCd, YearMonth.parse(dealYm, DEAL_YM)), null);
    }

    // 외부 API 문제는 우리 서버 오류(500)가 아니라 업스트림 오류(502)로 응답
    @ExceptionHandler(TradeApiException.class)
    public ProblemDetail handleApiError(TradeApiException e) {
        log.warn("수집 실패: {}", e.getMessage(), e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}
