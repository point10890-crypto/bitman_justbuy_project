package com.bitman.justbuy.dto.condition;

import java.util.Arrays;

public enum ConditionSection {
    SHORT_TERM("short-term", "shortTerm", "단타", "BREAKOUT"),
    SWING("swing", "swing", "스윙", "REVERSAL_EDGE"),
    LEADERS("leaders", "leaders", "주도주", "FLOW_LEADER"),
    THEMES("themes", "themes", "테마주", "CATALYST_BURST"),
    CLOSING_BET("closing-bet", "closingBet", "종가매매", "JONGGA_V2"),
    ALERTS("alerts", "alerts", "종목 알림이", null);

    private final String slug;
    private final String responseKey;
    private final String title;
    private final String legacyMode;

    ConditionSection(String slug, String responseKey, String title, String legacyMode) {
        this.slug = slug;
        this.responseKey = responseKey;
        this.title = title;
        this.legacyMode = legacyMode;
    }

    public String slug() {
        return slug;
    }

    public String responseKey() {
        return responseKey;
    }

    public String title() {
        return title;
    }

    public String legacyMode() {
        return legacyMode;
    }

    public boolean hasAnalysisMode() {
        return legacyMode != null && !legacyMode.isBlank();
    }

    public static ConditionSection fromSlug(String slug) {
        return Arrays.stream(values())
            .filter(section -> section.slug.equalsIgnoreCase(slug))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown condition section: " + slug));
    }
}
