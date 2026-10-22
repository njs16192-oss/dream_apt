package com.dreamapt.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class TradeXmlParserTest {

    private final TradeXmlParser parser = new TradeXmlParser();

    @Test
    void 샘플_응답을_파싱한다() throws IOException {
        TradePage page = parser.parse(sampleXml());

        assertThat(page.items()).hasSize(10);
        assertThat(page.totalCount()).isEqualTo(252);

        TradeRecord first = page.items().get(0);
        assertThat(first.aptSeq()).isEqualTo("11500-151");
        assertThat(first.aptNm()).isEqualTo("가양2단지(성지)");
        assertThat(first.sggCd()).isEqualTo("11500");
        assertThat(first.umdCd()).isEqualTo("10400");
        assertThat(first.jibun()).isEqualTo("1461");
        assertThat(first.roadAddress()).isEqualTo("허준로 47");
        assertThat(first.buildYear()).isEqualTo((short) 1992);
        assertThat(first.dealDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(first.dealAmount()).isEqualTo(81700);
        assertThat(first.excluUseAr()).isEqualByComparingTo("34.44");
        assertThat(first.floor()).isEqualTo((short) 7);
        assertThat(first.sellerGbn()).isEqualTo("개인");
        assertThat(first.agentSggNm()).isEqualTo("서울 강서구");
    }

    @Test
    void 공백_문자열은_null로_바꾸고_해제_거래가_아니다() throws IOException {
        TradeRecord first = parser.parse(sampleXml()).items().get(0);

        assertThat(first.aptDong()).isNull();
        assertThat(first.cancelled()).isFalse();
        assertThat(first.cancelDate()).isNull();
        assertThat(first.registeredDate()).isNull();
    }

    @Test
    void 전용면적은_소수_둘째_자리로_반올림한다() throws IOException {
        TradeRecord second = parser.parse(sampleXml()).items().get(1);

        // 원본 134.8836
        assertThat(second.excluUseAr()).isEqualTo(new BigDecimal("134.88"));
    }

    @Test
    void 해제_거래와_등기일을_파싱한다() throws IOException {
        String xml = sampleXml()
                .replaceFirst("<cdealType> </cdealType>", "<cdealType>O</cdealType>")
                .replaceFirst("<cdealDay> </cdealDay>", "<cdealDay>26.10.05</cdealDay>")
                .replaceFirst("<rgstDate> </rgstDate>", "<rgstDate>26.10.15</rgstDate>")
                .replaceFirst("<aptDong> </aptDong>", "<aptDong>201</aptDong>");

        TradeRecord first = parser.parse(xml).items().get(0);

        assertThat(first.cancelled()).isTrue();
        assertThat(first.cancelDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(first.registeredDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(first.aptDong()).isEqualTo("201");
    }

    @Test
    void 인증_오류_응답이면_예외() {
        String xml = """
                <OpenAPI_ServiceResponse><cmmMsgHeader>
                <errMsg>SERVICE ERROR</errMsg>
                <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>
                <returnReasonCode>30</returnReasonCode>
                </cmmMsgHeader></OpenAPI_ServiceResponse>""";

        assertThatThrownBy(() -> parser.parse(xml))
                .isInstanceOf(TradeApiException.class)
                .hasMessageContaining("SERVICE_KEY_IS_NOT_REGISTERED_ERROR");
    }

    @Test
    void DOCTYPE이_있으면_거부한다() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <response><header><resultCode>&xxe;</resultCode></header></response>""";

        assertThatThrownBy(() -> parser.parse(xml)).isInstanceOf(TradeApiException.class);
    }

    static String sampleXml() throws IOException {
        return new ClassPathResource("sample/apt-trade-sample.xml").getContentAsString(StandardCharsets.UTF_8);
    }
}
