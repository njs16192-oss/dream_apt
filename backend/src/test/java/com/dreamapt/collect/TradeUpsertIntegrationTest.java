package com.dreamapt.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dreamapt.support.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TradeUpsertIntegrationTest {

    @Autowired
    TradeUpsertService upsertService;

    @Autowired
    JdbcTemplate jdbc;

    List<TradeRecord> sample;

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM apt_trade");
        jdbc.update("DELETE FROM apt_complex");
        sample = new TradeXmlParser().parse(TradeXmlParserTest.sampleXml()).items();
    }

    @Test
    void 처음_저장하면_모두_신규() {
        UpsertResult result = upsertService.save(sample);

        // 샘플 10건 중 단지는 8개 (11500-263, 11500-5419가 두 번씩 나옴)
        assertThat(result).isEqualTo(new UpsertResult(8, 10, 0, 0));
        assertThat(count("apt_complex")).isEqualTo(8);
        assertThat(count("apt_trade")).isEqualTo(10);
    }

    @Test
    void 같은_데이터를_두_번_저장해도_중복이_생기지_않는다() {
        upsertService.save(sample);
        List<Long> idsBefore = tradeIds();

        UpsertResult second = upsertService.save(sample);

        assertThat(second).isEqualTo(new UpsertResult(8, 0, 0, 10));
        assertThat(count("apt_complex")).isEqualTo(8);
        assertThat(count("apt_trade")).isEqualTo(10);
        assertThat(tradeIds()).isEqualTo(idsBefore);
    }

    @Test
    void 해제_정보가_나중에_들어오면_갱신된다() {
        TradeRecord original = sample.get(0);
        upsertService.save(List.of(original));
        Map<String, Object> before = trade(original);

        TradeRecord cancelled = withLateValues(original, null, true, LocalDate.of(2026, 10, 5), null);
        UpsertResult result = upsertService.save(List.of(cancelled));

        Map<String, Object> after = trade(original);
        assertThat(result).isEqualTo(new UpsertResult(1, 0, 1, 0));
        assertThat(count("apt_trade")).isEqualTo(1);
        assertThat(after.get("id")).isEqualTo(before.get("id"));
        assertThat(after.get("cancelled")).isEqualTo(true);
        assertThat(after.get("cancel_date")).isEqualTo(java.sql.Date.valueOf("2026-10-05"));
        assertThat((Timestamp) after.get("updated_at")).isAfter((Timestamp) before.get("updated_at"));
        assertThat(after.get("created_at")).isEqualTo(before.get("created_at"));
    }

    @Test
    void 등기일과_동이_나중에_채워지면_갱신된다() {
        TradeRecord original = sample.get(0);
        upsertService.save(List.of(original));

        TradeRecord registered = withLateValues(original, "201", false, null, LocalDate.of(2026, 10, 15));
        UpsertResult result = upsertService.save(List.of(registered));

        Map<String, Object> after = trade(original);
        assertThat(result).isEqualTo(new UpsertResult(1, 0, 1, 0));
        assertThat(count("apt_trade")).isEqualTo(1);
        assertThat(after.get("apt_dong")).isEqualTo("201");
        assertThat(after.get("registered_date")).isEqualTo(java.sql.Date.valueOf("2026-10-15"));
        assertThat(after.get("cancelled")).isEqualTo(false);
    }

    @Test
    void 단지_정보가_바뀌면_갱신된다() {
        TradeRecord original = sample.get(0);
        upsertService.save(List.of(original));

        TradeRecord renamed = new TradeRecord(original.aptSeq(), "가양2단지성지아파트", original.sggCd(),
                original.umdCd(), original.umdNm(), original.jibun(), original.roadAddress(),
                original.buildYear(), original.dealDate(), original.dealAmount(), original.excluUseAr(),
                original.floor(), original.aptDong(), original.buyerGbn(), original.sellerGbn(),
                original.dealingGbn(), original.agentSggNm(), original.cancelled(), original.cancelDate(),
                original.registeredDate());
        upsertService.save(List.of(renamed));

        assertThat(jdbc.queryForObject("SELECT name FROM apt_complex WHERE apt_seq = ?", String.class,
                original.aptSeq())).isEqualTo("가양2단지성지아파트");
        assertThat(count("apt_complex")).isEqualTo(1);
    }

    private static TradeRecord withLateValues(TradeRecord r, String aptDong, boolean cancelled,
            LocalDate cancelDate, LocalDate registeredDate) {
        return new TradeRecord(r.aptSeq(), r.aptNm(), r.sggCd(), r.umdCd(), r.umdNm(), r.jibun(),
                r.roadAddress(), r.buildYear(), r.dealDate(), r.dealAmount(), r.excluUseAr(), r.floor(),
                aptDong, r.buyerGbn(), r.sellerGbn(), r.dealingGbn(), r.agentSggNm(),
                cancelled, cancelDate, registeredDate);
    }

    private Map<String, Object> trade(TradeRecord r) {
        return jdbc.queryForMap("""
                SELECT * FROM apt_trade
                WHERE apt_seq = ? AND deal_date = ? AND exclu_use_ar = ? AND floor = ? AND deal_amount = ?
                """, r.aptSeq(), r.dealDate(), r.excluUseAr(), r.floor(), r.dealAmount());
    }

    private List<Long> tradeIds() {
        return jdbc.queryForList("SELECT id FROM apt_trade ORDER BY id", Long.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
