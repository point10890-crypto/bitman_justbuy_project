package com.bitman.justbuy.dto.condition;

public record MainConditionResponse(
    String asOf,
    Sections sections,
    TrackRecordSummary trackRecord,
    String notice
) {
    public record Sections(
        ConditionSectionResponse shortTerm,
        ConditionSectionResponse swing,
        ConditionSectionResponse leaders,
        ConditionSectionResponse themes,
        ConditionSectionResponse closingBet,
        ConditionSectionResponse alerts
    ) {}
}
