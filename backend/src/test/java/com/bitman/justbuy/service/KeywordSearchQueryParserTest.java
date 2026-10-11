package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeywordSearchQueryParserTest {
    @org.junit.jupiter.api.Test void explicitResearchFiltersPreserveNumericPrecisionAndDoNotUseVagueConcepts() {
        var spec = new KeywordSearchQueryParser().parse("PER 15.5배 이하 PBR 1.2 이하 이평선 정배열 20일 돌파 거래량 20일 평균 대비 2배 이상");
        org.assertj.core.api.Assertions.assertThat(spec.unsupportedTerms()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(spec.filters()).extracting(KeywordSearchQueryParser.Filter::field)
            .containsExactly("PER", "PBR", "MA_ALIGNED", "BREAKOUT20", "VOLUME_RATIO20");
        org.assertj.core.api.Assertions.assertThat(spec.filters().getFirst().threshold()).isEqualByComparingTo("15.5");
    }

    @org.junit.jupiter.api.Test void researchConditionsRejectUnknownWindowsAndAmbiguousOrNegativeRatios() {
        var parser = new KeywordSearchQueryParser();
        for (String query : java.util.List.of("60일 돌파", "PER -1배 이하", "PBR 1% 이하", "거래량 10일 평균 대비 2배 이상", "저평가 정배열"))
            org.assertj.core.api.Assertions.assertThat(parser.parse(query).unsupportedTerms()).as(query).isNotEmpty();
    }

    @org.junit.jupiter.api.Test void researchThresholdContradictionsAreRejected() {
        org.assertj.core.api.Assertions.assertThat(new KeywordSearchQueryParser().parse("PER 20배 이상 PER 10배 이하").unsupportedTerms()).isNotEmpty();
    }

    private final KeywordSearchQueryParser parser = new KeywordSearchQueryParser();

    @ParameterizedTest
    @CsvSource(value = {
        "현재가 10,000원 이하|PRICE|LTE|10000",
        "주가 1.5만원 미만|PRICE|LT|15000",
        "가격 1000원 이상|PRICE|GTE|1000",
        "2만원 초과|PRICE|GT|20000",
        "상승률 5.21% 이상|CHANGE_PCT|GTE|5.21",
        "등락률 -3% 이하|CHANGE_PCT|LTE|-3",
        "거래량 2.5만주 이상|VOLUME|GTE|25000",
        "거래량 1억주 초과|VOLUME|GT|100000000",
        "거래량 3,000주 미만|VOLUME|LT|3000",
        "거래대금 100억원 이상|TRADING_VALUE|GTE|10000000000",
        "거래대금 300만원 이하|TRADING_VALUE|LTE|3000000",
        "거래대금 100000원 초과|TRADING_VALUE|GT|100000"
    }, delimiter = '|')
    void explicitNumericConditionPreservesFieldUnitAndOperator(String query, String field,
                                                              String operator, String threshold) {
        var spec = parser.parse(query);
        assertThat(spec.unsupportedTerms()).isEmpty();
        assertThat(spec.filters()).hasSize(1);
        assertThat(spec.filters().getFirst().field()).isEqualTo(field);
        assertThat(spec.filters().getFirst().operator()).isEqualTo(operator);
        assertThat(spec.filters().getFirst().threshold()).isEqualByComparingTo(threshold);
        assertThat(spec.filters().getFirst().label()).isNotBlank();
        assertThat(spec.nameContains()).isNull();
    }

    @Test
    void combinesMarketCompanyNameAndAllNumericConditionsWithoutGuessing() {
        var spec = parser.parse("코스피 삼성전자 주가 10만원 이하 거래대금 100억원 이상 종목 검색");
        assertThat(spec.market()).isEqualTo("KOSPI");
        assertThat(spec.nameContains()).isEqualTo("삼성전자");
        assertThat(spec.filters()).extracting(KeywordSearchQueryParser.Filter::field)
            .containsExactly("PRICE", "TRADING_VALUE");
        assertThat(spec.appliedConditions()).anyMatch(label -> label.contains("종목명에") && label.contains("삼성전자"));
        assertThat(spec.unsupportedTerms()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {"코스닥|KOSDAQ", "KOSDAQ|KOSDAQ", "kospi|KOSPI"}, delimiter = '|')
    void marketAliasesDoNotBecomeLiteralCompanyNames(String query, String market) {
        var spec = parser.parse(query + " 종목 검색");
        assertThat(spec.market()).isEqualTo(market);
        assertThat(spec.nameContains()).isNull();
        assertThat(spec.unsupportedTerms()).isEmpty();
    }

    @Test
    void directSixDigitCodeDoesNotGetConsumedByAmountOrWindowParser() {
        var spec = parser.parse("005930 최근 5거래일 외국인 순매수 종목");
        assertThat(spec.stockCode()).isEqualTo("005930");
        assertThat(spec.flowDays()).isEqualTo(5);
        assertThat(spec.filters()).hasSize(1);
        assertThat(spec.filters().getFirst().field()).isEqualTo("FOREIGN_NET");
        assertThat(spec.filters().getFirst().threshold()).isEqualByComparingTo("0");
        assertThat(spec.nameContains()).isNull();
        assertThat(spec.unsupportedTerms()).isEmpty();
    }

    @Test
    void todayDualNetBuyMeansBothPositiveOnOneActualTradingDay() {
        var spec = parser.parse("오늘 외국인 기관 쌍끌이 순매수 종목 검색");
        assertThat(spec.todayOnly()).isTrue();
        assertThat(spec.flowDays()).isEqualTo(1);
        assertThat(spec.filters()).extracting(KeywordSearchQueryParser.Filter::field)
            .containsExactly("FOREIGN_NET", "INSTITUTION_NET");
        assertThat(spec.filters()).allSatisfy(filter -> {
            assertThat(filter.operator()).isEqualTo("GT");
            assertThat(filter.threshold()).isEqualByComparingTo("0");
        });
        assertThat(spec.nameContains()).isNull();
        assertThat(spec.unsupportedTerms()).isEmpty();
    }

    @Test
    void jointlyNamedInvestorsShareExplicitNetBuyCondition() {
        var spec = parser.parse("최근 3거래일 외국인과 기관이 순매수한 종목");
        assertThat(spec.filters()).extracting(KeywordSearchQueryParser.Filter::field)
            .containsExactly("FOREIGN_NET", "INSTITUTION_NET");
        assertThat(spec.nameContains()).isNull();
        assertThat(spec.unsupportedTerms()).isEmpty();
    }

    @Test
    void omittedWindowHasExplicitActualObservationDefault() {
        var spec = parser.parse("외국인 순매수");
        assertThat(spec.flowDays()).isEqualTo(3);
        assertThat(spec.todayOnly()).isFalse();
        assertThat(spec.appliedConditions()).anyMatch(label -> label.contains("3") && label.contains("거래일"));
    }

    @ParameterizedTest
    @CsvSource(value = {
        "거래량 상위 5종목|VOLUME|5",
        "거래대금 TOP 30|TRADING_VALUE|20",
        "상승률 상위 10|CHANGE_PCT|10",
        "등락률 top 2 종목|CHANGE_PCT|2"
    }, delimiter = '|')
    void explicitRankingUsesRequestedMeasuredFieldWithBoundedResultLimit(String query, String sort, int limit) {
        var spec = parser.parse(query);
        assertThat(spec.sortBy()).isEqualTo(sort);
        assertThat(spec.descending()).isTrue();
        assertThat(spec.rankingExplicit()).isTrue();
        assertThat(spec.limit()).isEqualTo(limit);
        assertThat(spec.nameContains()).isNull();
        assertThat(spec.unsupportedTerms()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {"상승 종목|GT", "하락 종목|LT"}, delimiter = '|')
    void riseAndFallUseOnlyZeroPercentBoundary(String query, String operator) {
        var spec = parser.parse(query);
        assertThat(spec.filters()).hasSize(1);
        assertThat(spec.filters().getFirst().field()).isEqualTo("CHANGE_PCT");
        assertThat(spec.filters().getFirst().operator()).isEqualTo(operator);
        assertThat(spec.filters().getFirst().threshold()).isEqualByComparingTo("0");
        assertThat(spec.unsupportedTerms()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {"상승종목|GT", "하락한 종목|LT", "상승하는종목|GT"}, delimiter = '|')
    void adjacentKoreanDirectionWordsCannotBecomeLiteralCompanyNames(String query, String operator) {
        var spec = parser.parse(query);
        assertThat(spec.unsupportedTerms()).isEmpty();
        assertThat(spec.nameContains()).isNull();
        assertThat(spec.filters()).hasSize(1);
        assertThat(spec.filters().getFirst().field()).isEqualTo("CHANGE_PCT");
        assertThat(spec.filters().getFirst().operator()).isEqualTo(operator);
        assertThat(spec.filters().getFirst().threshold()).isEqualByComparingTo("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "신고가 종목", "저평가 종목", "공시 호재", "뉴스 관련주", "반도체 테마", "거래량 급증",
        "전고점 돌파", "코스피 제외", "상승 아닌 종목", "기관 순매도", "삼성전자 또는 현대차"
    })
    void knownUnsupportedConceptsNeverBecomeCompanyNameOrInventedThreshold(String query) {
        var spec = parser.parse(query);
        assertThat(spec.unsupportedTerms()).isNotEmpty();
        assertThat(spec.nameContains()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "가격 10% 이상", "거래량 1만원 이상", "상승률 100원 이상", "거래대금 10주 이상",
        "거래량 10 이상", "주가 1e5원 이상", "가격 -100원 이하", "가격 1.5원 이하",
        "거래대금 1000000000000000001원 이상", "거래량 12,34주 이상", "가격 10원 이상하게"
    })
    void ambiguousOrInvalidNumericConditionCannotSilentlyBecomeACompanySearch(String query) {
        var spec = parser.parse(query);
        assertThat(spec.unsupportedTerms()).isNotEmpty();
        assertThat(spec.nameContains()).isNull();
        assertThat(spec.filters()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "가격 2만원 이상 가격 1만원 이하", "상승률 5% 초과 등락률 5% 이하",
        "거래량 100주 초과 거래량 100주 미만", "거래량 1만주 이하 거래량 2만주 이상"
    })
    void impossibleAndRangesAreExplicitlyRejected(String query) {
        assertThat(parser.parse(query).unsupportedTerms()).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"최근 0거래일 기관 순매수", "최근 11거래일 기관 순매수", "오늘 최근 3거래일 쌍끌이"})
    void contradictoryOrUnboundedTradingWindowsAreUnsupported(String query) {
        assertThat(parser.parse(query).unsupportedTerms()).isNotEmpty();
    }

    @Test
    void conflictingMarketsAndStockCodesAreUnsupportedInsteadOfChoosingOne() {
        assertThat(parser.parse("코스피 코스닥 종목").unsupportedTerms()).isNotEmpty();
        assertThat(parser.parse("005930 000660").unsupportedTerms()).isNotEmpty();
    }

    @Test
    void literalCompanyNameIsLabelledAndCannotExecuteAnUnlabelledGuess() {
        var spec = parser.parse("현대자동차");
        assertThat(spec.nameContains()).isEqualTo("현대자동차");
        assertThat(spec.appliedConditions()).contains("종목명에 현대자동차 포함");
        assertThat(spec.sortBy()).isEqualTo("TRADING_VALUE");
        assertThat(spec.rankingExplicit()).isFalse();
        assertThat(spec.limit()).isEqualTo(10);
        assertThat(spec.unsupportedTerms()).isEmpty();
    }

    @Test
    void abbreviatedWonTradingValueHasTheSameMeasuredThresholdAsExplicitWon() {
        var spec = parser.parse("거래대금 100억 이상");
        assertThat(spec.unsupportedTerms()).isEmpty();
        assertThat(spec.filters()).hasSize(1);
        assertThat(spec.filters().getFirst().field()).isEqualTo("TRADING_VALUE");
        assertThat(spec.filters().getFirst().threshold()).isEqualByComparingTo("10000000000");
        assertThat(spec.nameContains()).isNull();
    }

    @Test
    void numericCharactersInAnActualLiteralCompanyNameAreNotAnUnlabelledNumericFilter() {
        var spec = parser.parse("3S");
        assertThat(spec.unsupportedTerms()).isEmpty();
        assertThat(spec.nameContains()).isEqualTo("3S");
        assertThat(spec.appliedConditions()).contains("종목명에 3S 포함");
        assertThat(spec.filters()).isEmpty();
    }

    @Test
    void defaultLimitForDirectOrLiteralLookupDoesNotClaimAnExecutedMeasuredRanking() {
        for (String query : List.of("005930", "삼성전자")) {
            var spec = parser.parse(query);
            assertThat(spec.rankingExplicit()).isFalse();
            assertThat(spec.appliedConditions()).contains("결과 최대 10종목");
            assertThat(spec.appliedConditions()).noneMatch(condition -> condition.contains("거래대금 상위"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"5만원", "거래대금 100억", "3%", "123", "최근 3거래일 거래량 1만주 이상", "외국인 순매수 100주 이상"})
    void missingNumericOperatorsAndUnsupportedTimeAggregationsNeverBecomeLiteralCompanyNames(String query) {
        var spec = parser.parse(query);
        assertThat(spec.unsupportedTerms()).isNotEmpty();
        assertThat(spec.nameContains()).isNull();
    }

    @Test
    void blankNullAndOversizedQueriesAreUnsupportedAndNoCompanySearchIsCreated() {
        for (String query : new String[]{null, "  ", "가".repeat(201)}) {
            var spec = parser.parse(query);
            assertThat(spec.unsupportedTerms()).isNotEmpty();
            assertThat(spec.nameContains()).isNull();
            assertThat(spec.filters()).isEmpty();
        }
    }

    @Test
    void parsedAndExternallyConstructedSpecsProtectTheirConditionLists() {
        var spec = parser.parse("가격 1000원 이하");
        assertThatThrownBy(() -> spec.filters().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.appliedConditions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> spec.unsupportedTerms().add("x")).isInstanceOf(UnsupportedOperationException.class);
        var mutable = new ArrayList<>(List.of(new KeywordSearchQueryParser.Filter("PRICE", "LTE", BigDecimal.TEN, "가격")));
        var constructed = new KeywordSearchQueryParser.Spec(mutable, null, null, null, "PRICE", true, false, 1, 3, false,
            List.of(), List.of());
        mutable.clear();
        assertThat(constructed.filters()).hasSize(1);
    }
}
