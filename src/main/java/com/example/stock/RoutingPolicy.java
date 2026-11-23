package com.example.stock;

public enum RoutingPolicy {
    PRICE_FIRST,
    QUANTITY_FIRST;

    static RoutingPolicy from(String value) {
        if (value == null) return PRICE_FIRST;
        return switch (value) {
            case "PRICE_FIRST" -> PRICE_FIRST;
            case "QUANTITY_FIRST" -> QUANTITY_FIRST;
            default -> throw new IllegalArgumentException("SOR 기준은 가격 우선 또는 수량 우선으로 선택해 주세요.");
        };
    }
}
