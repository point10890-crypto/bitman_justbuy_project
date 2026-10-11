package com.bitman.justbuy.dto;

import com.bitman.justbuy.entity.SubscriptionPlan;

public record SubscriptionPlanDto(String id, String label, int months, int price) {
    public static SubscriptionPlanDto from(SubscriptionPlan plan) {
        return new SubscriptionPlanDto(plan.name(), plan.getLabel(), plan.getMonths(), plan.getPrice());
    }
}
