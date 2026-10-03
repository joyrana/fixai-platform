package com.fixai.platform.simulator.engine;

import com.fixai.platform.fixcore.DictionaryRegistry;
import com.fixai.platform.fixcore.FixVersion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import quickfix.DataDictionary;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.ApplVerID;
import quickfix.field.AvgPx;
import quickfix.field.BusinessRejectReason;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecID;
import quickfix.field.ExecTransType;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.MsgSeqNum;
import quickfix.field.MsgType;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.RefMsgType;
import quickfix.field.RefSeqNum;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;

/**
 * Deterministic order-handling logic for one simulator session.
 *
 * <p>Pure with respect to I/O: it consumes an inbound application message and returns the responses to send, which
 * makes every business rule unit-testable without sockets. OrderIDs and ExecIDs come from per-session counters, so a
 * replayed input sequence yields identical outputs (apart from TransactTime, which follows the injected clock).
 */
public final class OrderBook {

    static final int MAX_ORDERS = 10_000;

    private static final DateTimeFormatter UTC_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final FixVersion version;
    private final SimulatorProfile profile;
    private final DataDictionary dictionary;
    private final Clock clock;
    private final Map<String, SimOrder> ordersByClOrdId = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SimOrder> eldest) {
            return size() > MAX_ORDERS;
        }
    };
    private long orderCounter;
    private long execCounter;

    public OrderBook(FixVersion version, SimulatorProfile profile, Clock clock) {
        this.version = version;
        this.profile = profile;
        this.dictionary = DictionaryRegistry.shared().application(version);
        this.clock = clock;
    }

    public SimulatorProfile profile() {
        return profile;
    }

    public synchronized List<Message> handle(Message inbound) throws FieldNotFound {
        String msgType = inbound.getHeader().getString(MsgType.FIELD);
        return switch (msgType) {
            case "D" -> newOrder(inbound);
            case "F" -> cancel(inbound);
            case "G" -> replace(inbound);
            case "H" -> status(inbound);
            default -> List.of(businessReject(inbound, msgType));
        };
    }

    private List<Message> newOrder(Message in) throws FieldNotFound {
        String clOrdId = in.getString(ClOrdID.FIELD);
        String symbol = in.getString(Symbol.FIELD);
        char side = in.getChar(Side.FIELD);
        char ordType = in.getChar(OrdType.FIELD);
        BigDecimal qty = in.getDecimal(OrderQty.FIELD);
        BigDecimal price = in.isSetField(Price.FIELD) ? in.getDecimal(Price.FIELD) : null;
        SimOrder order = new SimOrder(nextOrderId(), clOrdId, symbol, side, ordType, qty, price);

        Optional<String> rejection = validateNewOrder(order);
        if (rejection.isPresent()) {
            order.ordStatus = '8';
            String[] reason = rejection.get().split("\\|", 2);
            return List.of(rejectReport(order, reason[0], reason[1]));
        }

        ordersByClOrdId.put(clOrdId, order);
        List<Message> out = new ArrayList<>();
        out.add(report(order, ExecType.NEW, null, null, null));
        out.addAll(matchIfMarketable(order));
        return out;
    }

    /** Returns {@code "<OrdRejReason>|<Text>"} when the order must be rejected. */
    private Optional<String> validateNewOrder(SimOrder order) {
        if (profile == SimulatorProfile.REJECT_ALL_ORDERS) {
            return Optional.of(rejectCode("0") + "|Rejected by broker option");
        }
        if (profile != SimulatorProfile.ACCEPT_DUPLICATE_CLORDID && ordersByClOrdId.containsKey(order.clOrdId)) {
            return Optional.of(rejectCode("6") + "|Duplicate ClOrdID");
        }
        Optional<BigDecimal> reference = ReferenceData.referencePrice(order.symbol);
        if (reference.isEmpty()) {
            return Optional.of(rejectCode("1") + "|Unknown symbol");
        }
        if (ReferenceData.HALTED_SYMBOL.equals(order.symbol)) {
            return Optional.of(rejectCode("2") + "|Instrument halted");
        }
        if (order.side != '1' && order.side != '2' && order.side != '5') {
            return Optional.of(rejectCode("11") + "|Unsupported side");
        }
        if (order.orderQty.signum() <= 0) {
            return Optional.of(rejectCode("13") + "|OrderQty must be positive");
        }
        if (order.orderQty.compareTo(ReferenceData.MAX_ORDER_QTY) > 0) {
            return Optional.of(rejectCode("3") + "|OrderQty exceeds limit of " + ReferenceData.MAX_ORDER_QTY.toPlainString());
        }
        if (order.ordType != OrdType.MARKET && order.ordType != OrdType.LIMIT) {
            return Optional.of(rejectCode("11") + "|Unsupported OrdType");
        }
        if (order.ordType == OrdType.LIMIT) {
            if (order.price == null || order.price.signum() <= 0) {
                return Optional.of(rejectCode("99") + "|Limit order requires a positive Price");
            }
            if (order.price.stripTrailingZeros().scale() > ReferenceData.MAX_PRICE_SCALE) {
                return Optional.of(rejectCode("18") + "|Price exceeds " + ReferenceData.MAX_PRICE_SCALE + " decimal places");
            }
        }
        return Optional.empty();
    }

    private List<Message> matchIfMarketable(SimOrder order) {
        BigDecimal reference = ReferenceData.referencePrice(order.symbol).orElseThrow();
        boolean marketable = order.ordType == OrdType.MARKET
                || (order.side == Side.BUY && order.price.compareTo(reference) >= 0)
                || (order.side != Side.BUY && order.price.compareTo(reference) <= 0);
        if (!marketable || !order.isOpen()) {
            return List.of();
        }
        List<Message> fills = new ArrayList<>();
        BigDecimal remaining = order.leavesQty();
        if (remaining.compareTo(ReferenceData.PARTIAL_FILL_THRESHOLD) > 0) {
            BigDecimal first = remaining.divide(BigDecimal.valueOf(2), 0, RoundingMode.DOWN);
            fills.add(fill(order, first, reference));
            remaining = order.leavesQty();
        }
        fills.add(fill(order, remaining, reference));
        return fills;
    }

    private Message fill(SimOrder order, BigDecimal qty, BigDecimal px) {
        order.fill(qty, px);
        char execType;
        if (version == FixVersion.FIX42) {
            execType = order.ordStatus == '2' ? ExecType.FILL : ExecType.PARTIAL_FILL;
        } else {
            execType = ExecType.TRADE;
        }
        if (profile == SimulatorProfile.WRONG_EXEC_TYPE_ON_FILL) {
            execType = ExecType.NEW;
        }
        return report(order, execType, qty, px, null);
    }

    private List<Message> cancel(Message in) throws FieldNotFound {
        String clOrdId = in.getString(ClOrdID.FIELD);
        String origClOrdId = in.getString(OrigClOrdID.FIELD);
        if (profile == SimulatorProfile.NO_CANCEL_RESPONSE) {
            return List.of();
        }
        SimOrder order = ordersByClOrdId.get(origClOrdId);
        if (order == null) {
            if (profile == SimulatorProfile.ACCEPT_UNKNOWN_CANCEL) {
                SimOrder phantom = new SimOrder(nextOrderId(), clOrdId, in.getString(Symbol.FIELD),
                        in.getChar(Side.FIELD), OrdType.LIMIT, BigDecimal.ZERO, null);
                phantom.ordStatus = '4';
                return List.of(report(phantom, ExecType.CANCELED, null, null, origClOrdId));
            }
            return List.of(cancelReject(clOrdId, origClOrdId, null, CxlRejResponseTo.ORDER_CANCEL_REQUEST, "1", "Unknown order"));
        }
        if (!order.isOpen()) {
            return List.of(cancelReject(clOrdId, origClOrdId, order, CxlRejResponseTo.ORDER_CANCEL_REQUEST, "0", "Order already closed"));
        }
        order.ordStatus = '4';
        order.clOrdId = clOrdId;
        ordersByClOrdId.put(clOrdId, order);
        return List.of(report(order, ExecType.CANCELED, null, null, origClOrdId));
    }

    private List<Message> replace(Message in) throws FieldNotFound {
        String clOrdId = in.getString(ClOrdID.FIELD);
        String origClOrdId = in.getString(OrigClOrdID.FIELD);
        SimOrder order = ordersByClOrdId.get(origClOrdId);
        if (order == null) {
            return List.of(cancelReject(clOrdId, origClOrdId, null, CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST, "1", "Unknown order"));
        }
        if (!order.isOpen()) {
            return List.of(cancelReject(clOrdId, origClOrdId, order, CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST, "0", "Order already closed"));
        }
        BigDecimal newQty = in.isSetField(OrderQty.FIELD) ? in.getDecimal(OrderQty.FIELD) : order.orderQty;
        if (newQty.compareTo(order.cumQty) <= 0 || newQty.compareTo(ReferenceData.MAX_ORDER_QTY) > 0) {
            return List.of(cancelReject(clOrdId, origClOrdId, order, CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST, "0",
                    "New OrderQty must exceed CumQty and not exceed the limit"));
        }
        if (in.isSetField(Price.FIELD)) {
            BigDecimal newPrice = in.getDecimal(Price.FIELD);
            if (newPrice.signum() <= 0) {
                return List.of(cancelReject(clOrdId, origClOrdId, order, CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST, "0",
                        "Price must be positive"));
            }
            order.price = newPrice;
        }
        order.orderQty = newQty;
        order.clOrdId = clOrdId;
        ordersByClOrdId.put(clOrdId, order);
        List<Message> out = new ArrayList<>();
        out.add(report(order, ExecType.REPLACED, null, null, origClOrdId));
        out.addAll(matchIfMarketable(order));
        return out;
    }

    private List<Message> status(Message in) throws FieldNotFound {
        String clOrdId = in.getString(ClOrdID.FIELD);
        SimOrder order = ordersByClOrdId.get(clOrdId);
        if (order == null) {
            SimOrder unknown = new SimOrder("NONE", clOrdId, in.getString(Symbol.FIELD), in.getChar(Side.FIELD),
                    OrdType.LIMIT, BigDecimal.ZERO, null);
            unknown.ordStatus = '8';
            Message report = statusReport(unknown);
            report.setString(Text.FIELD, "Unknown order");
            return List.of(report);
        }
        return List.of(statusReport(order));
    }

    private Message statusReport(SimOrder order) {
        if (version == FixVersion.FIX42) {
            Message report = report(order, order.ordStatus, null, null, null);
            report.setChar(ExecTransType.FIELD, ExecTransType.STATUS);
            return report;
        }
        return report(order, ExecType.ORDER_STATUS, null, null, null);
    }

    private Message rejectReport(SimOrder order, String reason, String text) {
        Message report = report(order, ExecType.REJECTED, null, null, null);
        report.setString(OrdRejReason.FIELD, reason);
        report.setString(Text.FIELD, text);
        return report;
    }

    private Message report(SimOrder order, char execType, BigDecimal lastQty, BigDecimal lastPx, String origClOrdId) {
        Message m = newMessage("8");
        m.setString(OrderID.FIELD, order.orderId);
        String execId = nextExecId();
        if (profile == SimulatorProfile.DUPLICATE_EXEC_ID) {
            if (order.firstExecId == null) {
                order.firstExecId = execId;
            }
            execId = order.firstExecId;
        }
        if (profile != SimulatorProfile.MISSING_EXEC_ID) {
            m.setString(ExecID.FIELD, execId);
        }
        if (version == FixVersion.FIX42) {
            m.setChar(ExecTransType.FIELD, ExecTransType.NEW);
        }
        m.setChar(ExecType.FIELD, execType);
        m.setChar(OrdStatus.FIELD, order.ordStatus);
        m.setString(ClOrdID.FIELD, order.clOrdId);
        if (origClOrdId != null && profile != SimulatorProfile.MISSING_ORIG_CLORDID) {
            m.setString(OrigClOrdID.FIELD, origClOrdId);
        }
        m.setString(Symbol.FIELD, order.symbol);
        m.setChar(Side.FIELD, order.side);
        m.setChar(OrdType.FIELD, order.ordType);
        m.setString(OrderQty.FIELD, order.orderQty.toPlainString());
        if (order.price != null) {
            m.setString(Price.FIELD, order.price.toPlainString());
        }
        BigDecimal cumQty = order.cumQty;
        if (profile == SimulatorProfile.INCORRECT_CUM_QTY && lastQty != null) {
            cumQty = cumQty.subtract(lastQty);
        }
        m.setString(LeavesQty.FIELD, order.leavesQty().toPlainString());
        m.setString(CumQty.FIELD, cumQty.toPlainString());
        m.setString(AvgPx.FIELD, profile == SimulatorProfile.WRONG_AVG_PX ? "0" : order.avgPx().toPlainString());
        if (lastQty != null) {
            m.setString(LastQty.FIELD, lastQty.toPlainString());
            m.setString(LastPx.FIELD, lastPx.toPlainString());
        }
        m.setString(TransactTime.FIELD, UTC_TIMESTAMP.format(clock.instant()));
        return m;
    }

    private Message cancelReject(
            String clOrdId, String origClOrdId, SimOrder order, char responseTo, String reason, String text) {
        Message m = newMessage("9");
        m.setString(OrderID.FIELD, order == null ? "NONE" : order.orderId);
        m.setString(ClOrdID.FIELD, clOrdId);
        m.setString(OrigClOrdID.FIELD, origClOrdId);
        m.setChar(OrdStatus.FIELD, order == null ? OrdStatus.REJECTED : order.ordStatus);
        m.setChar(CxlRejResponseTo.FIELD, responseTo);
        m.setString(CxlRejReason.FIELD, valueOrFallback(CxlRejReason.FIELD, reason, "0"));
        m.setString(Text.FIELD, text);
        return m;
    }

    private Message businessReject(Message in, String msgType) throws FieldNotFound {
        Message m = newMessage("j");
        m.setInt(RefSeqNum.FIELD, in.getHeader().getInt(MsgSeqNum.FIELD));
        m.setString(RefMsgType.FIELD, msgType);
        m.setInt(BusinessRejectReason.FIELD, BusinessRejectReason.UNSUPPORTED_MESSAGE_TYPE);
        m.setString(Text.FIELD, "Unsupported message type");
        return m;
    }

    private Message newMessage(String msgType) {
        Message m = new Message();
        m.getHeader().setString(MsgType.FIELD, msgType);
        if (version.isFixt()) {
            m.getHeader().setString(ApplVerID.FIELD, version.defaultApplVerId().orElseThrow());
        }
        return m;
    }

    private String rejectCode(String preferred) {
        return valueOrFallback(OrdRejReason.FIELD, preferred, "0");
    }

    /** Older dictionaries define fewer reason codes; fall back so responses always validate against the session's version. */
    private String valueOrFallback(int tag, String preferred, String fallback) {
        return dictionary.isFieldValue(tag, preferred) ? preferred : fallback;
    }

    private String nextOrderId() {
        return "SIM-O-" + (++orderCounter);
    }

    private String nextExecId() {
        return "SIM-E-" + (++execCounter);
    }
}
