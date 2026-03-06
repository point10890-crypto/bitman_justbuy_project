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

    /** 2025~2026 한국 증시 공휴일 (KRX 휴장일 기준) */
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
        LocalDate.of(2026, 12, 31)  // 연말 휴장
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
}
