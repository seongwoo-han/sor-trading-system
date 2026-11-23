package com.example.stock;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

public record PortfolioValuation(
        Long referencePrice, String referenceMarket, Integer referenceLevel,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal averagePurchasePrice,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal purchaseAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal marketValue,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal profitLoss,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal returnPercent,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAssets) {

    static PortfolioValuation calculate(MarketSimulator.AccountSnapshot account, SorService.Allocation bestBid,
                                        List<Trade> positionTrades) {
        int quantity = account.quantity();
        var shares = BigDecimal.valueOf(quantity);
        var average = positionTrades.isEmpty() ? null : movingAverage(positionTrades);
        var cost = quantity == 0 ? BigDecimal.ZERO : average == null ? null : average.multiply(shares);
        var value = quantity == 0 ? BigDecimal.ZERO : bestBid == null ? null : shares.multiply(BigDecimal.valueOf(bestBid.price()));
        var profit = value == null || cost == null ? null : value.subtract(cost);
        var percent = profit == null || cost.signum() == 0 ? null : profit.movePointRight(2).divide(cost, 2, RoundingMode.HALF_UP);
        var assets = value == null ? null : value.add(BigDecimal.valueOf(account.cash()));
        return new PortfolioValuation(bestBid == null ? null : bestBid.price(), bestBid == null ? null : bestBid.market(),
                bestBid == null ? null : bestBid.level(), roundMoney(average), roundMoney(cost),
                roundMoney(value), roundMoney(profit), percent, roundMoney(assets));
    }

    private static BigDecimal movingAverage(List<Trade> trades) {
        BigDecimal average = BigDecimal.ZERO;
        long quantity = 0;
        for (Trade trade : trades) {
            if (trade.buy()) {
                var previousCost = average.multiply(BigDecimal.valueOf(quantity));
                var fillCost = BigDecimal.valueOf(trade.price()).multiply(BigDecimal.valueOf(trade.quantity()))
                        .add(BigDecimal.valueOf(trade.fee()));
                quantity += trade.quantity();
                average = previousCost.add(fillCost).divide(BigDecimal.valueOf(quantity), MathContext.DECIMAL128);
            } else {
                quantity -= trade.quantity();
            }
        }
        return average;
    }

    private static BigDecimal roundMoney(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    record Trade(boolean buy, long price, int quantity, long fee) {}
}
