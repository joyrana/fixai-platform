package com.fixai.platform.simulator.engine;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Synthetic instrument universe. Prices are fixed so every run is reproducible.
 */
public final class ReferenceData {

    public static final String HALTED_SYMBOL = "HALT";
    public static final BigDecimal MAX_ORDER_QTY = new BigDecimal("1000000");
    public static final BigDecimal PARTIAL_FILL_THRESHOLD = new BigDecimal("1000");
    public static final int MAX_PRICE_SCALE = 4;

    private static final Map<String, BigDecimal> PRICES = Map.of(
            "AAPL", new BigDecimal("190.00"),
            "MSFT", new BigDecimal("420.00"),
            "IBM", new BigDecimal("180.00"),
            "GOOG", new BigDecimal("165.00"),
            "VOD.L", new BigDecimal("70.00"),
            "ESZ6", new BigDecimal("5800.00"),
            HALTED_SYMBOL, new BigDecimal("10.00"));

    private ReferenceData() {
    }

    public static Optional<BigDecimal> referencePrice(String symbol) {
        return Optional.ofNullable(PRICES.get(symbol));
    }

    public static Map<String, BigDecimal> prices() {
        return PRICES;
    }
}
