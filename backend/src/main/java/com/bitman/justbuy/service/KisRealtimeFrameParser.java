package com.bitman.justbuy.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.bitman.justbuy.util.KoreanMarketCalendar;

/** Strict parser for the official KRX H0STCNT0 market-trade frame. */
public final class KisRealtimeFrameParser {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int FIELDS = 47;
    public record Quote(String code, long price, long tradeVolume, long accumulatedVolume,
                        BigDecimal tradingValue, Instant tradedAt, Instant receivedAt) {}

    public List<Quote> parse(String frame, Set<String> watched, Clock clock) {
        if (frame == null || frame.length() > 65_536 || watched == null || watched.isEmpty()) return List.of();
        String[] envelope = frame.split("\\|", 4);
        if (envelope.length != 4 || !"0".equals(envelope[0]) || !"H0STCNT0".equals(envelope[1])
            || !envelope[2].matches("[0-9]{1,3}")) return List.of();
        int count = Integer.parseInt(envelope[2]);
        if (count < 1 || count > 20) return List.of();
        String[] fields = envelope[3].split("\\^", -1);
        if (fields.length != count * FIELDS) return List.of();
        Instant received = clock.instant();
        LocalDate today = received.atZone(KST).toLocalDate();
        if (!KoreanMarketCalendar.isTradingDay(today)) return List.of();
        List<Quote> verified = new ArrayList<>();
        for (int offset = 0; offset < fields.length; offset += FIELDS) {
            try {
                String code = fields[offset];
                if (!code.matches("[0-9]{6}") || !watched.contains(code)
                    || !fields[offset + 1].matches("[0-9]{6}") || !fields[offset + 33].matches("[0-9]{8}")) continue;
                LocalDate day = LocalDate.parse(fields[offset + 33], DateTimeFormatter.BASIC_ISO_DATE);
                LocalTime time = LocalTime.parse(fields[offset + 1], DateTimeFormatter.ofPattern("HHmmss"));
                if (!day.equals(today) || time.isBefore(LocalTime.of(9, 0)) || time.isAfter(LocalTime.of(15, 30))) continue;
                Instant traded = day.atTime(time).atZone(KST).toInstant();
                if (traded.isAfter(received.plusSeconds(5))) continue;
                long price = positiveLong(fields[offset + 2]);
                long tradeVolume = positiveLong(fields[offset + 12]);
                long volume = positiveLong(fields[offset + 13]);
                String value = fields[offset + 14];
                if (tradeVolume > volume || !value.matches("[0-9]{1,18}(?:\\.[0-9]{1,4})?")) continue;
                BigDecimal turnover = new BigDecimal(value);
                if (turnover.signum() <= 0) continue;
                verified.add(new Quote(code, price, tradeVolume, volume, turnover, traded, received));
            } catch (RuntimeException ignored) {
                // Market data only; never return partial, malformed numerical facts.
            }
        }
        return List.copyOf(verified);
    }

    private static long positiveLong(String value) {
        if (!value.matches("[0-9]{1,18}")) throw new IllegalArgumentException();
        long parsed = Long.parseLong(value);
        if (parsed <= 0) throw new IllegalArgumentException();
        return parsed;
    }
}
