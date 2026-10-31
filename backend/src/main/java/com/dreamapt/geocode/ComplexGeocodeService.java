package com.dreamapt.geocode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 좌표 없는 단지를 도로명주소 API로 지오코딩한다 (ADR 010).
 * <ol>
 * <li>도로명주소로 검색하고, 결과가 없거나 법정동이 다르면 지번으로 다시 검색한다</li>
 * <li>결과의 법정동코드(admCd)가 단지의 sgg_cd + umd_cd와 같고, 건물번호(도로명) 또는 번지(지번)까지 같은 첫 건만 쓴다</li>
 * <li>그 건물의 주출입구 좌표(UTM-K)를 받아 WGS84로 바꿔 저장한다</li>
 * </ol>
 * 실패 사유는 기록해서 다시 호출하지 않는다. 일시 오류는 기록하지 않고 다음 실행에 맡기되 연속되면 멈추고,
 * 키 오류는 예외로 실행을 멈춘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplexGeocodeService {

    private static final int LOG_EVERY = 100;
    // 일시 오류가 이만큼 연속되면 서버 장애로 보고 실행을 멈춘다 (ADR 010 ④)
    static final int MAX_CONSECUTIVE_TRANSIENT = 5;
    // "송파대로 345", "통일로 555-1"처럼 건물번호가 있어야 도로명으로 찾을 수 있다 ("만리재로"만 있는 단지가 있다)
    private static final Pattern ROAD_WITH_NUMBER = Pattern.compile("^(\\S+) (\\d+)(?:-(\\d+))?$");
    // "913", "176-1", "산11-244"
    private static final Pattern JIBUN_NUMBER = Pattern.compile("^(산)?(\\d+)(?:-(\\d+))?$");

    private final JusoApiClient client;
    private final ComplexGeocodeRepository repository;

    public GeocodeResult geocode(int limit, boolean retryFailed) {
        List<GeocodeTarget> targets = repository.findTargets(limit, retryFailed);
        log.info("지오코딩 시작: 대상 {}개 (limit={}, retryFailed={})", targets.size(), limit, retryFailed);

        Counts counts = new Counts();
        int consecutiveTransient = 0;
        for (GeocodeTarget target : targets) {
            Optional<GeocodeError> error;
            try {
                error = geocodeOne(target);
            } catch (JusoApiException e) {
                if (e.kind().abortsRun()) {
                    log.warn("지오코딩 중단: {} (처리 {}개)", e.getMessage(), counts.attempted);
                    throw e;
                }
                if (e.kind() == JusoApiException.Kind.TRANSIENT) {
                    log.warn("일시 오류, 다음 실행에서 다시 시도: aptSeq={} {}", target.aptSeq(), e.getMessage());
                    counts.attempted++;
                    counts.transientError++;
                    // 서버가 내려가 있으면 남은 단지도 모두 타임아웃을 겪는다. 연속되면 멈추고 지금까지의 결과를 돌려준다
                    if (++consecutiveTransient >= MAX_CONSECUTIVE_TRANSIENT) {
                        log.warn("일시 오류가 {}회 연속되어 멈춤 (처리 {}개)", consecutiveTransient, counts.attempted);
                        counts.stopped = true;
                        break;
                    }
                    continue;
                }
                log.warn("API 오류: aptSeq={} {}", target.aptSeq(), e.getMessage());
                error = Optional.of(GeocodeError.API_ERROR);
                save(target, () -> repository.saveFailure(target, GeocodeError.API_ERROR));
            }
            consecutiveTransient = 0;
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
        for (SearchQuery query : queries(target)) {
            List<JusoAddress> results = client.search(query.keyword());
            anyResult |= !results.isEmpty();
            matched = results.stream()
                    .filter(a -> target.expectedAdmCd().equals(a.admCd()))
                    .filter(query.sameAddress())
                    .findFirst();
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

    /**
     * 검색어와, 결과가 단지 주소와 같은 건물인지 보는 조건.
     * juso 검색은 키워드 검색이라 정확히 일치하지 않아도 같은 동의 비슷한 주소를 돌려준다
     * (예: "공릉동 270-71" → 270-47). 그래서 법정동코드에 더해 번호까지 같아야 쓴다 (ADR 010 ③)
     */
    record SearchQuery(String keyword, Predicate<JusoAddress> sameAddress) {
    }

    /** 도로명주소 → 지번 순서. 시도·시군구를 붙여 다른 지역의 같은 주소를 줄인다 */
    static List<SearchQuery> queries(GeocodeTarget target) {
        List<SearchQuery> queries = new ArrayList<>(2);
        Matcher road = matcher(ROAD_WITH_NUMBER, target.roadAddress());
        if (road != null) {
            String roadName = road.group(1);
            int main = Integer.parseInt(road.group(2));
            int sub = road.group(3) == null ? 0 : Integer.parseInt(road.group(3));
            queries.add(new SearchQuery(join(target.sidoNm(), target.sggNm(), target.roadAddress()),
                    a -> roadName.equals(a.rn()) && sameNumber(a.buldMnnm(), main) && sameNumber(a.buldSlno(), sub)));
        }
        // "BL-3-1", "가-238"처럼 숫자 번지가 아니면 번호를 맞춰 볼 수 없으므로 검색하지 않는다
        Matcher jibun = target.umdNm() == null ? null : matcher(JIBUN_NUMBER, target.jibun());
        if (jibun != null) {
            boolean mountain = jibun.group(1) != null;
            int main = Integer.parseInt(jibun.group(2));
            int sub = jibun.group(3) == null ? 0 : Integer.parseInt(jibun.group(3));
            queries.add(new SearchQuery(join(target.sidoNm(), target.sggNm(), target.umdNm(), target.jibun()),
                    a -> "1".equals(a.mtYn()) == mountain
                            && sameNumber(a.lnbrMnnm(), main) && sameNumber(a.lnbrSlno(), sub)));
        }
        return queries;
    }

    private static Matcher matcher(Pattern pattern, String value) {
        if (value == null) {
            return null;
        }
        Matcher m = pattern.matcher(value.trim());
        return m.matches() ? m : null;
    }

    /** juso는 번호를 "345", 부번이 없으면 "0"으로 준다 */
    private static boolean sameNumber(String value, int expected) {
        if (value == null) {
            return expected == 0;
        }
        try {
            return Integer.parseInt(value.trim()) == expected;
        } catch (NumberFormatException e) {
            return false;
        }
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
        boolean stopped;

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
                    stopped, remaining);
        }
    }
}
