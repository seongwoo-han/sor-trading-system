package com.example.stock;

import java.util.List;

enum MarketVenue {
    KRX("A", 10),
    NXT("B", 5);

    static final List<String> NAMES = List.of(KRX.name(), NXT.name());
    private final String storageCode;
    private final int feeBasisPoints;

    MarketVenue(String storageCode, int feeBasisPoints) {
        this.storageCode = storageCode;
        this.feeBasisPoints = feeBasisPoints;
    }

    String storageCode() {
        return storageCode;
    }

    int feeBasisPoints() { return feeBasisPoints; }

    static MarketVenue from(String value) {
        return switch (value) {
            case "KRX", "A" -> KRX;
            case "NXT", "B" -> NXT;
            case null, default -> throw new IllegalArgumentException("시장은 KRX 또는 NXT를 선택해 주세요.");
        };
    }
}
