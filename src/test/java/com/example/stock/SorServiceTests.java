package com.example.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.stock.SorService.Allocation;
import com.example.stock.SorService.MarketQuote;
import java.util.List;
import org.junit.jupiter.api.Test;

class SorServiceTests {
    private final SorService routing = new SorService();

    @Test
    void priceFirstRemainsTheDefaultAndQuantityFirstStartsAtTheLargerMarket() {
        var quotes = MarketBooks.basicExample();
        var byPrice = routing.planBuy(70_000, 10, quotes);
        var byQuantity = routing.plan("BUY", 70_000L, 10, quotes, RoutingPolicy.QUANTITY_FIRST);

        assertEquals(RoutingPolicy.PRICE_FIRST, byPrice.routingPolicy());
        assertEquals(List.of(new Allocation("NXT", 1, 69_900, 4, 279_600, 139),
                new Allocation("KRX", 1, 70_000, 6, 420_000, 420)), byPrice.allocations());
        assertEquals(699_600, byPrice.estimatedAmount());
        assertEquals(700_159, byPrice.settlementAmount());
        assertEquals(List.of(new Allocation("KRX", 1, 70_000, 10, 700_000, 700)), byQuantity.allocations());
        assertEquals(700_000, byQuantity.estimatedAmount());
    }

    @Test
    void quantityFirstSumsEligibleLevelsAndThenFillsTheRemainderAtTheOtherMarket() {
        var quotes = List.of(new MarketQuote("KRX", 1, 100, 2),
                new MarketQuote("KRX", 2, 110, 6), new MarketQuote("NXT", 1, 90, 7),
                new MarketQuote("NXT", 2, 120, 99));
        var plan = routing.plan("BUY", 110L, 10, quotes, RoutingPolicy.QUANTITY_FIRST);

        assertEquals(List.of(new Allocation("KRX", 1, 100, 2, 200, 0),
                new Allocation("KRX", 2, 110, 6, 660, 0), new Allocation("NXT", 1, 90, 2, 180, 0)), plan.allocations());
        assertEquals(0, plan.unallocatedQuantity());
    }

    @Test
    void equalExecutableQuantityRanksByEstimatedAmountForThatQuantity() {
        var quotes = List.of(new MarketQuote("KRX", 1, 100, 1),
                new MarketQuote("KRX", 2, 150, 9), new MarketQuote("NXT", 110, 8));
        var plan = routing.plan("BUY", 150L, 8, quotes, RoutingPolicy.QUANTITY_FIRST);

        assertEquals(List.of(new Allocation("NXT", 1, 110, 8, 880, 0)), plan.allocations());
    }

    @Test
    void sellQuantityFirstUsesBidDepthAboveTheSellLimit() {
        var quotes = MarketBooks.basicExample();
        var byPrice = routing.planSell(69_800, 10, quotes);
        var byQuantity = routing.plan("SELL", 69_800L, 10, quotes, RoutingPolicy.QUANTITY_FIRST);
        var higherLimit = routing.plan("SELL", 69_850L, 10, quotes, RoutingPolicy.QUANTITY_FIRST);

        assertEquals(698_200, byPrice.estimatedAmount());
        assertEquals(List.of(new Allocation("KRX", 1, 69_800, 10, 698_000, 698)), byQuantity.allocations());
        assertEquals(List.of(new Allocation("NXT", 1, 69_850, 4, 279_400, 139)), higherLimit.allocations());
        assertEquals(6, higherLimit.unallocatedQuantity());
    }

    @Test
    void feesCanChangeVenueRankingWhileTheLimitStillUsesTheQuotedPrice() {
        var quotes = List.of(new MarketQuote("KRX", 1, 55_000, 2, 54_010, 2),
                new MarketQuote("NXT", 1, 55_010, 2, 54_000, 2));

        assertEquals("NXT", routing.planBuy(55_010, 1, quotes).allocations().getFirst().market());
        assertEquals(55_037, routing.planBuy(55_010, 1, quotes).settlementAmount());
        assertEquals("KRX", routing.planBuy(55_005, 1, quotes).allocations().getFirst().market());
        assertEquals("NXT", routing.planSell(54_000, 1, quotes).allocations().getFirst().market());
        assertEquals(53_973, routing.planSell(54_000, 1, quotes).settlementAmount());
        assertEquals("KRX", routing.planSell(54_005, 1, quotes).allocations().getFirst().market());
        assertEquals("NXT", routing.plan("BUY", 55_010L, 1, quotes, RoutingPolicy.QUANTITY_FIRST)
                .allocations().getFirst().market());
    }
}
