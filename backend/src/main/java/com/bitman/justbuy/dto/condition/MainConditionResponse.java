package com.bitman.justbuy.dto.condition;

public record MainConditionResponse(
    String asOf,
    Sections sections,
    TrackRecordSummary trackRecord,
    String notice,
    /**
     * 미구독자에게 내려간 마스킹 응답인지. true 면 종목명·가격이 서버에서 가려져 있다.
     * 클라이언트 마스킹만으로는 응답 본문에 원본이 남아 우회가 가능하므로 서버에서 가린다.
     */
    boolean preview,
    /** 회원 티어(ACTIVE/PENDING/EXPIRED/NONE). 구독 유도 문구 분기에 쓴다. */
    String tier
) {
    /** 구독자용 — 마스킹 없음. */
    public static MainConditionResponse full(String asOf, Sections sections,
                                             TrackRecordSummary trackRecord, String notice) {
        return new MainConditionResponse(asOf, sections, trackRecord, notice, false, "ACTIVE");
    }

    public record Sections(
        ConditionSectionResponse shortTerm,
        ConditionSectionResponse swing,
        ConditionSectionResponse leaders,
        ConditionSectionResponse themes,
        ConditionSectionResponse closingBet,
        ConditionSectionResponse alerts
    ) {}
}
