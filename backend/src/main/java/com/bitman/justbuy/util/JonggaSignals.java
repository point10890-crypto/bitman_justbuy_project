package com.bitman.justbuy.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 종가매매 아카이브에서 "추적/노출 대상" 시그널을 고르는 단일 기준.
 * 홈 카드 TOP3, 성과 기록, 히스토리 조회가 모두 같은 종목 집합을 보게 한다.
 */
public final class JonggaSignals {

    public static final int TRACKED_TOP_N = 3;

    private JonggaSignals() {}

    /** 상위 {@value #TRACKED_TOP_N} 종목. 종목코드가 6자리가 아니거나 진입가가 없으면 제외한다. */
    public static List<JsonNode> tracked(JsonNode archive) {
        return tracked(archive, TRACKED_TOP_N);
    }

    public static List<JsonNode> tracked(JsonNode archive, int topN) {
        List<JsonNode> selected = new ArrayList<>();
        if (archive == null) return selected;

        Set<String> seen = new HashSet<>();
        for (JsonNode signal : archive.path("signals")) {
            if (selected.size() >= topN) break;
            String stockCode = signal.path("stock_code").asText("");
            if (!stockCode.matches("\\d{6}")) continue;
            if (entryPrice(signal) <= 0) continue;
            if (!seen.add(stockCode)) continue;
            selected.add(signal);
        }
        return selected;
    }

    /** 진입가. 없으면 현재가로 대체한다. */
    public static long entryPrice(JsonNode signal) {
        return signal.path("entry_price").asLong(signal.path("current_price").asLong(0));
    }
}
