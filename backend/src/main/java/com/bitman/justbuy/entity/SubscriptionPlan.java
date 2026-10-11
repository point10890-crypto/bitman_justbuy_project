package com.bitman.justbuy.entity;

/** Server-owned subscription prices and calendar-month durations. */
public enum SubscriptionPlan {
    MONTHLY("1개월", 1, 30_000),
    SIX_MONTHS("6개월", 6, 150_000);

    private final String label;
    private final int months;
    private final int price;

    SubscriptionPlan(String label, int months, int price) {
        this.label = label;
        this.months = months;
        this.price = price;
    }

    public String getLabel() { return label; }
    public int getMonths() { return months; }
    public int getPrice() { return price; }

    /** Older clients and pending rows predate plan selection and mean one month. */
    public static SubscriptionPlan fromId(String id) {
        if (id == null) return MONTHLY;
        try {
            return valueOf(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("올바르지 않은 구독 플랜입니다.");
        }
    }
}
