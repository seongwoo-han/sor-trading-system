package com.example.stock;

import java.math.BigInteger;
import java.util.Map;

/** 시연용 체결 수수료. 각 체결의 원 미만 수수료는 버립니다. */
final class SimulatedFees {
    private static final int BASIS = 10_000;
    private static final BigInteger BASIS_AMOUNT = BigInteger.valueOf(BASIS);
    static final Map<String, Integer> RATES = Map.of(
            MarketVenue.KRX.name(), MarketVenue.KRX.feeBasisPoints(),
            MarketVenue.NXT.name(), MarketVenue.NXT.feeBasisPoints());
    private static final int MAX_RATE = RATES.values().stream().mapToInt(Integer::intValue).max().orElseThrow();

    private SimulatedFees() {}

    static BigInteger fee(BigInteger amount, int basisPoints) {
        return amount.multiply(BigInteger.valueOf(basisPoints)).divide(BASIS_AMOUNT);
    }

    static BigInteger adjustedUnit(long price, int basisPoints, boolean buy) {
        return BigInteger.valueOf(price).multiply(BigInteger.valueOf(BASIS + (buy ? basisPoints : -basisPoints)));
    }

    static long settlement(long amount, long fee, boolean buy) {
        return buy ? Math.addExact(amount, fee) : amount - fee;
    }

    static long buyReservation(long limitPrice, int quantity, boolean feesEnabled) {
        long amount = Math.multiplyExact(limitPrice, (long) quantity);
        return feesEnabled ? settlement(amount, fee(BigInteger.valueOf(amount), MAX_RATE).longValueExact(), true) : amount;
    }
}
