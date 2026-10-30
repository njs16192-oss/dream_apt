package com.dreamapt.geocode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.dreamapt.geocode.JusoApiException.Kind;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 도로명주소 API JSON 응답 파서. 검색·좌표제공 API 모두 {@code results.common}에 결과 코드,
 * {@code results.juso}에 결과 목록이 온다. 값은 숫자도 문자열로 온다.
 */
@Component
public class JusoResponseParser {

    private static final String SUCCESS_CODE = "0";
    // E0001: 승인되지 않은 키, E0014: 개발 승인키 기간 만료
    private static final Set<String> AUTH_CODES = Set.of("E0001", "E0014");
    // -999: 시스템 오류, E0007: 짧은 시간에 요청이 많음(빈도 제한). 단지 문제가 아니므로 일시 오류로 본다 (ADR 010 ④)
    private static final Set<String> TRANSIENT_CODES = Set.of("-999", "E0007");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public List<JusoAddress> parseSearch(String json) {
        List<JusoAddress> addresses = new ArrayList<>();
        for (JsonNode juso : jusoList(json)) {
            addresses.add(new JusoAddress(text(juso, "admCd"), text(juso, "rnMgtSn"), text(juso, "udrtYn"),
                    text(juso, "buldMnnm"), text(juso, "buldSlno"), text(juso, "roadAddr")));
        }
        return addresses;
    }

    /** 결과가 없거나 좌표가 비어 있으면 empty */
    public Optional<UtmkPoint> parseCoord(String json) {
        for (JsonNode juso : jusoList(json)) {
            String x = text(juso, "entX");
            String y = text(juso, "entY");
            if (x == null || y == null) {
                continue;
            }
            try {
                return Optional.of(new UtmkPoint(Double.parseDouble(x), Double.parseDouble(y)));
            } catch (NumberFormatException e) {
                throw new JusoApiException(Kind.OTHER, "좌표 형식 오류: entX=" + x + ", entY=" + y, e);
            }
        }
        return Optional.empty();
    }

    private List<JsonNode> jusoList(String json) {
        JsonNode results = readTree(json).path("results");
        JsonNode common = results.path("common");
        String errorCode = text(common, "errorCode");
        if (!SUCCESS_CODE.equals(errorCode)) {
            throw new JusoApiException(errorKind(errorCode),
                    "도로명주소 API 오류 응답: errorCode=" + errorCode + ", errorMessage=" + text(common, "errorMessage"));
        }
        List<JsonNode> list = new ArrayList<>();
        // 결과가 없으면 juso가 빈 배열이나 null로 온다
        results.path("juso").forEach(list::add);
        return list;
    }

    private JsonNode readTree(String json) {
        try {
            return jsonMapper.readTree(json == null ? "" : json);
        } catch (JacksonException e) {
            throw new JusoApiException(Kind.OTHER, "도로명주소 API 응답 파싱 실패", e);
        }
    }

    static Kind errorKind(String errorCode) {
        if (AUTH_CODES.contains(errorCode)) {
            return Kind.AUTH;
        }
        if (TRANSIENT_CODES.contains(errorCode)) {
            return Kind.TRANSIENT;
        }
        return Kind.OTHER;
    }

    /** 공백 문자열은 null로 본다 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = (value.isString() ? value.stringValue() : value.toString()).trim();
        return text.isEmpty() ? null : text;
    }
}
