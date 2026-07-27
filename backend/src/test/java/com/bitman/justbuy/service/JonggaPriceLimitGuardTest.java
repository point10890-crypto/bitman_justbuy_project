package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 하루에 나올 수 없는 수익률(가격제한폭 ±30% 초과 등)을 성과 통계에서 걸러내는지 검증한다.
 *
 * <p>실제 사례: 가온전선 진입 385,500 -> 익일 종가 187,543 (-51.35%). 액면분할로 기준이
 * 달라진 값이며, 이를 성과로 세면 승률·평균수익률이 통째로 왜곡된다.
 */
class JonggaPriceLimitGuardTest {

    @Test
    void normalDailyMovesAreMeasurable() {
        assertThat(JonggaPerformanceService.isPriceLimitViolation(2.03, 6.85)).isFalse();
        assertThat(JonggaPerformanceService.isPriceLimitViolation(-13.51, 2.49)).isFalse();
        assertThat(JonggaPerformanceService.isPriceLimitViolation(-29.9, 0.0)).isFalse();
        assertThat(JonggaPerformanceService.isPriceLimitViolation(29.9, 29.9)).isFalse();
    }

    @Test
    void movesBeyondDailyPriceLimitAreRejected() {
        // 가온전선 실제 사례
        assertThat(JonggaPerformanceService.isPriceLimitViolation(-51.35, -46.52)).isTrue();
        // 상승 방향도 동일
        assertThat(JonggaPerformanceService.isPriceLimitViolation(40.0, 45.0)).isTrue();
        // 종가는 정상인데 고가만 비정상
        assertThat(JonggaPerformanceService.isPriceLimitViolation(1.0, 88.0)).isTrue();
    }

    @Test
    void highBelowCloseIsContradictoryAndRejected() {
        // 고가는 항상 종가 이상이어야 한다
        assertThat(JonggaPerformanceService.isPriceLimitViolation(5.0, 1.0)).isTrue();
        // 반올림 오차 수준(0.01%p)은 허용
        assertThat(JonggaPerformanceService.isPriceLimitViolation(5.0, 4.995)).isFalse();
    }

    @Test
    void missingValuesAreNotTreatedAsViolation() {
        assertThat(JonggaPerformanceService.isPriceLimitViolation(null, null)).isFalse();
        assertThat(JonggaPerformanceService.isPriceLimitViolation(null, 5.0)).isFalse();
        assertThat(JonggaPerformanceService.isPriceLimitViolation(-2.0, null)).isFalse();
    }
}
