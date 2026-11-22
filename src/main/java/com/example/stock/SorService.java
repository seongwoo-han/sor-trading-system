package com.example.stock;

import java.math.BigInteger;
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
        return plan(direction, Long.valueOf(limitPrice), quantity, quotes);
    }

    RoutePlan plan(String direction, Long limitPrice, int quantity, List<MarketQuote> quotes) {
        return plan(direction, limitPrice, quantity, quotes, RoutingPolicy.PRICE_FIRST);
    }

    RoutePlan plan(String direction, Long limitPrice, int quantity, List<MarketQuote> quotes, RoutingPolicy policy) {
        return plan(direction, limitPrice, quantity, quotes, policy, true);
    }

    RoutePlan plan(String direction, Long limitPrice, int quantity, List<MarketQuote> quotes,
                   RoutingPolicy policy, boolean feesEnabled) {
        boolean buy = side(direction).equals("BUY");
        if (quantity <= 0) {
            throw new IllegalArgumentException("주문 수량은 1주 이상이어야 합니다.");
        }
        if (limitPrice != null && limitPrice <= 0) {
            throw new IllegalArgumentException("지정가는 1원 이상이어야 합니다.");
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
        Comparator<MarketQuote> byPrice = Comparator.comparing(
                quote -> SimulatedFees.adjustedUnit(quote.priceFor(buy), feeRate(quote.market(), feesEnabled), buy));
        if (!buy) byPrice = byPrice.reversed();
        var eligible = quotes.stream().filter(quote -> quote.quantityFor(buy) > 0)
                .filter(quote -> limitPrice == null
                        || (buy ? quote.priceFor(buy) <= limitPrice : quote.priceFor(buy) >= limitPrice))
                .sorted(byPrice.thenComparing(MarketQuote::market).thenComparingInt(MarketQuote::level))
                .toList();
        var sortedQuotes = policy == RoutingPolicy.QUANTITY_FIRST
                ? byExecutableQuantity(eligible, buy, quantity, feesEnabled) : eligible;

        int remaining = quantity;
        long estimatedAmount = 0;
        long estimatedFee = 0;
        long settlement = 0;
        List<Allocation> allocations = new ArrayList<>();

        for (MarketQuote quote : sortedQuotes) {
            if (remaining == 0) {
                break;
            }
            long price = quote.priceFor(buy);
            int allocated = Math.min(remaining, quote.quantityFor(buy));
            long amount;
            long fee;
            try {
                amount = Math.multiplyExact(price, (long) allocated);
                fee = SimulatedFees.fee(BigInteger.valueOf(amount), feeRate(quote.market(), feesEnabled)).longValueExact();
                estimatedAmount = Math.addExact(estimatedAmount, amount);
                estimatedFee = Math.addExact(estimatedFee, fee);
                settlement = Math.addExact(settlement, SimulatedFees.settlement(amount, fee, buy));
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("배분 금액이 처리 가능한 범위를 초과했습니다.");
            }
            allocations.add(new Allocation(quote.market(), quote.level(), price,
                    allocated, amount, fee));
            remaining -= allocated;
        }

        return new RoutePlan(limitPrice, quantity, quantity - remaining,
                remaining, estimatedAmount, List.copyOf(allocations), policy, estimatedFee, settlement);
    }

    private static int feeRate(String market, boolean feesEnabled) {
        return feesEnabled ? SimulatedFees.RATES.get(market) : 0;
    }

    private static List<MarketQuote> byExecutableQuantity(List<MarketQuote> eligible, boolean buy, int quantity,
                                                          boolean feesEnabled) {
        var candidates = new ArrayList<MarketCandidate>();
        for (String market : MarketVenue.NAMES) {
            var levels = eligible.stream().filter(quote -> quote.market().equals(market)).toList();
            if (levels.isEmpty()) continue;
            int feeRate = feeRate(market, feesEnabled);
            int remaining = quantity;
            BigInteger amount = BigInteger.ZERO;
            for (var level : levels) {
                int fill = Math.min(remaining, level.quantityFor(buy));
                BigInteger gross = BigInteger.valueOf(level.priceFor(buy)).multiply(BigInteger.valueOf(fill));
                BigInteger fee = SimulatedFees.fee(gross, feeRate);
                amount = amount.add(buy ? gross.add(fee) : gross.subtract(fee));
                remaining -= fill;
                if (remaining == 0) break;
            }
            candidates.add(new MarketCandidate(market, quantity - remaining, amount, levels));
        }
        Comparator<MarketCandidate> byAmount = Comparator.comparing(MarketCandidate::amount);
        if (!buy) byAmount = byAmount.reversed();
        return candidates.stream().sorted(Comparator.comparingInt(MarketCandidate::quantity).reversed()
                        .thenComparing(byAmount).thenComparing(MarketCandidate::market))
                .flatMap(candidate -> candidate.levels().stream()).toList();
    }

    private record MarketCandidate(String market, int quantity, BigInteger amount, List<MarketQuote> levels) {}

    public record MarketQuote(String market, int level, long askPrice, int askQuantity, long bidPrice, int bidQuantity) {
        public MarketQuote(String market, int level, long askPrice, int askQuantity) { this(market, level, askPrice, askQuantity, 0, 0); }
        public MarketQuote(String market, long askPrice, int askQuantity) { this(market, 1, askPrice, askQuantity); }
        public MarketQuote {
            if (market == null || market.isBlank() || level < 1 || level > 3 || askPrice <= 0 || askQuantity < 0
                    || bidPrice < 0 || bidQuantity < 0 || (bidPrice == 0 && bidQuantity != 0)) {
                throw new IllegalArgumentException("시장명, 호가 단계, 매도 가격과 잔량을 확인해 주세요.");
            }
            market = MarketVenue.from(market).name();
        }

        private long priceFor(boolean buy) { return buy ? askPrice : bidPrice; }

        private int quantityFor(boolean buy) { return buy ? askQuantity : bidQuantity; }
    }

    public record Allocation(String market, int level, long price, int quantity, long amount, long fee) {}

    public record RoutePlan(Long limitPrice, int requestedQuantity,
                            int allocatedQuantity, int unallocatedQuantity,
                            long estimatedAmount, List<Allocation> allocations, RoutingPolicy routingPolicy,
                            long estimatedFee, long settlementAmount) {}
}
