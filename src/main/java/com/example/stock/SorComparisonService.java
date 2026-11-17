package com.example.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;

/** 같은 시작 호가에서 각 방식을 독립 계산합니다. 계좌나 시장을 변경하지 않습니다. */
@Service
public class SorComparisonService {
    private final SorService router;

    public SorComparisonService(SorService router) { this.router = router; }

    public ComparisonResult compareExample(long limitPrice, int quantity, String baselineMarket) {
        return compareExample("BUY", limitPrice, quantity, baselineMarket);
    }
    public ComparisonResult compareExample(String side, long limitPrice, int quantity, String baselineMarket) {
        return compare(side, "EXAMPLE", null, MarketBooks.basicExample(), limitPrice, quantity, baselineMarket);
    }

    public ComparisonResult compareDepthExample(long limitPrice, int quantity, String baselineMarket) {
        return compareDepthExample("BUY", limitPrice, quantity, baselineMarket);
    }
    public ComparisonResult compareDepthExample(String side, long limitPrice, int quantity, String baselineMarket) {
        return compare(side, "DEPTH_EXAMPLE", null, MarketBooks.depthExample(), limitPrice, quantity, baselineMarket);
    }

    public ComparisonResult compareCurrent(MarketSimulator.BookSnapshot snapshot,
                                             long limitPrice, int quantity, String baselineMarket) {
        return compareCurrent("BUY", snapshot, limitPrice, quantity, baselineMarket);
    }
    public ComparisonResult compareCurrent(String side, MarketSimulator.BookSnapshot snapshot,
                                             long limitPrice, int quantity, String baselineMarket) {
        if (snapshot == null || snapshot.revision() < 0) {
            throw new IllegalArgumentException("현재 시장 호가를 먼저 조회해 주세요.");
        }
        return compare(side, "CURRENT", snapshot.revision(), snapshot.quotes(), limitPrice, quantity, baselineMarket);
    }

    private ComparisonResult compare(String direction, String source, Long revision, List<SorService.MarketQuote> input,
                                       long limitPrice, int quantity, String baselineMarket) {
        String side = SorService.side(direction);
        if (limitPrice <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("지정가와 비교 수량은 1 이상이어야 합니다.");
        }
        if (!"A".equals(baselineMarket) && !"B".equals(baselineMarket)) {
            throw new IllegalArgumentException("비교 기준 시장은 A 또는 B를 선택해 주세요.");
        }
        var quotes = MarketBooks.normalize(input);
        var singleQuotes = quotes.stream().filter(q -> q.market().equals(baselineMarket)).toList();
        var singlePlan = router.plan(side, limitPrice, quantity, singleQuotes);
        var sorPlan = router.plan(side, limitPrice, quantity, quotes);
        int commonQuantity = Math.min(singlePlan.allocatedQuantity(), sorPlan.allocatedQuantity());
        CommonQuantityCost common;
        if (commonQuantity == 0) {
            common = new CommonQuantityCost(0, null, null, null);
        } else {
            // 체결수량이 다를 때도 같은 수량을 각각 주문한 금액으로 비교합니다.
            long singleAmount = router.plan(side, limitPrice, commonQuantity, singleQuotes).estimatedAmount();
            long sorAmount = router.plan(side, limitPrice, commonQuantity, quotes).estimatedAmount();
            common = new CommonQuantityCost(commonQuantity, singleAmount, sorAmount, side.equals("BUY") ? singleAmount - sorAmount : sorAmount - singleAmount);
        }
        return new ComparisonResult(side, source, revision, limitPrice, quantity, baselineMarket, quotes,
                result(singlePlan), result(sorPlan), common,
                sorPlan.allocatedQuantity() - singlePlan.allocatedQuantity());
    }

    private StrategyResult result(SorService.RoutePlan plan) {
        BigDecimal average = plan.allocatedQuantity() == 0 ? null
                : BigDecimal.valueOf(plan.estimatedAmount()).divide(
                        BigDecimal.valueOf(plan.allocatedQuantity()), 2, RoundingMode.HALF_UP);
        BigDecimal fillRate = BigDecimal.valueOf(plan.allocatedQuantity()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(plan.requestedQuantity()), 2, RoundingMode.HALF_UP);
        return new StrategyResult(plan.allocatedQuantity(), plan.unallocatedQuantity(), plan.estimatedAmount(),
                average, fillRate, plan.allocations());
    }

    public record StrategyResult(int filledQuantity, int remainingQuantity, long totalAmount,
                                  BigDecimal averagePrice, BigDecimal fillRate,
                                  List<SorService.Allocation> allocations) {}

    public record CommonQuantityCost(int quantity, Long singleMarketAmount, Long sorAmount, Long savings) {}

    public record ComparisonResult(String side, String source, Long bookRevision, long limitPrice, int requestedQuantity,
                                    String baselineMarket, List<SorService.MarketQuote> quotes,
                                    StrategyResult singleMarket, StrategyResult sor,
                                    CommonQuantityCost commonQuantityCost, int additionalFilledQuantity) {}
}

