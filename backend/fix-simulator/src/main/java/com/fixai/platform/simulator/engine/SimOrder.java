package com.fixai.platform.simulator.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Mutable order state owned by a single session's {@link OrderBook}; guarded by that book's lock. */
final class SimOrder {

    final String orderId;
    final String symbol;
    final char side;
    final char ordType;
    String clOrdId;
    BigDecimal orderQty;
    BigDecimal price;
    BigDecimal cumQty = BigDecimal.ZERO;
    BigDecimal notional = BigDecimal.ZERO;
    char ordStatus = '0';
    String firstExecId;

    SimOrder(String orderId, String clOrdId, String symbol, char side, char ordType, BigDecimal orderQty, BigDecimal price) {
        this.orderId = orderId;
        this.clOrdId = clOrdId;
        this.symbol = symbol;
        this.side = side;
        this.ordType = ordType;
        this.orderQty = orderQty;
        this.price = price;
    }

    BigDecimal leavesQty() {
        return isOpen() ? orderQty.subtract(cumQty) : BigDecimal.ZERO;
    }

    BigDecimal avgPx() {
        return cumQty.signum() == 0 ? BigDecimal.ZERO : notional.divide(cumQty, 6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    boolean isOpen() {
        return ordStatus == '0' || ordStatus == '1' || ordStatus == '5';
    }

    void fill(BigDecimal qty, BigDecimal px) {
        cumQty = cumQty.add(qty);
        notional = notional.add(qty.multiply(px));
        ordStatus = cumQty.compareTo(orderQty) >= 0 ? '2' : '1';
    }
}
