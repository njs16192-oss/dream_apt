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

    // 단지 정보 갱신 규칙 (ADR 003): 더 늦은 계약일의 거래만 단지 정보를 덮는다.
    // 원본 거래(sample[0])의 계약일은 2026-09-20이고, save()는 2026-09 한 달을 대조하므로 날짜는 그 달 안에서 바꾼다
    private static final LocalDate EARLIER = LocalDate.of(2026, 9, 5);
    private static final LocalDate LATER = LocalDate.of(2026, 9, 25);

    @Test
    void 더_늦은_거래의_단지_정보로_갱신된다() {
        TradeRecord original = sample.get(0);
        save(List.of(original));

        save(List.of(complexInfo(original, "가양2단지성지아파트", "999-9", "새도로명 1", (short) 2001, LATER)));

        assertThat(complexRow(original.aptSeq()))
                .containsEntry("name", "가양2단지성지아파트")
                .containsEntry("jibun", "999-9")
                .containsEntry("road_address", "새도로명 1")
                .containsEntry("build_year", 2001) // SMALLINT는 Integer로 읽힌다
                .containsEntry("source_deal_date", java.sql.Date.valueOf(LATER));
        assertThat(count("apt_complex")).isEqualTo(1);
    }

    @Test
    void 더_이른_거래는_단지_정보를_덮지_않는다() {
        TradeRecord original = sample.get(0);
        save(List.of(original));
        Map<String, Object> before = complexRow(original.aptSeq());

        // backfill은 최신 → 과거 순으로 처리하므로 옛 주소가 나중에 들어온다
        save(List.of(complexInfo(original, "옛이름", "1-1", "옛도로명 1", (short) 1980, EARLIER)));

        assertThat(complexRow(original.aptSeq())).isEqualTo(before)
                .containsEntry("source_deal_date", java.sql.Date.valueOf(original.dealDate()));
    }

    @Test
    void 같은_계약일이면_기존_값을_유지한다() {
        TradeRecord original = sample.get(0);
        save(List.of(original));
        Map<String, Object> before = complexRow(original.aptSeq());

        save(List.of(complexInfo(original, "다른표기", original.jibun(), original.roadAddress(), original.buildYear(),
                original.dealDate())));

        assertThat(complexRow(original.aptSeq())).isEqualTo(before);
    }

    @Test
    void 기준_날짜가_없으면_어떤_거래든_덮고_날짜를_채운다() {
        TradeRecord original = sample.get(0);
        save(List.of(original));
        // V9 이전에 저장된 행
        jdbc.update("UPDATE apt_complex SET source_deal_date = NULL WHERE apt_seq = ?", original.aptSeq());

        save(List.of(complexInfo(original, "옛이름", "1-1", "옛도로명 1", (short) 1980, EARLIER)));

        assertThat(complexRow(original.aptSeq()))
                .containsEntry("name", "옛이름")
                .containsEntry("source_deal_date", java.sql.Date.valueOf(EARLIER));
    }

    @Test
    void 한_응답_안에서는_순서와_관계없이_가장_늦은_거래의_단지_정보를_쓴다() {
        TradeRecord original = sample.get(0);
        TradeRecord early = complexInfo(original, "이른거래", original.jibun(), "도로명 1", original.buildYear(), EARLIER);
        TradeRecord late = complexInfo(original, "늦은거래", original.jibun(), "도로명 2", original.buildYear(), LATER);

        save(List.of(late, early));
        assertThat(complexRow(original.aptSeq())).containsEntry("name", "늦은거래");

        jdbc.update("DELETE FROM apt_trade");
        jdbc.update("DELETE FROM apt_complex");
        save(List.of(early, late));
        assertThat(complexRow(original.aptSeq())).containsEntry("name", "늦은거래");
    }

    @Test
    void 값이_같아도_기준_날짜는_앞당겨서_그보다_이른_거래가_덮지_못하게_한다() {
        TradeRecord original = sample.get(0);
        TradeRecord a = complexInfo(original, "A", original.jibun(), original.roadAddress(), original.buildYear(),
                EARLIER);
        save(List.of(a));
        Object updatedAt = complexRow(original.aptSeq()).get("updated_at");

        // 9-25에도 같은 A: 단지 정보는 그대로지만 기준 날짜는 9-25가 되고, 값이 바뀌지 않았으니 updated_at은 그대로다
        save(List.of(complexInfo(original, "A", original.jibun(), original.roadAddress(), original.buildYear(), LATER)));
        assertThat(complexRow(original.aptSeq()))
                .containsEntry("source_deal_date", java.sql.Date.valueOf(LATER))
                .containsEntry("updated_at", updatedAt);

        // 그 사이 날짜(9-20)의 B는 덮지 못한다. 기준 날짜를 앞당기지 않았다면 9-05보다 늦어서 덮었을 것이다
        save(List.of(complexInfo(original, "B", original.jibun(), original.roadAddress(), original.buildYear(),
                original.dealDate())));
        assertThat(complexRow(original.aptSeq())).containsEntry("name", "A");
    }

    @Test
    void 이름만_바뀌면_좌표와_지오코딩_기록을_유지한다() {
        TradeRecord original = sample.get(0);
        save(List.of(original));
        markGeocoded(original.aptSeq());

        save(List.of(complexInfo(original, "가양2단지성지아파트", original.jibun(), original.roadAddress(),
                original.buildYear(), LATER)));

        Map<String, Object> row = geocodeColumns(original.aptSeq());
        assertThat(row.get("has_location")).isEqualTo(true);
        assertThat(row.get("geocode_attempted_at")).isNotNull();
        assertThat(row.get("geocode_error")).isEqualTo("MISMATCH");
    }

    @Test
    void 주소가_바뀌면_좌표와_지오코딩_기록을_지운다() {
        TradeRecord original = sample.get(0);
        save(List.of(original));

        markGeocoded(original.aptSeq());
        save(List.of(complexInfo(original, original.aptNm(), "999-9", original.roadAddress(), original.buildYear(),
                LocalDate.of(2026, 9, 21))));
        assertThat(geocodeColumns(original.aptSeq()))
                .containsEntry("has_location", false)
                .containsEntry("geocode_attempted_at", null)
                .containsEntry("geocode_error", null);

        markGeocoded(original.aptSeq());
        save(List.of(complexInfo(original, original.aptNm(), "999-9", "새도로명 1", original.buildYear(),
                LocalDate.of(2026, 9, 22))));
        assertThat(geocodeColumns(original.aptSeq())).containsEntry("has_location", false);
    }

    @Test
    void 더_이른_거래의_다른_주소는_좌표를_지우지_않는다() {
        TradeRecord original = sample.get(0);
        save(List.of(original));
        markGeocoded(original.aptSeq());

        save(List.of(complexInfo(original, original.aptNm(), "1-1", "옛도로명 1", original.buildYear(), EARLIER)));

        assertThat(geocodeColumns(original.aptSeq())).containsEntry("has_location", true);
    }

    private Map<String, Object> complexRow(String aptSeq) {
        return jdbc.queryForMap("""
                SELECT name, sgg_cd, umd_cd, umd_nm, jibun, road_address, build_year, source_deal_date, updated_at
                FROM apt_complex WHERE apt_seq = ?
                """, aptSeq);
    }

    // 실제로는 성공하면 geocode_error가 NULL이지만, 세 컬럼이 각각 유지·삭제되는지 보려고 모두 채운다
    private void markGeocoded(String aptSeq) {
        jdbc.update("""
                UPDATE apt_complex SET location = ST_SetSRID(ST_MakePoint(127.1, 37.5), 4326),
                    geocode_attempted_at = now(), geocode_error = 'MISMATCH'
                WHERE apt_seq = ?
                """, aptSeq);
    }

    private Map<String, Object> geocodeColumns(String aptSeq) {
        return jdbc.queryForMap("""
                SELECT location IS NOT NULL AS has_location, geocode_attempted_at, geocode_error
                FROM apt_complex WHERE apt_seq = ?
                """, aptSeq);
    }

    /** 단지 정보와 계약일만 바꾼 거래. 계약일이 다르면 다른 거래로 저장된다 */
    private static TradeRecord complexInfo(TradeRecord r, String aptNm, String jibun, String roadAddress,
            Short buildYear, LocalDate dealDate) {
        return new TradeRecord(r.aptSeq(), aptNm, r.sggCd(), r.umdCd(), r.umdNm(), jibun, roadAddress,
                buildYear, dealDate, r.dealAmount(), r.excluUseAr(), r.floor(), r.aptDong(), r.buyerGbn(),
                r.sellerGbn(), r.dealingGbn(), r.agentSggNm(), r.cancelled(), r.cancelDate(), r.registeredDate());
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
