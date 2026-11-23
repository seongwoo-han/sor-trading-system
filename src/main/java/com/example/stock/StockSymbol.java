package com.example.stock;

import java.util.Arrays;
import java.util.List;

/** 두 모의시장에서 공통으로 다루는 종목과 예시 호가의 기준 가격입니다. */
public enum StockSymbol {
    VIRTUAL("VIRTUAL01", "가상종목 01", 1, 70_000),
    SAMSUNG("005930", "삼성전자", 2, 82_000),
    HYNIX("000660", "SK하이닉스", 3, 205_000),
    HYUNDAI("005380", "현대차", 4, 247_000),
    NAVER("035420", "NAVER", 5, 211_000),
    LG_ENERGY("373220", "LG에너지솔루션", 6, 366_000),
    KAKAO("035720", "카카오", 7, 55_000);

    public static final StockSymbol DEFAULT = SAMSUNG;
    private static final List<StockSymbol> ACTIVE = List.of(SAMSUNG, HYNIX, HYUNDAI, NAVER, KAKAO);

    private final String code;
    private final String name;
    private final int bookId;
    private final long referencePrice;

    StockSymbol(String code, String name, int bookId, long referencePrice) {
        this.code = code;
        this.name = name;
        this.bookId = bookId;
        this.referencePrice = referencePrice;
    }

    public String code() { return code; }
    public String displayName() { return name; }
    public int bookId() { return bookId; }
    public long referencePrice() { return referencePrice; }

    public static List<StockSymbol> active() { return ACTIVE; }

    public boolean isActive() { return ACTIVE.contains(this); }

    public void requireActive() {
        if (!isActive()) throw new IllegalArgumentException("이전 종목입니다. 거래 목록에서 종목을 골라 주세요.");
    }

    public static StockSymbol from(String code) {
        if (code == null) return DEFAULT;
        return Arrays.stream(values()).filter(symbol -> symbol.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하는 모의거래 종목을 선택해 주세요."));
    }

    public static List<SymbolInfo> catalog() {
        return ACTIVE.stream().map(symbol -> new SymbolInfo(symbol.code(), symbol.displayName())).toList();
    }

    public record SymbolInfo(String code, String name) {}
}
