package com.dreamapt.complex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dreamapt.common.BadRequestException;
import com.dreamapt.common.MonthlySeries;
import com.dreamapt.common.NotFoundException;
import com.dreamapt.common.PageResponse;
import com.dreamapt.support.TestcontainersConfiguration;
import com.dreamapt.support.TradeRows;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ComplexQueryServiceIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 기준일 2026-10-25 (서울). 집계 중 = 2026-09, 2026-10 */
    private static final Clock CLOCK = Clock.fixed(ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, SEOUL).toInstant(), SEOUL);

    private static final String HELIO = "11710-8865";
    private static final String HYUNDAI_A = "11710-1";
    private static final String HYUNDAI_B = "11500-1";

    @Autowired
    ComplexQueryRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    TradeRows rows;
    ComplexQueryService service;

    @BeforeEach
    void setUp() {
        rows = new TradeRows(jdbc);
        rows.clear();
        rows.complex(HELIO, "헬리오시티", "11710");
        service = new ComplexQueryService(repository, CLOCK);
    }

    @Nested
    class 월별_가격 {

        @Test
        void 짝수_건이면_가운데_두_값의_평균을_HALF_UP으로_반올림한다() {
            // 평균 100,002.5. ROUND(double)이면 짝수 쪽인 100,002가 된다
            rows.trade(HELIO, LocalDate.of(2026, 8, 3)).amount(100_000).insert();
            rows.trade(HELIO, LocalDate.of(2026, 8, 20)).amount(100_005).insert();

            assertThat(monthly(null, null).months()).singleElement()
                    .isEqualTo(new MonthlyPrice(YearMonth.of(2026, 8), 100_003, 2, false));
        }

        @Test
        void 홀수_건이면_가운데_값_그대로다() {
            rows.trade(HELIO, LocalDate.of(2026, 8, 3)).amount(100_000).insert();
            rows.trade(HELIO, LocalDate.of(2026, 8, 10)).amount(120_000).insert();
            rows.trade(HELIO, LocalDate.of(2026, 8, 20)).amount(100_005).insert();

            assertThat(monthly(null, null).months()).singleElement()
                    .isEqualTo(new MonthlyPrice(YearMonth.of(2026, 8), 100_005, 3, false));
        }

        @Test
        void 일괄_거래는_중앙값과_건수에서_빠진다() {
            rows.trades(HELIO, LocalDate.of(2026, 7, 10), 10, 50_000);
            rows.trade(HELIO, LocalDate.of(2026, 7, 20)).amount(100_000).insert();

            assertThat(monthly(null, null).months()).singleElement()
                    .isEqualTo(new MonthlyPrice(YearMonth.of(2026, 7), 100_000, 1, false));
        }

        @Test
        void 기준일이_속한_달과_그_전_달은_집계_중이다() {
            rows.trade(HELIO, LocalDate.of(2026, 8, 31)).insert();
            rows.trade(HELIO, LocalDate.of(2026, 9, 1)).insert();
            rows.trade(HELIO, LocalDate.of(2026, 10, 20)).insert();

            MonthlySeries<MonthlyPrice> series = monthly(null, null);

            assertThat(series.baseDate()).isEqualTo(LocalDate.of(2026, 10, 25));
            assertThat(series.provisionalFrom()).isEqualTo(YearMonth.of(2026, 9));
            assertThat(series.months()).extracting(MonthlyPrice::month, MonthlyPrice::provisional).containsExactly(
                    tuple(YearMonth.of(2026, 8), false),
                    tuple(YearMonth.of(2026, 9), true),
                    tuple(YearMonth.of(2026, 10), true));
        }

        @Test
        void from과_to는_양쪽_달을_포함한다() {
            rows.trade(HELIO, LocalDate.of(2026, 6, 30)).insert();
            rows.trade(HELIO, LocalDate.of(2026, 7, 1)).insert();
            rows.trade(HELIO, LocalDate.of(2026, 8, 31)).insert();
            rows.trade(HELIO, LocalDate.of(2026, 9, 1)).insert();

            assertThat(monthly(YearMonth.of(2026, 7), YearMonth.of(2026, 8)).months())
                    .extracting(MonthlyPrice::month)
                    .containsExactly(YearMonth.of(2026, 7), YearMonth.of(2026, 8));
        }

        @Test
        void 다른_면적_그룹과_해제된_거래는_섞이지_않는다() {
            rows.trade(HELIO, LocalDate.of(2026, 8, 3)).amount(100_000).insert();
            rows.trade(HELIO, LocalDate.of(2026, 8, 3)).amount(200_000).area("84.49").insert();
            rows.trade(HELIO, LocalDate.of(2026, 8, 3)).amount(300_000).cancelled().insert();

            assertThat(monthly(null, null).months()).singleElement()
                    .isEqualTo(new MonthlyPrice(YearMonth.of(2026, 8), 100_000, 1, false));
        }

        @Test
        void from이_to보다_늦으면_잘못된_요청이다() {
            rows.trade(HELIO, LocalDate.of(2026, 8, 3)).insert();

            assertThatThrownBy(() -> monthly(YearMonth.of(2026, 9), YearMonth.of(2026, 8)))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void 없는_단지나_면적_그룹이면_찾을_수_없다() {
            rows.trade(HELIO, LocalDate.of(2026, 8, 3)).insert();

            assertThatThrownBy(() -> service.getMonthlyPrices("99999-1", 85, null, null))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> service.getMonthlyPrices(HELIO, 60, null, null))
                    .isInstanceOf(NotFoundException.class);
        }

        private MonthlySeries<MonthlyPrice> monthly(YearMonth from, YearMonth to) {
            return service.getMonthlyPrices(HELIO, 85, from, to);
        }
    }

    @Nested
    class 단지_상세 {

        @Test
        void 마지막_거래가_일괄_거래면_그_전_일반_거래가_마지막_거래다() {
            rows.trade(HELIO, LocalDate.of(2026, 8, 1)).amount(90_000).insert();
            rows.trades(HELIO, LocalDate.of(2026, 9, 18), 10, 70_000);

            AreaGroupSummary area = service.getDetail(HELIO).areaGroups().get(0);

            assertThat(area.tradeCount()).isEqualTo(1);
            assertThat(area.bulkCount()).isEqualTo(10);
            assertThat(area.lastDealDate()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(area.lastDealAmount()).isEqualTo(90_000);
        }

        @Test
        void 마지막_계약일에_여러_건이면_늦게_수집된_거래다() {
            rows.trade(HELIO, LocalDate.of(2026, 8, 1)).amount(90_000).insert();
            rows.trade(HELIO, LocalDate.of(2026, 8, 1)).amount(95_000).insert();

            assertThat(service.getDetail(HELIO).areaGroups().get(0).lastDealAmount()).isEqualTo(95_000);
        }

        @Test
        void 면적_그룹은_원값을_모으고_일괄_거래만_있는_그룹도_보여준다() {
            rows.trade(HELIO, LocalDate.of(2026, 8, 1)).area("84.95").insert();
            rows.trade(HELIO, LocalDate.of(2026, 8, 2)).area("84.99").insert();
            rows.trade(HELIO, LocalDate.of(2026, 8, 2)).area("84.99").insert();
            rows.trade(HELIO, LocalDate.of(2026, 8, 3)).area("59.96").cancelled().insert();
            for (int i = 0; i < 10; i++) {
                rows.trade(HELIO, LocalDate.of(2026, 9, 18)).area("39.12").insert();
            }

            assertThat(service.getDetail(HELIO).areaGroups())
                    .extracting(AreaGroupSummary::areaGroup, AreaGroupSummary::exclusiveAreas,
                            AreaGroupSummary::tradeCount, AreaGroupSummary::bulkCount,
                            AreaGroupSummary::lastDealAmount)
                    .containsExactly(
                            tuple(39, List.of(new BigDecimal("39.12")), 0, 10, null),
                            tuple(85, List.of(new BigDecimal("84.95"), new BigDecimal("84.99")), 3, 0,
                                    100_000));
        }

        @Test
        void 일괄_거래가_80퍼센트_이상이면_일괄_거래_위주_단지다() {
            rows.trades(HELIO, LocalDate.of(2026, 9, 18), 10, 70_000);
            rows.trade(HELIO, LocalDate.of(2026, 8, 1)).insert();
            rows.trade(HELIO, LocalDate.of(2026, 8, 2)).insert();

            ComplexDetail detail = service.getDetail(HELIO);

            assertThat(detail.tradeCount()).isEqualTo(2);
            assertThat(detail.bulkCount()).isEqualTo(10);
            assertThat(detail.bulkRatio()).isCloseTo(10.0 / 12, offset(1e-9));
            assertThat(detail.bulkDominant()).isTrue();
        }

        @Test
        void 일괄_거래가_80퍼센트_미만이면_일괄_거래_위주가_아니다() {
            rows.trades(HELIO, LocalDate.of(2026, 9, 18), 10, 70_000);
            rows.trades(HELIO, LocalDate.of(2026, 8, 1), 3, 100_000);

            assertThat(service.getDetail(HELIO).bulkDominant()).isFalse();
        }

        @Test
        void 단지_정보와_구_이름을_준다() {
            ComplexDetail detail = service.getDetail(HELIO);

            assertThat(detail).extracting(ComplexDetail::name, ComplexDetail::sggName, ComplexDetail::umdName,
                    ComplexDetail::buildYear, ComplexDetail::tradeCount, ComplexDetail::bulkDominant)
                    .containsExactly("헬리오시티", "송파구", "가락동", 2018, 0, false);
            assertThat(detail.areaGroups()).isEmpty();
        }

        @Test
        void 없는_단지면_찾을_수_없다() {
            assertThatThrownBy(() -> service.getDetail("99999-1")).isInstanceOf(NotFoundException.class);
        }

        @Test
        void 좌표가_있으면_위도_경도를_주고_없으면_null이다() {
            assertThat(service.getDetail(HELIO)).extracting(ComplexDetail::lat, ComplexDetail::lng)
                    .containsExactly(null, null);

            rows.location(HELIO, CENTER_LAT, CENTER_LNG);

            assertThat(service.getDetail(HELIO)).extracting(ComplexDetail::lat, ComplexDetail::lng)
                    .containsExactly(CENTER_LAT, CENTER_LNG);
        }
    }

    /** 반경 검색 중심 (헬리오시티 주출입구 근처) */
    private static final double CENTER_LAT = 37.499077;
    private static final double CENTER_LNG = 127.112296;

    @Nested
    class 반경_검색 {

        @BeforeEach
        void setUp() {
            // HELIO는 중심. 거리는 ST_Project로 정확히 둔다. 반경 1000m 경계는 990m·1010m로 여유를 둔다
            rows.location(HELIO, CENTER_LAT, CENTER_LNG);
            complexAt("11710-2", 500, 90);
            complexAt("11710-3", 990, 0);
            complexAt("11710-4", 1010, 180);
            rows.complex("11710-5", "좌표없음", "11710"); // 좌표 없는 단지는 빠진다
        }

        @Test
        void 반경_안_단지를_가까운_순으로_거리와_함께_준다() {
            NearbyComplexes result = service.nearby(CENTER_LAT, CENTER_LNG, 1000, 300);

            assertThat(result.items()).extracting(NearbyComplex::aptSeq, NearbyComplex::distanceM)
                    .containsExactly(tuple(HELIO, 0), tuple("11710-2", 500), tuple("11710-3", 990));
            assertThat(result.totalCount()).isEqualTo(3);
            assertThat(result).extracting(NearbyComplexes::lat, NearbyComplexes::lng, NearbyComplexes::radius)
                    .containsExactly(CENTER_LAT, CENTER_LNG, 1000);
            assertThat(result.items().get(0)).extracting(NearbyComplex::name, NearbyComplex::sggName,
                    NearbyComplex::lat, NearbyComplex::lng)
                    .containsExactly("헬리오시티", "송파구", CENTER_LAT, CENTER_LNG);
        }

        @Test
        void 반경을_넓히면_경계_밖_단지도_들어온다() {
            assertThat(service.nearby(CENTER_LAT, CENTER_LNG, 1100, 300).items())
                    .extracting(NearbyComplex::aptSeq).contains("11710-4");
        }

        @Test
        void limit으로_자르고_totalCount는_자르기_전_전체_수다() {
            NearbyComplexes result = service.nearby(CENTER_LAT, CENTER_LNG, 1000, 2);

            assertThat(result.items()).extracting(NearbyComplex::aptSeq).containsExactly(HELIO, "11710-2");
            assertThat(result.totalCount()).isEqualTo(3);
        }

        @Test
        void 같은_거리면_단지_ID_순이다() {
            complexAt("11710-7", 300, 270);
            complexAt("11710-6", 300, 0);

            assertThat(service.nearby(CENTER_LAT, CENTER_LNG, 400, 300).items())
                    .extracting(NearbyComplex::aptSeq).containsExactly(HELIO, "11710-6", "11710-7");
        }

        @Test
        void 최근_거래_수는_기준일_전_12개월의_일반_거래만_센다() {
            rows.trade(HELIO, LocalDate.of(2026, 9, 1)).insert();
            rows.trade(HELIO, LocalDate.of(2025, 11, 1)).insert();
            rows.trade(HELIO, LocalDate.of(2025, 10, 1)).insert(); // 12개월 전보다 이르다
            rows.trades(HELIO, LocalDate.of(2026, 8, 5), 10, 50_000); // 일괄 거래
            rows.trade(HELIO, LocalDate.of(2026, 8, 6)).cancelled().insert();

            assertThat(service.nearby(CENTER_LAT, CENTER_LNG, 100, 300).items()).singleElement()
                    .extracting(NearbyComplex::recentTradeCount).isEqualTo(2);
        }

        @Test
        void 반경_안에_단지가_없으면_빈_목록과_0이다() {
            NearbyComplexes result = service.nearby(37.6, 126.9, 1000, 300);

            assertThat(result.items()).isEmpty();
            assertThat(result.totalCount()).isZero();
        }

        private void complexAt(String aptSeq, double meters, double azimuth) {
            rows.complex(aptSeq, "단지" + aptSeq, "11710");
            rows.locationFrom(aptSeq, CENTER_LAT, CENTER_LNG, meters, azimuth);
        }
    }

    @Nested
    class 검색 {

        @BeforeEach
        void setUp() {
            rows.complex(HYUNDAI_A, "현대", "11710", "문정동", 1990);
            rows.complex(HYUNDAI_B, "현대", "11500", "염창동", 1994);
        }

        @Test
        void 띄어쓰기가_달라도_찾는다() {
            assertThat(service.search("헬리오 시티", null, 20)).extracting(ComplexSearchItem::aptSeq)
                    .containsExactly(HELIO);
        }

        @Test
        void 한_글자로도_찾는다() {
            assertThat(service.search("현", null, 20)).extracting(ComplexSearchItem::aptSeq)
                    .containsExactlyInAnyOrder(HYUNDAI_A, HYUNDAI_B);
        }

        @Test
        void 같은_이름이면_최근_일반_거래가_많은_단지가_먼저고_일괄_거래는_세지_않는다() {
            rows.trades(HYUNDAI_A, LocalDate.of(2026, 9, 18), 10, 50_000);
            rows.trade(HYUNDAI_B, LocalDate.of(2026, 8, 1)).insert();
            rows.trade(HYUNDAI_B, LocalDate.of(2026, 8, 2)).insert();

            assertThat(service.search("현대", null, 20))
                    .extracting(ComplexSearchItem::aptSeq, ComplexSearchItem::recentTradeCount,
                            ComplexSearchItem::sggName, ComplexSearchItem::umdName, ComplexSearchItem::buildYear)
                    .containsExactly(
                            tuple(HYUNDAI_B, 2, "강서구", "염창동", 1994),
                            tuple(HYUNDAI_A, 0, "송파구", "문정동", 1990));
        }

        @Test
        void 최근_거래_수는_기준일_전_12개월만_센다() {
            rows.trade(HYUNDAI_A, LocalDate.of(2025, 10, 25)).insert();
            rows.trade(HYUNDAI_A, LocalDate.of(2025, 10, 26)).insert();

            assertThat(service.search("현대", "11710", 20)).singleElement()
                    .extracting(ComplexSearchItem::recentTradeCount).isEqualTo(1);
        }

        @Test
        void 구로_거른다() {
            assertThat(service.search("현대", "11500", 20)).extracting(ComplexSearchItem::aptSeq)
                    .containsExactly(HYUNDAI_B);
        }

        @Test
        void LIKE_와일드카드는_글자_그대로_찾는다() {
            assertThat(service.search("%", null, 20)).isEmpty();
            assertThat(service.search("_", null, 20)).isEmpty();
        }

        @Test
        void 개수를_제한한다() {
            assertThat(service.search("현대", null, 1)).hasSize(1);
        }
    }

    @Nested
    class 거래_목록 {

        @Test
        void 일괄_거래도_표시해서_최신순으로_준다() {
            long old = rows.trade(HELIO, LocalDate.of(2026, 8, 1)).insert();
            rows.trades(HELIO, LocalDate.of(2026, 9, 18), 10, 70_000);
            long cancelled = rows.trade(HELIO, LocalDate.of(2026, 9, 20)).cancelled().insert();

            PageResponse<TradeItem> page = service.getTrades(HELIO, null, 0, 20);

            assertThat(page.totalElements()).isEqualTo(11);
            assertThat(page.content()).hasSize(11);
            assertThat(page.content()).extracting(TradeItem::id).doesNotContain(cancelled).last().isEqualTo(old);
            assertThat(page.content().subList(0, 10)).allSatisfy(t -> assertThat(t.bulk()).isTrue());
            assertThat(page.content().get(10).bulk()).isFalse();
        }

        @Test
        void 같은_날이면_늦게_수집된_거래가_먼저다() {
            long first = rows.trade(HELIO, LocalDate.of(2026, 8, 1)).insert();
            long second = rows.trade(HELIO, LocalDate.of(2026, 8, 1)).insert();

            assertThat(service.getTrades(HELIO, null, 0, 20).content()).extracting(TradeItem::id)
                    .containsExactly(second, first);
        }

        @Test
        void 페이지로_나눈다() {
            for (int day = 1; day <= 5; day++) {
                rows.trade(HELIO, LocalDate.of(2026, 8, day)).insert();
            }

            PageResponse<TradeItem> page = service.getTrades(HELIO, null, 1, 2);

            assertThat(page.content()).extracting(TradeItem::dealDate)
                    .containsExactly(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 2));
            assertThat(page).extracting(PageResponse::page, PageResponse::size, PageResponse::totalElements,
                    PageResponse::totalPages).containsExactly(1, 2, 5L, 3);
        }

        @Test
        void 면적_그룹으로_거르고_없는_면적_그룹이면_빈_목록이다() {
            rows.trade(HELIO, LocalDate.of(2026, 8, 1)).area("84.99").insert();
            rows.trade(HELIO, LocalDate.of(2026, 8, 2)).area("59.96").insert();

            assertThat(service.getTrades(HELIO, 60, 0, 20).content()).extracting(TradeItem::exclusiveArea)
                    .containsExactly(new BigDecimal("59.96"));
            assertThat(service.getTrades(HELIO, 102, 0, 20).totalElements()).isZero();
        }

        @Test
        void 없는_단지면_찾을_수_없다() {
            assertThatThrownBy(() -> service.getTrades("99999-1", null, 0, 20))
                    .isInstanceOf(NotFoundException.class);
        }
    }
}
