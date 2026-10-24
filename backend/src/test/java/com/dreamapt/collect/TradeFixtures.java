package com.dreamapt.collect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.core.io.ClassPathResource;

/**
 * 대조 테스트용 실데이터 픽스처. {@code sample/reconcile/}의 XML은 2026-10-25 조회한 API 응답에서
 * 해당 단지의 item만 원문 그대로 뽑은 것이다 (docs/analysis/2026-10-key-collision.md 사례).
 */
final class TradeFixtures {

    /** 송파해링턴타워 2026-09-18 일괄 매매 170건 (17개 키 그룹, 그룹당 10~12건) */
    static final String HARRINGTON = "harrington-tower-11710-202609.xml";
    /** 중계무지개 정상(등기·동) 1 + 해제 2 */
    static final String JUNGGYE = "junggye-mujigae-11350-202608.xml";
    /** 명덕 정상(등기) 1 + 해제 1 */
    static final String MYEONGDEOK = "myeongdeok-11500-202609.xml";
    /** 트리지움 중개거래 정상(등기·동) 1 + 직거래 해제 1 */
    static final String TRIZIUM = "trizium-11710-202607.xml";
    /** 송파더플래티넘 등기 1 + 미등기 1 */
    static final String THE_PLATINUM = "the-platinum-11710-202607.xml";

    private TradeFixtures() {
    }

    static List<TradeRecord> load(String name) {
        try {
            String xml = new ClassPathResource("sample/reconcile/" + name).getContentAsString(StandardCharsets.UTF_8);
            return new TradeXmlParser().parse(xml).items();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static TradeRecord withLateValues(TradeRecord r, String aptDong, boolean cancelled,
            LocalDate cancelDate, LocalDate registeredDate) {
        return new TradeRecord(r.aptSeq(), r.aptNm(), r.sggCd(), r.umdCd(), r.umdNm(), r.jibun(),
                r.roadAddress(), r.buildYear(), r.dealDate(), r.dealAmount(), r.excluUseAr(), r.floor(),
                aptDong, r.buyerGbn(), r.sellerGbn(), r.dealingGbn(), r.agentSggNm(),
                cancelled, cancelDate, registeredDate);
    }

    /** 등기·동이 채워지기 전 상태 */
    static TradeRecord unregistered(TradeRecord r) {
        return withLateValues(r, null, r.cancelled(), r.cancelDate(), null);
    }

    static ExistingTrade existing(long id, TradeRecord r) {
        return existing(id, r, null);
    }

    static ExistingTrade existing(long id, TradeRecord r, LocalDateTime missingSince) {
        return new ExistingTrade(id, r.aptSeq(), r.dealDate(), r.dealAmount(), r.excluUseAr(), r.floor(),
                r.aptDong(), r.buyerGbn(), r.sellerGbn(), r.dealingGbn(), r.agentSggNm(),
                r.cancelled(), r.cancelDate(), r.registeredDate(), missingSince);
    }

    /** 레코드마다 1부터 id를 붙인 DB 행 */
    static List<ExistingTrade> existingAll(List<TradeRecord> records) {
        return java.util.stream.IntStream.range(0, records.size())
                .mapToObj(i -> existing(i + 1, records.get(i)))
                .toList();
    }
}
