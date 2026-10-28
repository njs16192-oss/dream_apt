package com.dreamapt.geocode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 좌표 없는 단지를 도로명주소 API로 지오코딩한다 (ADR 010).
 * <ol>
 * <li>도로명주소로 검색하고, 결과가 없거나 법정동이 다르면 지번으로 다시 검색한다</li>
 * <li>결과의 법정동코드(admCd)가 단지의 sgg_cd + umd_cd와 같은 첫 건만 쓴다</li>
 * <li>그 건물의 주출입구 좌표(UTM-K)를 받아 WGS84로 바꿔 저장한다</li>
 * </ol>
 * 실패 사유는 기록해서 다시 호출하지 않는다. 일시 오류는 기록하지 않고 다음 실행에 맡기고, 키 오류는 실행을 멈춘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplexGeocodeService {

    private static final int LOG_EVERY = 100;
    // "송파대로 345", "통일로 555-1"처럼 건물번호가 있어야 도로명으로 찾을 수 있다 ("만리재로"만 있는 단지가 있다)
    private static final Pattern ROAD_WITH_NUMBER = Pattern.compile("^\\S+ \\d+(-\\d+)?$");

    private final JusoApiClient client;
    private final ComplexGeocodeRepository repository;

    public GeocodeResult geocode(int limit, boolean retryFailed) {
        List<GeocodeTarget> targets = repository.findTargets(limit, retryFailed);
        log.info("지오코딩 시작: 대상 {}개 (limit={}, retryFailed={})", targets.size(), limit, retryFailed);

        Counts counts = new Counts();
        for (GeocodeTarget target : targets) {
            Optional<GeocodeError> error;
            try {
                error = geocodeOne(target);
            } catch (JusoApiException e) {
                if (e.kind() == JusoApiException.Kind.AUTH) {
                    log.warn("지오코딩 중단: {} (처리 {}개)", e.getMessage(), counts.attempted);
                    throw e;
                }
                if (e.kind() == JusoApiException.Kind.TRANSIENT) {
                    log.warn("일시 오류, 다음 실행에서 다시 시도: aptSeq={} {}", target.aptSeq(), e.getMessage());
                    counts.attempted++;
                    counts.transientError++;
                    continue;
                }
                log.warn("API 오류: aptSeq={} {}", target.aptSeq(), e.getMessage());
                error = Optional.of(GeocodeError.API_ERROR);
                repository.saveFailure(target, GeocodeError.API_ERROR);
            }
            counts.add(error);
            if (counts.attempted % LOG_EVERY == 0) {
                log.info("지오코딩 진행: {}/{} (성공 {})", counts.attempted, targets.size(), counts.succeeded);
            }
        }

        GeocodeResult result = counts.toResult(repository.countTargets(retryFailed));
        log.info("지오코딩 끝: {}", result);
        return result;
    }

    /** @return 실패 사유. 성공이면 empty */
    private Optional<GeocodeError> geocodeOne(GeocodeTarget target) {
        boolean anyResult = false;
        Optional<JusoAddress> matched = Optional.empty();
        for (String keyword : keywords(target)) {
            List<JusoAddress> results = client.search(keyword);
            anyResult |= !results.isEmpty();
            matched = results.stream().filter(a -> target.expectedAdmCd().equals(a.admCd())).findFirst();
            if (matched.isPresent()) {
                break;
            }
        }
        if (matched.isEmpty()) {
            GeocodeError error = anyResult ? GeocodeError.MISMATCH : GeocodeError.NOT_FOUND;
            save(target, () -> repository.saveFailure(target, error));
            return Optional.of(error);
        }

        Optional<UtmkPoint> point = client.coord(matched.get());
        if (point.isEmpty()) {
            save(target, () -> repository.saveFailure(target, GeocodeError.NO_COORD));
            return Optional.of(GeocodeError.NO_COORD);
        }
        save(target, () -> repository.saveLocation(target, point.get()));
        return Optional.empty();
    }

    /** 도로명주소 → 지번 순서. 시도·시군구를 붙여 다른 지역의 같은 주소를 줄인다 */
    static List<String> keywords(GeocodeTarget target) {
        List<String> keywords = new ArrayList<>(2);
        if (target.roadAddress() != null && ROAD_WITH_NUMBER.matcher(target.roadAddress()).matches()) {
            keywords.add(join(target.sidoNm(), target.sggNm(), target.roadAddress()));
        }
        if (target.jibun() != null && target.umdNm() != null) {
            keywords.add(join(target.sidoNm(), target.sggNm(), target.umdNm(), target.jibun()));
        }
        return keywords;
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                sb.append(sb.isEmpty() ? "" : " ").append(part.trim());
            }
        }
        return sb.toString();
    }

    private static void save(GeocodeTarget target, BooleanSupplier write) {
        if (!write.getAsBoolean()) {
            log.info("지오코딩 중 주소가 바뀌어 저장하지 않음: aptSeq={}", target.aptSeq());
        }
    }

    private static final class Counts {
        int attempted;
        int succeeded;
        int notFound;
        int mismatch;
        int noCoord;
        int apiError;
        int transientError;

        void add(Optional<GeocodeError> error) {
            attempted++;
            if (error.isEmpty()) {
                succeeded++;
                return;
            }
            switch (error.get()) {
                case NOT_FOUND -> notFound++;
                case MISMATCH -> mismatch++;
                case NO_COORD -> noCoord++;
                case API_ERROR -> apiError++;
            }
        }

        GeocodeResult toResult(long remaining) {
            return new GeocodeResult(attempted, succeeded, notFound, mismatch, noCoord, apiError, transientError,
                    remaining);
        }
    }
}
