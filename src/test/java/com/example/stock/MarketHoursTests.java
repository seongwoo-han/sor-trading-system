package com.example.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MarketHoursTests {
    @Test
    void normalSessionsApplyVenueAndOrderTypeBoundaries() {
        assertEquals(Set.of("NXT"), normalAt("08:15").matchingMarkets(false));
        assertEquals(Set.of(), normalAt("08:15").matchingMarkets(true));
        assertEquals(Set.of("KRX"), normalAt("09:00:29").matchingMarkets(true));
        assertEquals(Set.of("KRX", "NXT"), normalAt("09:00:30").matchingMarkets(true));
        assertEquals(Set.of(), normalAt("15:20").matchingMarkets(false));
        assertTrue(normalAt("15:30").markets().get(1).acceptsLimit());
        assertFalse(normalAt("15:39:59").markets().get(1).executesLimit());
        assertEquals(Set.of("NXT"), normalAt("15:40").matchingMarkets(false));
        assertEquals(Set.of("KRX", "NXT"), normalAt("16:00").matchingMarkets(false));
        assertEquals(Set.of(), normalAt("16:00").matchingMarkets(true));
        assertTrue(normalAt("20:00").markets().stream().noneMatch(MarketHours.VenueStatus::acceptsLimit));
    }

    @Test
    void appliesSeoulExamDayScheduleInRealTime() {
        assertEquals(Set.of(), at("2026-11-19T08:15:00", Set.of()).current().matchingMarkets(false));
        assertEquals(Set.of("KRX"), at("2026-11-19T10:00:29", Set.of()).current().matchingMarkets(true));
        assertEquals(Set.of("KRX", "NXT"), at("2026-11-19T10:00:30", Set.of()).current().matchingMarkets(true));
        assertEquals(Set.of("NXT"), at("2026-11-19T16:40:00", Set.of()).current().matchingMarkets(false));
        assertEquals(Set.of("KRX", "NXT"), at("2026-11-19T17:00:00", Set.of()).current().matchingMarkets(false));
    }

    @Test
    void closesOnWeekendsAndConfiguredHolidaysAndAllowsDemoOverride() {
        var saturday = at("2026-11-21T10:30:00", Set.of());
        assertEquals("휴장일", saturday.current().calendar());
        assertTrue(saturday.current().matchingMarkets(false).isEmpty());
        saturday.setDemoTime(LocalTime.of(8, 15));
        assertTrue(saturday.current().demo());
        assertEquals(Set.of("NXT"), saturday.current().matchingMarkets(false));
        saturday.setDemoTime(null);
        assertFalse(saturday.current().demo());
        assertTrue(saturday.current().matchingMarkets(false).isEmpty());
        var closed = at("2026-12-25T10:30:00", Set.of(LocalDate.of(2026, 12, 25)));
        assertTrue(closed.current().matchingMarkets(false).isEmpty());
    }

    @Test
    void comparisonTimeFiltersCurrentQuotesWithoutChangingTheTradingClock() {
        var saturday = at("2026-11-21T10:30:00", Set.of());
        var comparison = new SorComparisonService(new SorService(), saturday);
        var symbol = StockSymbol.DEFAULT;
        var book = new MarketSimulator.BookSnapshot(0, MarketBooks.basicExample(symbol), symbol.code());

        var premarket = comparison.compareCurrent("BUY", book, symbol.referencePrice(), 10, "KRX", LocalTime.of(8, 15));
        assertEquals(Set.of("NXT"), premarket.executionMarkets());
        assertEquals(4, premarket.sor().filledQuantity());
        assertEquals("08:15:00", premarket.comparisonHours().time());
        assertFalse(premarket.comparisonHours().markets().get(1).acceptsMarket());
        assertTrue(comparison.compareCurrent("BUY", book, symbol.referencePrice(), 10, "KRX", null).executionMarkets().isEmpty());
        assertEquals("휴장일", saturday.current().calendar());
        assertFalse(saturday.current().demo());
    }

    private static MarketHours.Status normalAt(String time) {
        return at("2026-11-20T" + time, Set.of()).current();
    }

    private static MarketHours at(String time, Set<LocalDate> closedDates) {
        var instant = LocalDateTime.parse(time).atZone(MarketHours.SEOUL).toInstant();
        return new MarketHours(Clock.fixed(instant, MarketHours.SEOUL), null, closedDates);
    }
}
