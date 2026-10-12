package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FinancialScorerResearchTest {
    private final FinancialScorer scorer = new FinancialScorer(null);

    @Test
    void disclosureTitlesDoNotInventAFinancialScore() {
        assertThat(scorer.scoreFromText("DART 최근 공시: 자기주식취득, 배당, 합병").score()).isZero();
        assertThat(scorer.scoreFromText("DART 최근 공시: 유상증자, 전환사채").raw()).isEmpty();
    }

    @Test
    void aHeaderWithoutVerifiedNumbersIsUnavailable() {
        assertThat(scorer.scoreFromText("DART 공시 재무 데이터 삼성전자 005930").score()).isZero();
    }

    @Test
    void untrustedOrNegativeRatioTextCannotEarnLowDebtBonus() {
        assertThat(scorer.scoreFromText("부채비율: -35% 영업이익률: 20% ROE: 80%").score()).isZero();
    }
}
