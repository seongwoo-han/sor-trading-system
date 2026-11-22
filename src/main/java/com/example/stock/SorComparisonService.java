package com.example.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 같은 시작 호가에서 각 방식을 독립 계산합니다. 계좌나 시장을 변경하지 않습니다. */
@Service
public class SorComparisonService {
    private final SorService router;
    private final MarketHours marketHours;

    public SorComparisonService(SorService router, MarketHours marketHours) {
        this.router = router;
        this.marketHours = marketHours;
    }

    public ComparisonResult compareExample(StockSymbol symbol, String side, long limitPrice, int quantity,
                                           String baselineMarket, boolean depth, LocalTime comparisonTime) {
        return compare(side, depth ? "DEPTH_EXAMPLE" : "EXAMPLE", null, symbol.code(),
                depth ? MarketBooks.depthExample(symbol) : MarketBooks.basicExample(symbol),
                limitPrice, quantity, baselineMarket, comparisonTime);
    }

    public ComparisonResult compareCurrent(String side, MarketSimulator.BookSnapshot snapshot,
                                             long limitPrice, int quantity, String baselineMarket, LocalTime comparisonTime) {
        if (snapshot == null || snapshot.revision() < 0) {
            throw new IllegalArgumentException("현재 시장 호가를 먼저 조회해 주세요.");
        }
        return compare(side, "CURRENT", snapshot.revision(), snapshot.symbol(), snapshot.quotes(),
                limitPrice, quantity, baselineMarket, comparisonTime);
    }

    private ComparisonResult compare(String direction, String source, Long revision, String symbol, List<SorService.MarketQuote> input,
                                       long limitPrice, int quantity, String baselineMarket, LocalTime comparisonTime) {
        String side = SorService.side(direction);
        if (limitPrice <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("지정가와 비교 수량은 1 이상이어야 합니다.");
        }
        String baseline = MarketVenue.from(baselineMarket).name();
        var quotes = MarketBooks.normalize(input);
        var comparisonHours = comparisonTime == null ? null : marketHours.previewAt(comparisonTime);
        Set<String> executionMarkets;
        if (comparisonHours != null) {
            executionMarkets = comparisonHours.matchingMarkets(false);
        } else if ("CURRENT".equals(source)) {
            executionMarkets = marketHours.current().matchingMarkets(false);
        } else {
            executionMarkets = Set.copyOf(MarketVenue.NAMES);
        }
        var executableQuotes = quotes.stream().filter(q -> executionMarkets.contains(q.market())).toList();
        var singleQuotes = executableQuotes.stream().filter(q -> q.market().equals(baseline)).toList();
        var singlePlan = router.plan(side, limitPrice, quantity, singleQuotes);
        var sorPlan = router.plan(side, limitPrice, quantity, executableQuotes);
        var quantityPlan = router.plan(side, limitPrice, quantity, executableQuotes, RoutingPolicy.QUANTITY_FIRST);
        int commonQuantity = Math.min(singlePlan.allocatedQuantity(), sorPlan.allocatedQuantity());
        CommonQuantityCost common;
        if (commonQuantity == 0) {
            common = new CommonQuantityCost(0, null, null, null);
        } else {
            // 체결수량이 다를 때도 같은 수량을 각각 주문한 금액으로 비교합니다.
            long singleAmount = router.plan(side, limitPrice, commonQuantity, singleQuotes).settlementAmount();
            long sorAmount = router.plan(side, limitPrice, commonQuantity, executableQuotes).settlementAmount();
            common = new CommonQuantityCost(commonQuantity, singleAmount, sorAmount, side.equals("BUY") ? singleAmount - sorAmount : sorAmount - singleAmount);
        }
        return new ComparisonResult(side, source, revision, limitPrice, quantity, baseline, quotes, executionMarkets,
                result(singlePlan), result(sorPlan), result(quantityPlan), common,
                sorPlan.allocatedQuantity() - singlePlan.allocatedQuantity(), symbol, comparisonHours);
    }

    private StrategyResult result(SorService.RoutePlan plan) {
        BigDecimal average = plan.allocatedQuantity() == 0 ? null
                : BigDecimal.valueOf(plan.estimatedAmount()).divide(
                        BigDecimal.valueOf(plan.allocatedQuantity()), 2, RoundingMode.HALF_UP);
        BigDecimal fillRate = BigDecimal.valueOf(plan.allocatedQuantity()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(plan.requestedQuantity()), 2, RoundingMode.HALF_UP);
        return new StrategyResult(plan.allocatedQuantity(), plan.unallocatedQuantity(), plan.estimatedAmount(),
                average, fillRate, plan.allocations(), plan.estimatedFee(), plan.settlementAmount());
    }

    public record StrategyResult(int filledQuantity, int remainingQuantity, long totalAmount,
                                  BigDecimal averagePrice, BigDecimal fillRate,
                                  List<SorService.Allocation> allocations, long totalFee, long settlementAmount) {}

    public record CommonQuantityCost(int quantity, Long singleMarketAmount, Long sorAmount, Long savings) {}

    public record ComparisonResult(String side, String source, Long bookRevision, long limitPrice, int requestedQuantity,
                                    String baselineMarket, List<SorService.MarketQuote> quotes, Set<String> executionMarkets,
                                    StrategyResult singleMarket, StrategyResult sor, StrategyResult quantityFirst,
                                    CommonQuantityCost commonQuantityCost, int additionalFilledQuantity, String symbol,
                                    MarketHours.Status comparisonHours) {}
}

