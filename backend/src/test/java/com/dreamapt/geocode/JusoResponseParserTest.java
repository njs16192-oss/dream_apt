package com.dreamapt.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

import com.dreamapt.geocode.JusoApiException.Kind;

class JusoResponseParserTest {

    private final JusoResponseParser parser = new JusoResponseParser();

    // juso-search-sample.json은 실제 응답 (2026-10-28, "서울특별시 송파구 송파대로 345")
    @Test
    void 검색_결과를_파싱한다() throws IOException {
        assertThat(parser.parseSearch(sample("juso-search-sample.json"))).containsExactly(
                new JusoAddress("1171010700", "117102005011", "0", "345", "0",
                        "서울특별시 송파구 송파대로 345 (가락동, 헬리오시티)"));
    }

    // juso-coord-sample.json은 좌표제공 API 키 승인 전이라 손으로 만든 것. 좌표는 WGS84 (127.1078, 37.4966)을
    // UTM-K로 바꾼 값이다. 키가 나오면 실제 응답으로 바꾼다
    @Test
    void 좌표를_파싱한다() throws IOException {
        assertThat(parser.parseCoord(sample("juso-coord-sample.json")))
                .contains(new UtmkPoint(965331.7703824214, 1944221.3447027379));
    }

    @Test
    void 결과가_없으면_빈_목록이다() {
        String emptyArray = """
                {"results":{"common":{"errorMessage":"정상","totalCount":"0","errorCode":"0"},"juso":[]}}""";
        String nullJuso = """
                {"results":{"common":{"errorMessage":"정상","totalCount":"0","errorCode":"0"},"juso":null}}""";

        assertThat(parser.parseSearch(emptyArray)).isEmpty();
        assertThat(parser.parseSearch(nullJuso)).isEmpty();
        assertThat(parser.parseCoord(emptyArray)).isEmpty();
        assertThat(parser.parseCoord(nullJuso)).isEmpty();
    }

    @Test
    void 좌표가_공백이면_없는_것으로_본다() {
        String blank = """
                {"results":{"common":{"errorCode":"0"},"juso":[{"admCd":"1171010700","entX":" ","entY":""}]}}""";

        assertThat(parser.parseCoord(blank)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "E0001, AUTH",      // 승인되지 않은 키
            "E0014, AUTH",      // 개발 승인키 기간 만료
            "-999, TRANSIENT",  // 시스템 오류
            "E0006, OTHER",     // 주소를 상세히 입력
            "E0013, OTHER",     // 특수문자·SQL 예약어 포함
    })
    void 오류_응답은_코드에_따라_종류를_나눈다(String errorCode, Kind kind) {
        String error = """
                {"results":{"common":{"errorMessage":"오류","totalCount":"0","errorCode":"%s"},"juso":null}}"""
                .formatted(errorCode);

        assertThatThrownBy(() -> parser.parseSearch(error))
                .isInstanceOfSatisfying(JusoApiException.class, e -> assertThat(e.kind()).isEqualTo(kind))
                .hasMessageContaining(errorCode);
    }

    @Test
    void JSON이_아니면_OTHER() {
        assertThatThrownBy(() -> parser.parseSearch("<html>점검 중</html>"))
                .isInstanceOfSatisfying(JusoApiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.OTHER));
    }

    static String sample(String name) throws IOException {
        return new ClassPathResource("sample/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
