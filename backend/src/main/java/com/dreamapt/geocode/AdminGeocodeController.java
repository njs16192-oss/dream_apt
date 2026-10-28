package com.dreamapt.geocode;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 단지 좌표 지오코딩 관리 API (ADR 010). 인증이 없으므로 외부 배포 전에 보호 장치가 필요하다.
 * 단지 하나에 API를 1–3회 호출하므로 limit을 나눠 여러 번 실행한다. remaining이 0이 되면 끝이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminGeocodeController {

    private final ComplexGeocodeService service;

    @Operation(summary = "단지 좌표 지오코딩",
            description = "좌표가 없고 아직 시도하지 않은 단지를 limit개까지 처리한다. 요청 하나가 길어지지 않게 limit을 나눠 반복 호출한다")
    @PostMapping("/geocode")
    public GeocodeResult geocode(
            @Parameter(description = "처리할 최대 단지 수 (1–2000)") @RequestParam(defaultValue = "1000") @Min(1) @Max(2000) int limit,
            @Parameter(description = "실패로 기록된 단지도 다시 시도") @RequestParam(defaultValue = "false") boolean retryFailed) {
        return service.geocode(limit, retryFailed);
    }

    // 외부 API 문제는 우리 서버 오류(500)가 아니라 업스트림 오류(502)로 응답
    @ExceptionHandler(JusoApiException.class)
    public ProblemDetail handleApiError(JusoApiException e) {
        log.warn("지오코딩 실패: {}", e.getMessage(), e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}
