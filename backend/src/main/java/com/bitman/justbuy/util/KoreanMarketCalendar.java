package com.bitman.justbuy.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * 한국 증시 휴장일 판별 유틸리티.
 * 주말(토/일) + 공휴일이면 휴장으로 판단한다.
 */
public final class KoreanMarketCalendar {

    private KoreanMarketCalendar() {}

    /** 2025~2027 한국 증시 공휴일 (KRX 휴장일 기준).
     *
     *  ⚠ 2027 공휴일은 통상적인 한국 공휴일 + 대체공휴일 규칙에 기반한 추정치다.
     *     KRX 공식 2027 휴장일 발표(통상 11~12월) 후 검증·보정 필요.
     *     마지막 검토: 2026-04-26 (v2.8.4 audit) */
    private static final Set<LocalDate> HOLIDAYS = Set.of(
        // ── 2025 ──
        LocalDate.of(2025, 1, 1),   // 신정
        LocalDate.of(2025, 1, 28),  // 설날 연휴
        LocalDate.of(2025, 1, 29),  // 설날
        LocalDate.of(2025, 1, 30),  // 설날 연휴
        LocalDate.of(2025, 3, 1),   // 삼일절
        LocalDate.of(2025, 5, 5),   // 어린이날
        LocalDate.of(2025, 5, 6),   // 석가탄신일
        LocalDate.of(2025, 6, 6),   // 현충일
        LocalDate.of(2025, 8, 15),  // 광복절
        LocalDate.of(2025, 10, 3),  // 개천절
        LocalDate.of(2025, 10, 5),  // 추석 연휴
        LocalDate.of(2025, 10, 6),  // 추석
        LocalDate.of(2025, 10, 7),  // 추석 연휴
        LocalDate.of(2025, 10, 8),  // 대체공휴일
        LocalDate.of(2025, 10, 9),  // 한글날
        LocalDate.of(2025, 12, 25), // 크리스마스
        LocalDate.of(2025, 12, 31), // 연말 휴장

        // ── 2026 ──
        LocalDate.of(2026, 1, 1),   // 신정
        LocalDate.of(2026, 2, 16),  // 설날 연휴
        LocalDate.of(2026, 2, 17),  // 설날
        LocalDate.of(2026, 2, 18),  // 설날 연휴
        LocalDate.of(2026, 3, 1),   // 삼일절
        LocalDate.of(2026, 3, 2),   // 대체공휴일
        LocalDate.of(2026, 5, 5),   // 어린이날
        LocalDate.of(2026, 5, 25),  // 석가탄신일
        LocalDate.of(2026, 6, 6),   // 현충일
        LocalDate.of(2026, 8, 15),  // 광복절
        LocalDate.of(2026, 8, 17),  // 대체공휴일
        LocalDate.of(2026, 9, 24),  // 추석 연휴
        LocalDate.of(2026, 9, 25),  // 추석
        LocalDate.of(2026, 9, 26),  // 추석 연휴
        LocalDate.of(2026, 10, 3),  // 개천절
        LocalDate.of(2026, 10, 5),  // 대체공휴일
        LocalDate.of(2026, 10, 9),  // 한글날
        LocalDate.of(2026, 12, 25), // 크리스마스
        LocalDate.of(2026, 12, 31), // 연말 휴장

        // ── 2027 (추정 — KRX 공식 발표 후 보정 필요) ──
        LocalDate.of(2027, 1, 1),   // 신정 (금)
        LocalDate.of(2027, 2, 5),   // 설날 연휴 (금, 음 12/30)
        LocalDate.of(2027, 2, 8),   // 설날 대체공휴일 (월) — 음 1/1이 토요일
        LocalDate.of(2027, 3, 1),   // 삼일절 (월)
        LocalDate.of(2027, 5, 5),   // 어린이날 (수)
        LocalDate.of(2027, 5, 13),  // 석가탄신일 (목, 음 4/8)
        LocalDate.of(2027, 6, 7),   // 현충일 대체공휴일 (월) — 6/6 일요일
        LocalDate.of(2027, 8, 16),  // 광복절 대체공휴일 (월) — 8/15 일요일
        LocalDate.of(2027, 9, 14),  // 추석 연휴 (화)
        LocalDate.of(2027, 9, 15),  // 추석 (수, 음 8/15)
        LocalDate.of(2027, 9, 16),  // 추석 연휴 (목)
        LocalDate.of(2027, 10, 4),  // 개천절 대체공휴일 (월) — 10/3 일요일
        LocalDate.of(2027, 10, 11), // 한글날 대체공휴일 (월) — 10/9 토요일
        LocalDate.of(2027, 12, 27), // 크리스마스 대체공휴일 (월) — 12/25 토요일
        LocalDate.of(2027, 12, 31)  // 연말 휴장 (금)
    );

    /**
     * 해당 날짜가 한국 증시 거래일인지 판별.
     * @return true = 거래일 (평일 & 비공휴일)
     */
    public static boolean isTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return !HOLIDAYS.contains(date);
    }

    /**
     * 해당 날짜가 휴장일인지 판별 (주말 포함).
     */
    public static boolean isHoliday(LocalDate date) {
        return !isTradingDay(date);
    }

    /**
     * 기준일 직전의 거래일. 연휴가 길어도 최대 30일까지만 역산한다.
     * @return 직전 거래일. 30일 내에 없으면 null.
     */
    public static LocalDate previousTradingDay(LocalDate date) {
        LocalDate cursor = date.minusDays(1);
        for (int i = 0; i < 30; i++) {
            if (isTradingDay(cursor)) return cursor;
            cursor = cursor.minusDays(1);
        }
        return null;
    }

    /**
     * 기준일 직후의 거래일. 연휴가 길어도 최대 30일까지만 전진한다.
     * 종가매매 성과는 "추천일 다음 거래일"에 평가하므로 소급 검증에서 사용한다.
     *
     * @return 직후 거래일. 30일 내에 없으면 null.
     */
    public static LocalDate nextTradingDay(LocalDate date) {
        LocalDate cursor = date.plusDays(1);
        for (int i = 0; i < 30; i++) {
            if (isTradingDay(cursor)) return cursor;
            cursor = cursor.plusDays(1);
        }
        return null;
    }
}
