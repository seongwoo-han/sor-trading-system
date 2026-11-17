package com.example.stock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SorService {

    public RoutePlan planBuy(long limitPrice, int quantity,
                             List<MarketQuote> quotes) {
        return plan("BUY", limitPrice, quantity, quotes);
    }

    public RoutePlan planSell(long limitPrice, int quantity, List<MarketQuote> quotes) {
        return plan("SELL", limitPrice, quantity, quotes);
    }

    public static String side(String value) {
        if (value == null) return "BUY"; // 이전 매수 요청과 호환
        if (!value.equals("BUY") && !value.equals("SELL")) throw new IllegalArgumentException("주문 구분은 BUY 또는 SELL이어야 합니다.");
        return value;
    }

    public RoutePlan plan(String direction, long limitPrice, int quantity, List<MarketQuote> quotes) {
        boolean buy = side(direction).equals("BUY");
        if (limitPrice <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("지정가와 주문 수량은 1 이상이어야 합니다.");
        }
        if (quotes == null || quotes.stream().anyMatch(q -> q == null)) {
            throw new IllegalArgumentException("호가 목록을 확인해 주세요.");
        }

        var seen = new HashSet<String>();
        for (var quote : quotes) {
            if (!seen.add(quote.market() + ":" + quote.level())) {
                throw new IllegalArgumentException("같은 시장·호가 단계를 중복해서 배분할 수 없습니다.");
            }
        }
        List<MarketQuote> sortedQuotes = new ArrayList<>(quotes);
        Comparator<MarketQuote> byPrice = Comparator.comparingLong(q -> buy ? q.askPrice() : q.bidPrice());
        if (!buy) byPrice = byPrice.reversed();
        sortedQuotes.sort(byPrice.thenComparing(MarketQuote::market).thenComparingInt(MarketQuote::level));

        int remaining = quantity;
        long estimatedAmount = 0;
        List<Allocation> allocations = new ArrayList<>();

        for (MarketQuote quote : sortedQuotes) {
            if (remaining == 0) {
                break;
            }
            long price = buy ? quote.askPrice() : quote.bidPrice();
            int available = buy ? quote.askQuantity() : quote.bidQuantity();
            if (buy ? price > limitPrice : price < limitPrice) {
                break;
            }
            if (available == 0) {
                continue;
            }

            int allocated = Math.min(remaining, available);
            long amount;
            try {
                amount = Math.multiplyExact(price, (long) allocated);
                estimatedAmount = Math.addExact(estimatedAmount, amount);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("배분 금액이 처리 가능한 범위를 초과했습니다.");
            }
            allocations.add(new Allocation(quote.market(), quote.level(), price,
                    allocated, amount));
            remaining -= allocated;
        }

        return new RoutePlan(limitPrice, quantity, quantity - remaining,
                remaining, estimatedAmount, List.copyOf(allocations));
    }

    public record MarketQuote(String market, int level, long askPrice, int askQuantity, long bidPrice, int bidQuantity) {
        public MarketQuote(String market, int level, long askPrice, int askQuantity) { this(market, level, askPrice, askQuantity, 0, 0); }
        public MarketQuote(String market, long askPrice, int askQuantity) { this(market, 1, askPrice, askQuantity); }
        public MarketQuote {
            if (market == null || market.isBlank() || level < 1 || level > 3 || askPrice <= 0 || askQuantity < 0
                    || bidPrice < 0 || bidQuantity < 0 || (bidPrice == 0 && bidQuantity != 0)) {
                throw new IllegalArgumentException("시장명, 호가 단계, 매도 가격과 잔량을 확인해 주세요.");
            }
        }
    }

    public record Allocation(String market, int level, long price, int quantity, long amount) {
        public Allocation(String market, long price, int quantity, long amount) { this(market, 1, price, quantity, amount); }
    }

    public record RoutePlan(long limitPrice, int requestedQuantity,
                            int allocatedQuantity, int unallocatedQuantity,
                            long estimatedAmount, List<Allocation> allocations) {
    }
}
