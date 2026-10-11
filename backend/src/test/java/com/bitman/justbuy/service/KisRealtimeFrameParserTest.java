package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class KisRealtimeFrameParserTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T00:30:00Z"), ZoneOffset.UTC);
    private final KisRealtimeFrameParser parser = new KisRealtimeFrameParser();

    @Test void officialTradeFramePreservesActualPriceTurnoverAndTradeTime() {
        var quotes = parser.parse(frame("005930", "093000", "20261012", "70000", "10", "100", "7000000"), Set.of("005930"), clock);
        assertThat(quotes).hasSize(1);
        assertThat(quotes.getFirst().price()).isEqualTo(70000);
        assertThat(quotes.getFirst().accumulatedVolume()).isEqualTo(100);
        assertThat(quotes.getFirst().tradingValue()).isEqualByComparingTo("7000000");
        assertThat(quotes.getFirst().tradedAt()).isEqualTo(Instant.parse("2026-10-12T00:30:00Z"));
    }
    @Test void framesNeverIntroduceAnUnwatchedStock() {
        assertThat(parser.parse(frame("000660", "093000", "20261012", "70000", "10", "100", "7000000"), Set.of("005930"), clock)).isEmpty();
    }
    @Test void rejectsWrongTradeDayFutureTimeAndMarketHoliday() {
        assertThat(parser.parse(frame("005930", "093000", "20261008", "70000", "10", "100", "7000000"), Set.of("005930"), clock)).isEmpty();
        assertThat(parser.parse(frame("005930", "093100", "20261012", "70000", "10", "100", "7000000"), Set.of("005930"), clock)).isEmpty();
        Clock holiday = Clock.fixed(Instant.parse("2026-10-09T00:30:00Z"), ZoneOffset.UTC);
        assertThat(parser.parse(frame("005930", "093000", "20261009", "70000", "10", "100", "7000000"), Set.of("005930"), holiday)).isEmpty();
    }
    @Test void rejectsMissingFieldsWrongChannelEncryptionAndBadCounts() {
        String good = frame("005930", "093000", "20261012", "70000", "10", "100", "7000000");
        assertThat(parser.parse(good.replace("H0STCNT0", "H0STCNI0"), Set.of("005930"), clock)).isEmpty();
        assertThat(parser.parse("1" + good.substring(1), Set.of("005930"), clock)).isEmpty();
        assertThat(parser.parse(good.replace("|001|", "|002|"), Set.of("005930"), clock)).isEmpty();
        assertThat(parser.parse("0|H0STCNT0|001|005930^093000^70000", Set.of("005930"), clock)).isEmpty();
    }
    @Test void rejectsNonPositiveOverflowAndInconsistentVolume() {
        for (String price : new String[]{"0", "-1", "70000.1", "9223372036854775808"})
            assertThat(parser.parse(frame("005930", "093000", "20261012", price, "10", "100", "7000000"), Set.of("005930"), clock)).isEmpty();
        assertThat(parser.parse(frame("005930", "093000", "20261012", "70000", "101", "100", "7000000"), Set.of("005930"), clock)).isEmpty();
        assertThat(parser.parse(frame("005930", "093000", "20261012", "70000", "0", "100", "7000000"), Set.of("005930"), clock)).isEmpty();
    }
    @Test void splitBatchAcceptsOnlyVerifiedRows() {
        String first = frame("005930", "093000", "20261012", "70000", "10", "100", "7000000");
        String second = frame("000660", "093000", "20261012", "200000", "20", "200", "40000000");
        String batch = "0|H0STCNT0|002|" + first.split("\\|", 4)[3] + "^" + second.split("\\|", 4)[3];
        assertThat(parser.parse(batch, Set.of("005930", "000660"), clock)).hasSize(2);
    }
    static String frame(String code, String time, String date, String price, String tradeVol, String volume, String value) {
        String[] fields = new String[47];
        Arrays.fill(fields, "0");
        fields[0] = code; fields[1] = time; fields[2] = price;
        fields[12] = tradeVol; fields[13] = volume; fields[14] = value; fields[33] = date;
        return "0|H0STCNT0|001|" + String.join("^", fields);
    }
}
