package com.dreamapt.collect;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import lombok.extern.slf4j.Slf4j;

/**
 * 실거래가 API XML 응답 파서. 필드 변환 규칙은 ADR 002 참고.
 */
@Slf4j
@Component
public class TradeXmlParser {

    private static final String SUCCESS_CODE = "000";

    // 해제사유발생일(cdealDay), 등기일자(rgstDate) 형식. 예: "26.10.05"
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("yy.MM.dd");

    public TradePage parse(String xml) {
        Document doc = toDocument(xml);

        String resultCode = text(doc.getDocumentElement(), "resultCode");
        if (!SUCCESS_CODE.equals(resultCode)) {
            // 인증 실패 등은 <OpenAPI_ServiceResponse> 형식으로 와서 resultCode가 없다
            throw new TradeApiException("API 오류 응답: resultCode=" + resultCode
                    + ", resultMsg=" + text(doc.getDocumentElement(), "resultMsg")
                    + ", returnAuthMsg=" + text(doc.getDocumentElement(), "returnAuthMsg"));
        }

        NodeList itemNodes = doc.getElementsByTagName("item");
        List<TradeRecord> items = new ArrayList<>(itemNodes.getLength());
        for (int i = 0; i < itemNodes.getLength(); i++) {
            items.add(toRecord((Element) itemNodes.item(i)));
        }

        String totalCount = text(doc.getDocumentElement(), "totalCount");
        return new TradePage(items, totalCount == null ? items.size() : Integer.parseInt(totalCount));
    }

    private TradeRecord toRecord(Element item) {
        String cdealType = text(item, "cdealType");
        return new TradeRecord(
                text(item, "aptSeq"),
                text(item, "aptNm"),
                text(item, "sggCd"),
                text(item, "umdCd"),
                text(item, "umdNm"),
                text(item, "jibun"),
                roadAddress(item),
                toShort(text(item, "buildYear")),
                LocalDate.of(
                        Integer.parseInt(text(item, "dealYear")),
                        Integer.parseInt(text(item, "dealMonth")),
                        Integer.parseInt(text(item, "dealDay"))),
                Integer.parseInt(text(item, "dealAmount").replace(",", "")),
                // unique 키에 들어가는 값이라 컬럼 정밀도(NUMERIC(7,2))에 맞춰 Java에서 먼저 반올림
                new BigDecimal(text(item, "excluUseAr")).setScale(2, RoundingMode.HALF_UP),
                Short.parseShort(text(item, "floor")),
                text(item, "aptDong"),
                text(item, "buyerGbn"),
                text(item, "slerGbn"),
                text(item, "dealingGbn"),
                text(item, "estateAgentSggNm"),
                cdealType != null,
                toDate(text(item, "cdealDay"), "cdealDay"),
                toDate(text(item, "rgstDate"), "rgstDate"));
    }

    /**
     * 도로명 + 건물 본번(앞 0 제거) [+ "-" 부번]. 예: "허준로 47", "강서로 266-1"
     */
    private String roadAddress(Element item) {
        String roadNm = text(item, "roadNm");
        String bonbun = stripZeros(text(item, "roadNmBonbun"));
        if (roadNm == null || bonbun == null) {
            return roadNm;
        }
        String bubun = stripZeros(text(item, "roadNmBubun"));
        return bubun == null ? roadNm + " " + bonbun : roadNm + " " + bonbun + "-" + bubun;
    }

    // "00047" -> "47", "00000" -> null
    private static String stripZeros(String number) {
        if (number == null) {
            return null;
        }
        String stripped = number.replaceFirst("^0+", "");
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * 태그 값을 trim 해서 반환. 태그가 없거나 공백 문자열(" ")이면 null.
     */
    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent().trim();
        return value.isEmpty() ? null : value;
    }

    private static Short toShort(String value) {
        return value == null ? null : Short.valueOf(value);
    }

    private static LocalDate toDate(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, SHORT_DATE);
        } catch (DateTimeParseException e) {
            // 날짜 하나 때문에 수집 전체를 실패시키지 않는다
            log.warn("날짜 형식을 해석할 수 없어 null로 저장: {}={}", field, value);
            return null;
        }
    }

    private static Document toDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE(외부 엔티티) 공격 차단: 외부 응답을 파싱하므로 DOCTYPE 자체를 허용하지 않는다
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new TradeApiException("XML 파싱 실패", e);
        }
    }
}
