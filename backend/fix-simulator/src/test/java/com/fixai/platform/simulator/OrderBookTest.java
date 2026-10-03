package com.fixai.platform.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixai.platform.fixcore.FixMessageBuilder;
import com.fixai.platform.fixcore.FixMessageInspector;
import com.fixai.platform.fixcore.FixVersion;
import com.fixai.platform.simulator.engine.OrderBook;
import com.fixai.platform.simulator.engine.SimulatorProfile;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import quickfix.Message;

class OrderBookTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC);

    @ParameterizedTest
    @EnumSource(FixVersion.class)
    void compliantResponsesValidateAgainstDictionaryForEveryVersion(FixVersion version) throws Exception {
        OrderBook book = new OrderBook(version, SimulatorProfile.COMPLIANT, CLOCK);
        List<Message> all = new java.util.ArrayList<>();
        all.addAll(book.handle(order(version, "C1", "AAPL", "1", "2", "100", "150")));      // rests
        all.addAll(book.handle(order(version, "C2", "AAPL", "1", "1", "5000", null)));      // market, partial + fill
        all.addAll(book.handle(order(version, "C3", "UNKNOWN", "1", "1", "10", null)));    // reject
        all.addAll(book.handle(cancel(version, "C4", "C1", "AAPL", "1")));                  // cancel ok
        all.addAll(book.handle(cancel(version, "C5", "NOPE", "AAPL", "1")));                // cancel reject
        all.addAll(book.handle(order(version, "C6", "MSFT", "2", "2", "100", "500")));      // rests (sell above ref)
        all.addAll(book.handle(replace(version, "C7", "C6", "MSFT", "2", "200", "410")));   // replace -> marketable

        for (Message message : all) {
            assertThat(inspect(version, message).issues()).as(message.toString()).isEmpty();
        }
        assertThat(all).hasSize(10);
    }

    @Test
    void marketOrderAboveThresholdIsPartiallyThenFullyFilledWithConsistentQuantities() throws Exception {
        OrderBook book = new OrderBook(FixVersion.FIX44, SimulatorProfile.COMPLIANT, CLOCK);

        List<Message> out = book.handle(order(FixVersion.FIX44, "C1", "IBM", "1", "1", "3001", null));

        assertThat(out).extracting(m -> field(m, 150)).containsExactly("0", "F", "F");
        assertThat(out).extracting(m -> field(m, 39)).containsExactly("0", "1", "2");
        assertThat(out).extracting(m -> field(m, 14)).containsExactly("0", "1500", "3001");
        assertThat(out).extracting(m -> field(m, 151)).containsExactly("3001", "1501", "0");
        assertThat(field(out.get(2), 6)).isEqualTo("180");
        assertThat(out).extracting(m -> field(m, 17)).doesNotHaveDuplicates();
    }

    @Test
    void fix42UsesLegacyExecTypesAndExecTransType() throws Exception {
        OrderBook book = new OrderBook(FixVersion.FIX42, SimulatorProfile.COMPLIANT, CLOCK);

        List<Message> out = book.handle(order(FixVersion.FIX42, "C1", "IBM", "2", "1", "10", null));

        assertThat(out).extracting(m -> field(m, 150)).containsExactly("0", "2");
        assertThat(out).extracting(m -> field(m, 20)).containsExactly("0", "0");
    }

    @Test
    void businessRulesRejectInvalidOrders() throws Exception {
        OrderBook book = new OrderBook(FixVersion.FIX44, SimulatorProfile.COMPLIANT, CLOCK);
        book.handle(order(FixVersion.FIX44, "DUP", "AAPL", "1", "2", "1", "1"));

        assertThat(rejectReason(book, order(FixVersion.FIX44, "DUP", "AAPL", "1", "2", "1", "1"))).isEqualTo("6");
        assertThat(rejectReason(book, order(FixVersion.FIX44, "Q0", "AAPL", "1", "2", "0", "1"))).isEqualTo("13");
        assertThat(rejectReason(book, order(FixVersion.FIX44, "QMAX", "AAPL", "1", "1", "1000001", null))).isEqualTo("3");
        assertThat(rejectReason(book, order(FixVersion.FIX44, "SYM", "ZZZZ", "1", "1", "1", null))).isEqualTo("1");
        assertThat(rejectReason(book, order(FixVersion.FIX44, "HALT", "HALT", "1", "1", "1", null))).isEqualTo("2");
        assertThat(rejectReason(book, order(FixVersion.FIX44, "PX", "AAPL", "1", "2", "1", "0"))).isEqualTo("99");
        // FIX 4.4 does not define OrdRejReason=18 (invalid price increment); FIX 5.0 SP2 does.
        assertThat(rejectReason(book, order(FixVersion.FIX44, "TICK", "AAPL", "1", "2", "1", "1.123456"))).isEqualTo("0");
        OrderBook fix50 = new OrderBook(FixVersion.FIX50SP2, SimulatorProfile.COMPLIANT, CLOCK);
        assertThat(rejectReason(fix50, order(FixVersion.FIX50SP2, "TICK", "AAPL", "1", "2", "1", "1.123456"))).isEqualTo("18");
        assertThat(rejectReason(book, order(FixVersion.FIX44, "STOP", "AAPL", "1", "3", "1", null))).isEqualTo("11");
    }

    @Test
    void fix42FallsBackToReasonCodesItsDictionaryDefines() throws Exception {
        OrderBook book = new OrderBook(FixVersion.FIX42, SimulatorProfile.COMPLIANT, CLOCK);

        assertThat(rejectReason(book, order(FixVersion.FIX42, "Q0", "AAPL", "1", "2", "0", "1"))).isEqualTo("0");
    }

    @Test
    void cancelOfFilledOrderIsTooLate() throws Exception {
        OrderBook book = new OrderBook(FixVersion.FIX44, SimulatorProfile.COMPLIANT, CLOCK);
        book.handle(order(FixVersion.FIX44, "C1", "AAPL", "1", "1", "10", null));

        List<Message> out = book.handle(cancel(FixVersion.FIX44, "C2", "C1", "AAPL", "1"));

        assertThat(out).singleElement().satisfies(m -> {
            assertThat(m.getHeader().getString(35)).isEqualTo("9");
            assertThat(field(m, 102)).isEqualTo("0");
            assertThat(field(m, 434)).isEqualTo("1");
        });
    }

    @Test
    void unsupportedApplicationMessageGetsBusinessReject() throws Exception {
        OrderBook book = new OrderBook(FixVersion.FIX44, SimulatorProfile.COMPLIANT, CLOCK);
        Message quoteRequest = new FixMessageBuilder(FixVersion.FIX44).build("R", Map.of("QuoteReqID", "Q1"));
        quoteRequest.getHeader().setInt(34, 7);

        List<Message> out = book.handle(quoteRequest);

        assertThat(out).singleElement().satisfies(m -> {
            assertThat(m.getHeader().getString(35)).isEqualTo("j");
            assertThat(field(m, 45)).isEqualTo("7");
            assertThat(field(m, 380)).isEqualTo("3");
        });
    }

    @Test
    void defectProfilesInjectExactlyTheirDocumentedDeviation() throws Exception {
        assertThat(firstFill(SimulatorProfile.WRONG_EXEC_TYPE_ON_FILL).getString(150)).isEqualTo("0");
        assertThat(firstFill(SimulatorProfile.MISSING_EXEC_ID).isSetField(17)).isFalse();
        assertThat(firstFill(SimulatorProfile.WRONG_AVG_PX).getString(6)).isEqualTo("0");
        assertThat(firstFill(SimulatorProfile.INCORRECT_CUM_QTY).getString(14)).isEqualTo("0");

        OrderBook duplicates = new OrderBook(FixVersion.FIX44, SimulatorProfile.DUPLICATE_EXEC_ID, CLOCK);
        assertThat(duplicates.handle(order(FixVersion.FIX44, "C1", "AAPL", "1", "1", "10", null)))
                .extracting(m -> field(m, 17)).containsOnly("SIM-E-1");

        OrderBook rejectAll = new OrderBook(FixVersion.FIX44, SimulatorProfile.REJECT_ALL_ORDERS, CLOCK);
        assertThat(rejectReason(rejectAll, order(FixVersion.FIX44, "C1", "AAPL", "1", "1", "10", null))).isEqualTo("0");

        OrderBook noCancel = new OrderBook(FixVersion.FIX44, SimulatorProfile.NO_CANCEL_RESPONSE, CLOCK);
        noCancel.handle(order(FixVersion.FIX44, "C1", "AAPL", "1", "2", "10", "1"));
        assertThat(noCancel.handle(cancel(FixVersion.FIX44, "C2", "C1", "AAPL", "1"))).isEmpty();

        OrderBook phantom = new OrderBook(FixVersion.FIX44, SimulatorProfile.ACCEPT_UNKNOWN_CANCEL, CLOCK);
        assertThat(phantom.handle(cancel(FixVersion.FIX44, "C2", "NOPE", "AAPL", "1")))
                .singleElement().satisfies(m -> assertThat(field(m, 150)).isEqualTo("4"));

        OrderBook noOrig = new OrderBook(FixVersion.FIX44, SimulatorProfile.MISSING_ORIG_CLORDID, CLOCK);
        noOrig.handle(order(FixVersion.FIX44, "C1", "AAPL", "1", "2", "10", "1"));
        assertThat(noOrig.handle(cancel(FixVersion.FIX44, "C2", "C1", "AAPL", "1")))
                .singleElement().satisfies(m -> assertThat(m.isSetField(41)).isFalse());

        OrderBook dupOk = new OrderBook(FixVersion.FIX44, SimulatorProfile.ACCEPT_DUPLICATE_CLORDID, CLOCK);
        dupOk.handle(order(FixVersion.FIX44, "C1", "AAPL", "1", "2", "10", "1"));
        assertThat(dupOk.handle(order(FixVersion.FIX44, "C1", "AAPL", "1", "2", "10", "1")))
                .extracting(m -> field(m, 150)).containsExactly("0");
    }

    @Test
    void identicalInputsProduceIdenticalOutputs() throws Exception {
        List<String> first = runScript();
        List<String> second = runScript();

        assertThat(first).isEqualTo(second);
    }

    private List<String> runScript() throws Exception {
        OrderBook book = new OrderBook(FixVersion.FIX44, SimulatorProfile.COMPLIANT, CLOCK);
        List<String> out = new java.util.ArrayList<>();
        for (Message m : book.handle(order(FixVersion.FIX44, "C1", "AAPL", "1", "1", "2000", null))) {
            out.add(m.toString());
        }
        for (Message m : book.handle(cancel(FixVersion.FIX44, "C2", "C1", "AAPL", "1"))) {
            out.add(m.toString());
        }
        return out;
    }

    private static Message firstFill(SimulatorProfile profile) throws Exception {
        OrderBook book = new OrderBook(FixVersion.FIX44, profile, CLOCK);
        return book.handle(order(FixVersion.FIX44, "C1", "AAPL", "1", "1", "10", null)).get(1);
    }

    private static String rejectReason(OrderBook book, Message order) throws Exception {
        List<Message> out = book.handle(order);
        assertThat(out).singleElement().satisfies(m -> assertThat(m.getString(150)).isEqualTo("8"));
        return out.get(0).getString(103);
    }

    static Message order(FixVersion version, String clOrdId, String symbol, String side, String ordType, String qty, String px) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ClOrdID", clOrdId);
        if (version == FixVersion.FIX42) {
            fields.put("HandlInst", "1");
        }
        fields.put("Symbol", symbol);
        fields.put("Side", side);
        fields.put("TransactTime", "20261003-10:00:00.000");
        fields.put("OrderQty", qty);
        fields.put("OrdType", ordType);
        if (px != null) {
            fields.put("Price", px);
        }
        return new FixMessageBuilder(version).build("D", fields);
    }

    static Message cancel(FixVersion version, String clOrdId, String orig, String symbol, String side) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("OrigClOrdID", orig);
        fields.put("ClOrdID", clOrdId);
        fields.put("Symbol", symbol);
        fields.put("Side", side);
        fields.put("TransactTime", "20261003-10:00:00.000");
        if (version == FixVersion.FIX42) {
            fields.put("OrderQty", "1");
        }
        return new FixMessageBuilder(version).build("F", fields);
    }

    static Message replace(FixVersion version, String clOrdId, String orig, String symbol, String side, String qty, String px) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("OrigClOrdID", orig);
        fields.put("ClOrdID", clOrdId);
        if (version == FixVersion.FIX42) {
            fields.put("HandlInst", "1");
        }
        fields.put("Symbol", symbol);
        fields.put("Side", side);
        fields.put("TransactTime", "20261003-10:00:00.000");
        fields.put("OrderQty", qty);
        fields.put("OrdType", "2");
        fields.put("Price", px);
        return new FixMessageBuilder(version).build("G", fields);
    }

    private static FixMessageInspector.Result inspect(FixVersion version, Message message) {
        message.getHeader().setString(8, version.beginString());
        message.getHeader().setString(49, "SIM");
        message.getHeader().setString(56, "CLIENT");
        message.getHeader().setInt(34, 2);
        message.getHeader().setString(52, "20261003-10:00:00.000");
        return new FixMessageInspector().inspect(message.toString(), Optional.of(version));
    }

    private static String field(Message message, int tag) {
        try {
            return message.getString(tag);
        } catch (quickfix.FieldNotFound exception) {
            return null;
        }
    }
}
