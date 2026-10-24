package com.dreamapt.collect;

import static com.dreamapt.collect.TradeFixtures.HARRINGTON;
import static com.dreamapt.collect.TradeFixtures.TRIZIUM;
import static com.dreamapt.collect.TradeFixtures.load;
import static com.dreamapt.collect.TradeFixtures.withLateValues;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dreamapt.support.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TradeReconcileIntegrationTest {

    // sample/apt-trade-sample.xml은 강서구 2026-09 응답의 일부
    private static final String SAMPLE_SGG = "11500";
    private static final String SAMPLE_YM = "202609";

    @Autowired
    TradeReconcileService reconcileService;

    @Autowired
    TradeReconcileRepository repository;

    @Autowired
    PlatformTransactionManager transactionManager;

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
        ReconcileResult result = save(sample);

        // 샘플 10건 중 단지는 8개 (11500-263, 11500-5419가 두 번씩 나옴)
        assertThat(result).isEqualTo(new ReconcileResult(8, 10, 0, 0, 0, List.of()));
        assertThat(count("apt_complex")).isEqualTo(8);
        assertThat(count("apt_trade")).isEqualTo(10);
    }

    @Test
    void 같은_데이터를_두_번_저장해도_중복이_생기지_않는다() {
        save(sample);
        List<Long> idsBefore = tradeIds();

        ReconcileResult second = save(sample);

        assertThat(second).isEqualTo(new ReconcileResult(8, 0, 0, 10, 0, List.of()));
        assertThat(count("apt_trade")).isEqualTo(10);
        assertThat(tradeIds()).isEqualTo(idsBefore);
    }

    @Test
    void 해제_정보가_나중에_들어오면_갱신된다() {
        TradeRecord original = sample.get(0);
        save(List.of(original));
        Map<String, Object> before = trade(original);

        TradeRecord cancelled = withLateValues(original, null, true, LocalDate.of(2026, 10, 5), null);
        ReconcileResult result = save(List.of(cancelled));

        Map<String, Object> after = trade(original);
        assertThat(result).isEqualTo(new ReconcileResult(1, 0, 1, 0, 0, List.of()));
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
        save(List.of(original));

        TradeRecord registered = withLateValues(original, "201", false, null, LocalDate.of(2026, 10, 15));
        ReconcileResult result = save(List.of(registered));

        Map<String, Object> after = trade(original);
        assertThat(result).isEqualTo(new ReconcileResult(1, 0, 1, 0, 0, List.of()));
        assertThat(count("apt_trade")).isEqualTo(1);
        assertThat(after.get("apt_dong")).isEqualTo("201");
        assertThat(after.get("registered_date")).isEqualTo(java.sql.Date.valueOf("2026-10-15"));
        assertThat(after.get("cancelled")).isEqualTo(false);
    }

    @Test
    void 단지_정보가_바뀌면_갱신된다() {
        TradeRecord original = sample.get(0);
        save(List.of(original));

        TradeRecord renamed = new TradeRecord(original.aptSeq(), "가양2단지성지아파트", original.sggCd(),
                original.umdCd(), original.umdNm(), original.jibun(), original.roadAddress(),
                original.buildYear(), original.dealDate(), original.dealAmount(), original.excluUseAr(),
                original.floor(), original.aptDong(), original.buyerGbn(), original.sellerGbn(),
                original.dealingGbn(), original.agentSggNm(), original.cancelled(), original.cancelDate(),
                original.registeredDate());
        save(List.of(renamed));

        assertThat(jdbc.queryForObject("SELECT name FROM apt_complex WHERE apt_seq = ?", String.class,
                original.aptSeq())).isEqualTo("가양2단지성지아파트");
        assertThat(count("apt_complex")).isEqualTo(1);
    }

    @Test
    void 일괄_매매_170건이_모두_저장되고_재수집해도_그대로다_해링턴타워() {
        List<TradeRecord> harrington = load(HARRINGTON);

        ReconcileResult first = reconcileService.save("11710", "202609", harrington);
        List<Long> ids = tradeIds();
        ReconcileResult second = reconcileService.save("11710", "202609", harrington);

        assertThat(first).isEqualTo(new ReconcileResult(1, 170, 0, 0, 0, List.of()));
        assertThat(second).isEqualTo(new ReconcileResult(1, 0, 0, 170, 0, List.of()));
        assertThat(count("apt_trade")).isEqualTo(170);
        assertThat(tradeIds()).isEqualTo(ids);
    }

    @Test
    void 재신고로_거래_유형이_바뀌면_기존_행은_해제되고_새_행이_추가된다_트리지움() {
        List<TradeRecord> source = load(TRIZIUM);
        TradeRecord cancelled = source.stream().filter(TradeRecord::cancelled).findFirst().orElseThrow();
        reconcileService.save("11710", "202607", List.of(withLateValues(cancelled, null, false, null, null)));
        long originalId = tradeIds().get(0);

        ReconcileResult result = reconcileService.save("11710", "202607", source);

        assertThat(result).isEqualTo(new ReconcileResult(1, 1, 1, 0, 0, List.of()));
        assertThat(jdbc.queryForMap("SELECT dealing_gbn, cancelled FROM apt_trade WHERE id = ?", originalId))
                .containsEntry("dealing_gbn", "직거래")
                .containsEntry("cancelled", true);
        assertThat(jdbc.queryForObject("SELECT dealing_gbn FROM apt_trade WHERE id <> ?", String.class, originalId))
                .isEqualTo("중개거래");
    }

    @Test
    void 같은_키_그룹에서_일부만_해제되면_한_행만_해제되고_건수는_유지된다() {
        List<TradeRecord> group = load(HARRINGTON).stream()
                .filter(r -> r.floor() == 3 && r.dealAmount() == 41_350)
                .toList();
        reconcileService.save("11710", "202609", group);
        List<TradeRecord> source = new ArrayList<>(group.subList(1, 10));
        source.add(withLateValues(group.get(0), group.get(0).aptDong(), true, LocalDate.of(2026, 10, 20),
                group.get(0).registeredDate()));

        ReconcileResult result = reconcileService.save("11710", "202609", source);

        assertThat(result).isEqualTo(new ReconcileResult(1, 0, 1, 9, 0, List.of()));
        assertThat(count("apt_trade")).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apt_trade WHERE cancelled", Integer.class)).isEqualTo(1);
    }

    @Test
    void 원본에서_사라진_행은_남기고_표시하며_다시_나타나면_표시를_지운다() {
        save(sample);
        List<TradeRecord> withoutFirst = sample.subList(1, sample.size());

        ReconcileResult gone = save(withoutFirst);
        assertThat(gone).isEqualTo(new ReconcileResult(7, 0, 0, 9, 1, List.of()));
        assertThat(count("apt_trade")).isEqualTo(10);
        assertThat(trade(sample.get(0)).get("missing_since")).isNotNull();

        // 계속 없으면 처음 표시 시각을 유지한다
        Object since = trade(sample.get(0)).get("missing_since");
        save(withoutFirst);
        assertThat(trade(sample.get(0)).get("missing_since")).isEqualTo(since);

        ReconcileResult back = save(sample);
        assertThat(back).isEqualTo(new ReconcileResult(8, 0, 1, 9, 0, List.of()));
        assertThat(trade(sample.get(0)).get("missing_since")).isNull();
    }

    @Test
    void 새_missing이_최소_건수_미만이면_비율과_관계없이_표시한다() {
        List<TradeRecord> three = load(HARRINGTON).subList(0, 3);
        reconcileService.save("11710", "202609", three);

        // 기존 3행 → 응답 2건: 33%지만 1건이라 표시
        ReconcileResult result = reconcileService.save("11710", "202609", three.subList(0, 2));

        assertThat(result.missing()).isEqualTo(1);
        assertThat(result.warnings()).isEmpty();
        assertThat(missingCount()).isEqualTo(1);
    }

    @Test
    void 새_missing이_최소_건수_이상이고_비율을_넘으면_표시를_보류하고_나머지는_반영한다() {
        List<TradeRecord> ten = load(HARRINGTON).subList(0, 10);
        reconcileService.save("11710", "202609", ten.stream().map(TradeFixtures::unregistered).toList());

        // 기존 10행 → 응답 4건(등기됨): 6건(60%)이 사라짐
        ReconcileResult result = reconcileService.save("11710", "202609", ten.subList(0, 4));

        assertThat(result).isEqualTo(new ReconcileResult(1, 0, 4, 0, 6,
                List.of(ReconcileResult.MISSING_RATIO_EXCEEDED)));
        assertThat(missingCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apt_trade WHERE registered_date IS NOT NULL",
                Integer.class)).isEqualTo(4);
    }

    @Test
    void 새_missing_비율이_한도와_같으면_표시한다() {
        List<TradeRecord> rows = load(HARRINGTON).subList(0, 25);
        reconcileService.save("11710", "202609", rows);

        // 기존 25행 → 응답 20건: 5건(20%)은 한도를 넘지 않음
        ReconcileResult result = reconcileService.save("11710", "202609", rows.subList(0, 20));

        assertThat(result.missing()).isEqualTo(5);
        assertThat(result.warnings()).isEmpty();
        assertThat(missingCount()).isEqualTo(5);
    }

    @Test
    void 빈_응답이면_아무것도_바꾸지_않는다() {
        save(sample);

        ReconcileResult result = save(List.of());

        assertThat(result).isEqualTo(ReconcileResult.EMPTY);
        assertThat(count("apt_trade")).isEqualTo(10);
        assertThat(missingCount()).isZero();
    }

    @Test
    void 요청_범위_밖_레코드가_있으면_저장하지_않는다() {
        assertThatThrownBy(() -> reconcileService.save("11500", "202608", sample))
                .isInstanceOf(TradeApiException.class);
        assertThat(count("apt_trade")).isZero();
    }

    @Test
    void 같은_구월_저장은_앞선_트랜잭션이_끝날_때까지_기다린다() throws Exception {
        List<TradeRecord> harrington = load(HARRINGTON);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // 다른 수집이 같은 구×월 lock을 잡고 있는 상황
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                repository.lockMonth("11710", "202609");
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ReconcileResult> saving = executor.submit(() -> reconcileService.save("11710", "202609", harrington));
            Thread.sleep(500);
            assertThat(saving).isNotDone();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(saving.get(30, TimeUnit.SECONDS).inserted()).isEqualTo(170);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ReconcileResult save(List<TradeRecord> records) {
        return reconcileService.save(SAMPLE_SGG, SAMPLE_YM, records);
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

    private int missingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM apt_trade WHERE missing_since IS NOT NULL", Integer.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
