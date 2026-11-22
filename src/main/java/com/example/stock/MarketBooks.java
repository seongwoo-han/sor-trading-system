package com.example.stock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 공통 양방향 3단계 호가. bidPrice=0은 이전 요청의 매수호가 생략을 뜻합니다. */
public final class MarketBooks {
    private MarketBooks() {}

    public static List<SorService.MarketQuote> normalize(List<SorService.MarketQuote> input) {
        if (input == null || (input.size() != 2 && input.size() != 6) || input.stream().anyMatch(q -> q == null)) {
            throw new IllegalArgumentException("KRX·NXT의 1~3호가를 모두 입력해 주세요.");
        }
        var sorted = input.stream().sorted(Comparator.comparing(SorService.MarketQuote::market)
                .thenComparingInt(SorService.MarketQuote::level)).toList();
        var result = new ArrayList<SorService.MarketQuote>();
        long bidInputs = input.stream().filter(q -> q.bidPrice() > 0).count();
        if (bidInputs != 0 && bidInputs != input.size()) throw new IllegalArgumentException("매수호가는 모든 시장·단계에 함께 입력해 주세요.");
        int depth = input.size() == 2 ? 1 : 3;
        int index = 0;
        for (String market : MarketVenue.NAMES) {
            long previous = 0;
            long previousBid = Long.MAX_VALUE;
            for (int level = 1; level <= depth; level++) {
                var quote = sorted.get(index++);
                if (!quote.market().equals(market) || quote.level() != level || quote.askPrice() < previous || quote.bidPrice() > previousBid) {
                    throw new IllegalArgumentException("시장마다 1·2·3호가를 입력하고, 매도호가는 오름차순·매수호가는 내림차순으로 설정해 주세요.");
                }
                previous = quote.askPrice();
                previousBid = quote.bidPrice();
                result.add(quote);
            }
            if (depth == 1) {
                for (int level = 2; level <= 3; level++) result.add(new SorService.MarketQuote(market, level, previous, 0, previousBid, 0));
            }
            long ask = result.stream().filter(q -> q.market().equals(market) && q.askQuantity() > 0)
                    .mapToLong(SorService.MarketQuote::askPrice).min().orElse(Long.MAX_VALUE);
            long bid = result.stream().filter(q -> q.market().equals(market) && q.bidQuantity() > 0)
                    .mapToLong(SorService.MarketQuote::bidPrice).max().orElse(0);
            if (bid > ask) throw new IllegalArgumentException("같은 시장의 매수호가가 매도호가보다 높을 수 없습니다.");
        }
        return List.copyOf(result);
    }

    /** 과거의 매도호가 전용 요청은 현재 매수호가와 그 잔량을 유지합니다. */
    public static List<SorService.MarketQuote> applyTo(List<SorService.MarketQuote> submitted, List<SorService.MarketQuote> current) {
        if (submitted.get(0).bidPrice() > 0) return submitted;
        var merged = submitted.stream().map(q -> {
            var old = current.stream().filter(v -> v.market().equals(q.market()) && v.level() == q.level()).findFirst().orElseThrow();
            return new SorService.MarketQuote(q.market(), q.level(), q.askPrice(), q.askQuantity(), old.bidPrice(), old.bidQuantity());
        }).toList();
        return normalize(merged);
    }

    public static List<SorService.MarketQuote> basicExample() {
        return basicExample(StockSymbol.VIRTUAL);
    }

    public static List<SorService.MarketQuote> basicExample(StockSymbol symbol) {
        long price = symbol.referencePrice();
        return normalize(List.of(new SorService.MarketQuote("KRX", 1, price, 10, price - 200, 10),
                new SorService.MarketQuote("NXT", 1, price - 100, 4, price - 150, 4)));
    }

    public static List<SorService.MarketQuote> depthExample() {
        return List.of(new SorService.MarketQuote("KRX", 1, 1000, 2, 970, 2),
                new SorService.MarketQuote("KRX", 2, 1020, 4, 950, 4),
                new SorService.MarketQuote("KRX", 3, 1040, 4, 930, 4),
                new SorService.MarketQuote("NXT", 1, 990, 3, 980, 3),
                new SorService.MarketQuote("NXT", 2, 1010, 3, 960, 3),
                new SorService.MarketQuote("NXT", 3, 1030, 4, 940, 4));
    }

    public static List<SorService.MarketQuote> depthExample(StockSymbol symbol) {
        var example = depthExample();
        if (symbol == StockSymbol.VIRTUAL) return example;
        return example.stream().map(level -> new SorService.MarketQuote(level.market(), level.level(),
                symbol.referencePrice() + (level.askPrice() - 1_000) * 10, level.askQuantity(),
                symbol.referencePrice() + (level.bidPrice() - 1_000) * 10, level.bidQuantity())).toList();
    }
}
