package com.dreamapt.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.dreamapt.geocode.JusoApiException.Kind;
import com.dreamapt.support.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ComplexGeocodeServiceIntegrationTest {

    // 헬리오시티: 송파구(11710) 가락동(10700). 좌표는 좌표제공 API의 실제 주출입구 좌표(UTM-K)
    private static final String HELIO = "11710-8865";
    private static final String ROAD_KEYWORD = "서울특별시 송파구 송파대로 345";
    private static final String JIBUN_KEYWORD = "서울특별시 송파구 가락동 913";
    private static final JusoAddress GARAK = address("1171010700");
    private static final JusoAddress MUNJEONG = address("1171010800"); // 같은 구의 다른 동
    private static final UtmkPoint HELIO_POINT = new UtmkPoint(965730.2897525667, 1944494.4993890333);

    @Autowired
    ComplexGeocodeService service;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    JusoApiClient client;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM apt_trade");
        jdbc.update("DELETE FROM apt_complex");
        complex(HELIO, "10700", "가락동", "913", "송파대로 345");
    }

    @Test
    void 도로명으로_찾아_주출입구_좌표를_WGS84로_저장한다() {
        given(client.search(ROAD_KEYWORD)).willReturn(List.of(GARAK));
        given(client.coord(GARAK)).willReturn(Optional.of(HELIO_POINT));

        GeocodeResult result = service.geocode(10, false);

        assertThat(result).isEqualTo(new GeocodeResult(1, 1, 0, 0, 0, 0, 0, false, 0));
        Map<String, Object> row = row(HELIO);
        // 기대값은 PostGIS ST_Transform 결과 (송파대로 345, 가락동)
        assertThat((Double) row.get("lng")).isCloseTo(127.1122956, within(1e-6));
        assertThat((Double) row.get("lat")).isCloseTo(37.4990770, within(1e-6));
        assertThat(row.get("srid")).isEqualTo(4326);
        assertThat(row.get("geocode_attempted_at")).isNotNull();
        assertThat(row.get("geocode_error")).isNull();
        verify(client, never()).search(JIBUN_KEYWORD);
    }

    @Test
    void 도로명_결과의_법정동이_다르면_지번으로_다시_찾는다() {
        given(client.search(ROAD_KEYWORD)).willReturn(List.of(MUNJEONG));
        given(client.search(JIBUN_KEYWORD)).willReturn(List.of(MUNJEONG, GARAK));
        given(client.coord(GARAK)).willReturn(Optional.of(HELIO_POINT));

        assertThat(service.geocode(10, false).succeeded()).isEqualTo(1);
        verify(client, never()).coord(MUNJEONG);
    }

    @Test
    void 결과의_법정동이_모두_다르면_MISMATCH로_기록하고_좌표를_받지_않는다() {
        given(client.search(anyString())).willReturn(List.of(MUNJEONG));

        GeocodeResult result = service.geocode(10, false);

        assertThat(result.mismatch()).isEqualTo(1);
        assertThat(row(HELIO)).containsEntry("geocode_error", "MISMATCH").containsEntry("lng", null);
        verify(client, never()).coord(any());
    }

    @Test
    void 결과가_없으면_NOT_FOUND() {
        given(client.search(anyString())).willReturn(List.of());

        assertThat(service.geocode(10, false).notFound()).isEqualTo(1);
        assertThat(row(HELIO)).containsEntry("geocode_error", "NOT_FOUND");
    }

    @Test
    void 도로명에_건물번호가_없으면_지번으로만_찾는다() {
        jdbc.update("UPDATE apt_complex SET road_address = '송파대로' WHERE apt_seq = ?", HELIO);
        given(client.search(JIBUN_KEYWORD)).willReturn(List.of(GARAK));
        given(client.coord(GARAK)).willReturn(Optional.of(HELIO_POINT));

        assertThat(service.geocode(10, false).succeeded()).isEqualTo(1);
        verify(client, never()).search("서울특별시 송파구 송파대로");
    }

    @Test
    void 좌표가_없으면_NO_COORD() {
        given(client.search(ROAD_KEYWORD)).willReturn(List.of(GARAK));
        given(client.coord(GARAK)).willReturn(Optional.empty());

        assertThat(service.geocode(10, false).noCoord()).isEqualTo(1);
        assertThat(row(HELIO)).containsEntry("geocode_error", "NO_COORD");
    }

    @Test
    void 단지별_API_오류는_기록하고_일시_오류는_기록하지_않고_다음_단지로_넘어간다() {
        complex("11710-1", "10700", "가락동", "1", "송파대로 1");
        complex("11710-2", "10700", "가락동", "2", "송파대로 2");
        given(client.search(ROAD_KEYWORD)).willReturn(List.of(GARAK));
        given(client.coord(GARAK)).willReturn(Optional.of(HELIO_POINT));
        given(client.search("서울특별시 송파구 송파대로 1")).willThrow(new JusoApiException(Kind.OTHER, "E0013"));
        given(client.search("서울특별시 송파구 송파대로 2")).willThrow(new JusoApiException(Kind.TRANSIENT, "timeout"));

        GeocodeResult result = service.geocode(10, false);

        assertThat(result).isEqualTo(new GeocodeResult(3, 1, 0, 0, 0, 1, 1, false, 1));
        assertThat(row("11710-1")).containsEntry("geocode_error", "API_ERROR");
        assertThat(row("11710-2")).containsEntry("geocode_attempted_at", null);
    }

    @Test
    void 일시_오류가_연속되면_멈추고_결과를_돌려준다() {
        for (int i = 1; i <= 7; i++) {
            complex("11710-" + i, "10700", "가락동", String.valueOf(i), "송파대로 " + i);
        }
        given(client.search(anyString())).willThrow(new JusoApiException(Kind.TRANSIENT, "timeout"));

        GeocodeResult result = service.geocode(100, false);

        // HELIO까지 8개 중 5개째에서 멈춘다. 일시 오류는 기록하지 않으므로 8개 모두 남는다
        assertThat(result.attempted()).isEqualTo(ComplexGeocodeService.MAX_CONSECUTIVE_TRANSIENT);
        assertThat(result.transientError()).isEqualTo(ComplexGeocodeService.MAX_CONSECUTIVE_TRANSIENT);
        assertThat(result.stopped()).isTrue();
        assertThat(result.remaining()).isEqualTo(8);
    }

    @Test
    void 일시_오류_사이에_다른_결과가_있으면_연속_횟수를_다시_센다() {
        // apt_seq는 문자열 순: 11710-1 ~ 4(일시 오류), 5(결과 없음), 6 ~ 8(일시 오류), 8865(HELIO, 성공), 9(일시 오류).
        // 다시 세지 않으면 5 다음에 7회째가 되어 멈춘다
        for (int i = 1; i <= 9; i++) {
            complex("11710-" + i, "10700", "가락동", String.valueOf(i), "송파대로 " + i);
        }
        given(client.search(anyString())).willThrow(new JusoApiException(Kind.TRANSIENT, "timeout"));
        // anyString에 예외를 걸어 두었으므로 given(client.search(..)) 대신 호출하지 않는 형식으로 덮어쓴다
        willReturn(List.of()).given(client).search("서울특별시 송파구 송파대로 5");
        willReturn(List.of()).given(client).search("서울특별시 송파구 가락동 5");
        willReturn(List.of(GARAK)).given(client).search(ROAD_KEYWORD);
        given(client.coord(GARAK)).willReturn(Optional.of(HELIO_POINT));

        GeocodeResult result = service.geocode(100, false);

        assertThat(result.stopped()).isFalse();
        assertThat(result.attempted()).isEqualTo(10);
        assertThat(result.transientError()).isEqualTo(8);
        assertThat(result.notFound()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
    }

    @Test
    void 키_오류는_실행을_멈추고_앞서_저장한_좌표는_남긴다() {
        complex("11710-9", "10700", "가락동", "9", "송파대로 9"); // apt_seq 순으로 HELIO 다음
        given(client.search(ROAD_KEYWORD)).willReturn(List.of(GARAK));
        given(client.coord(GARAK)).willReturn(Optional.of(HELIO_POINT));
        given(client.search("서울특별시 송파구 송파대로 9")).willThrow(new JusoApiException(Kind.AUTH, "E0001"));

        assertThatThrownBy(() -> service.geocode(10, false)).isInstanceOf(JusoApiException.class);
        assertThat(row(HELIO).get("lng")).isNotNull();
        assertThat(row("11710-9")).containsEntry("geocode_attempted_at", null);
    }

    @Test
    void 대상은_좌표가_없고_시도하지_않은_단지다_retryFailed면_실패한_단지도() {
        complex("11710-1", "10700", "가락동", "1", "송파대로 1");
        complex("11710-2", "10700", "가락동", "2", "송파대로 2");
        jdbc.update("""
                UPDATE apt_complex SET geocode_attempted_at = now(), geocode_error = 'NOT_FOUND' WHERE apt_seq = '11710-1';
                UPDATE apt_complex SET geocode_attempted_at = now(),
                    location = ST_SetSRID(ST_MakePoint(127.1, 37.5), 4326) WHERE apt_seq = '11710-2';
                """);
        given(client.search(anyString())).willReturn(List.of());

        assertThat(service.geocode(10, false)).satisfies(r -> {
            assertThat(r.attempted()).isEqualTo(1); // HELIO만
            assertThat(r.remaining()).isZero();
        });
        // 방금 실패한 HELIO와 전에 실패한 11710-1. 좌표가 있는 11710-2는 빠진다
        assertThat(service.geocode(10, true).attempted()).isEqualTo(2);
    }

    @Test
    void limit만큼만_처리하고_남은_수를_알려준다() {
        complex("11710-1", "10700", "가락동", "1", "송파대로 1");
        complex("11710-2", "10700", "가락동", "2", "송파대로 2");
        given(client.search(anyString())).willReturn(List.of());

        assertThat(service.geocode(2, false)).satisfies(r -> {
            assertThat(r.attempted()).isEqualTo(2);
            assertThat(r.remaining()).isEqualTo(1);
        });
        assertThat(service.geocode(2, false).remaining()).isZero();
    }

    @Test
    void 지오코딩_중에_주소가_바뀌면_옛_주소의_좌표를_저장하지_않는다() {
        given(client.search(ROAD_KEYWORD)).willReturn(List.of(GARAK));
        given(client.coord(GARAK)).willAnswer(invocation -> {
            // 좌표를 받는 사이 수집이 주소를 바꾼 상황
            jdbc.update("UPDATE apt_complex SET road_address = '송파대로 999' WHERE apt_seq = ?", HELIO);
            return Optional.of(HELIO_POINT);
        });

        service.geocode(10, false);

        assertThat(row(HELIO)).containsEntry("lng", null).containsEntry("geocode_attempted_at", null);
    }

    @Test
    void 검색어는_도로명_지번_순서이고_시도_시군구를_붙인다() {
        GeocodeTarget target = new GeocodeTarget(HELIO, "11710", "10700", "서울특별시", "송파구", "가락동", "913",
                "송파대로 345");

        assertThat(ComplexGeocodeService.keywords(target)).containsExactly(ROAD_KEYWORD, JIBUN_KEYWORD);
        assertThat(ComplexGeocodeService.keywords(new GeocodeTarget(HELIO, "11710", "10700", "서울특별시", "송파구",
                "가락동", "913", null))).containsExactly(JIBUN_KEYWORD);
    }

    private void complex(String aptSeq, String umdCd, String umdNm, String jibun, String roadAddress) {
        jdbc.update("""
                INSERT INTO apt_complex (apt_seq, name, sgg_cd, umd_cd, umd_nm, jibun, road_address)
                VALUES (?, ?, '11710', ?, ?, ?, ?)
                """, aptSeq, "단지" + aptSeq, umdCd, umdNm, jibun, roadAddress);
    }

    private Map<String, Object> row(String aptSeq) {
        return jdbc.queryForMap("""
                SELECT ST_X(location) AS lng, ST_Y(location) AS lat, ST_SRID(location) AS srid,
                       geocode_attempted_at, geocode_error
                FROM apt_complex WHERE apt_seq = ?
                """, aptSeq);
    }

    private static JusoAddress address(String admCd) {
        return new JusoAddress(admCd, "117102005011", "0", "345", "0", "서울특별시 송파구 송파대로 345");
    }
}
