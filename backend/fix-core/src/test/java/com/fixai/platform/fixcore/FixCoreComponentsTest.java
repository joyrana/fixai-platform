package com.fixai.platform.fixcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.field.BeginString;
import quickfix.field.MsgSeqNum;
import quickfix.field.SenderCompID;
import quickfix.field.SendingTime;
import quickfix.field.TargetCompID;

class FixCoreComponentsTest {

    @TempDir
    Path tempDir;

    @Test
    void redactorMasksDefaultAndConfiguredTags() {
        FixMessageRedactor redactor = new FixMessageRedactor(Set.of(1));

        String redacted = redactor.redactRaw("8=FIX.4.4\u00019=10\u00011=ACC-9\u0001554=pw\u000196=blob\u000155=IBM\u0001");

        assertThat(redacted).isEqualTo("8=FIX.4.4|9=10|1=***|554=***|96=***|55=IBM|");
        assertThat(redactor.isSensitive(925)).isTrue();
        assertThat(redactor.isSensitive(55)).isFalse();
    }

    @Test
    void builderResolvesNamesHeaderFieldsAndRepeatingGroups() throws Exception {
        FixMessageBuilder builder = new FixMessageBuilder(FixVersion.FIX44);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ClOrdID", "C-1");
        fields.put("Side", "1");
        fields.put("Symbol", "AAPL");
        fields.put("TransactTime", "20261003-10:00:00.000");
        fields.put("OrderQty", "100");
        fields.put("OrdType", "1");
        fields.put("OnBehalfOfCompID", "DESK1");
        fields.put("NoPartyIDs", List.of(
                Map.of("PartyID", "TRADER1", "PartyIDSource", "D", "PartyRole", "11"),
                Map.of("PartyID", "FIRM9", "PartyIDSource", "D", "PartyRole", "1")));

        Message message = builder.build("D", fields);
        message.getHeader().setString(BeginString.FIELD, "FIX.4.4");
        message.getHeader().setString(SenderCompID.FIELD, "CLIENT");
        message.getHeader().setString(TargetCompID.FIELD, "SIM");
        message.getHeader().setInt(MsgSeqNum.FIELD, 2);
        message.getHeader().setString(SendingTime.FIELD, "20261003-10:00:00.000");

        assertThat(message.getHeader().getString(115)).isEqualTo("DESK1");
        assertThat(message.getGroupCount(453)).isEqualTo(2);
        FixMessageInspector.Result inspected = new FixMessageInspector().inspect(message.toString(), Optional.empty());
        assertThat(inspected.issues()).isEmpty();
    }

    @Test
    void builderRejectsUnknownFieldNamesAndMessageTypes() {
        FixMessageBuilder builder = new FixMessageBuilder(FixVersion.FIX44);

        assertThatThrownBy(() -> builder.build("D", Map.of("ClientOrderId", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ClientOrderId");
        assertThatThrownBy(() -> builder.build("ZZ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builderSetsApplVerIdForFixtApplicationMessages() throws Exception {
        Message message = new FixMessageBuilder(FixVersion.FIX50SP2).build("D", Map.of("ClOrdID", "C-1"));

        assertThat(message.getHeader().getString(1128)).isEqualTo("9");
    }

    @Test
    void validatorReportsEveryViolation() {
        FixSessionSpec spec = new FixSessionSpec(FixSessionSpec.Role.INITIATOR, FixVersion.FIX44, "BAD ID", "BAD ID",
                "", 70000, 0, 0, 0, true, false, false, true, FixSessionSpec.StoreType.FILE, " ", "/does/not/exist.xml");

        List<FixSessionSpecValidator.Violation> violations = new FixSessionSpecValidator().validate(spec);

        assertThat(violations).extracting(FixSessionSpecValidator.Violation::code).containsExactlyInAnyOrder(
                "INVALID_COMP_ID", "INVALID_COMP_ID", "SAME_COMP_IDS", "INVALID_HOST", "INVALID_PORT",
                "INVALID_HEARTBEAT", "INVALID_RECONNECT", "INVALID_LOGON_TIMEOUT", "STORE_PATH_REQUIRED",
                "DICTIONARY_NOT_FOUND");
    }

    @Test
    void validatorDetectsDictionaryVersionMismatch() throws Exception {
        Path fix42 = tempDir.resolve("FIX42.xml");
        try (var in = getClass().getClassLoader().getResourceAsStream("FIX42.xml")) {
            Files.copy(in, fix42);
        }
        FixSessionSpec spec = new FixSessionSpec(FixSessionSpec.Role.INITIATOR, FixVersion.FIX44, "A", "B", "localhost",
                9880, 30, 5, 10, true, false, false, true, FixSessionSpec.StoreType.MEMORY, null, fix42.toString());

        assertThat(new FixSessionSpecValidator().validate(spec))
                .extracting(FixSessionSpecValidator.Violation::code)
                .containsExactly("DICTIONARY_VERSION_MISMATCH");
    }

    @Test
    void validSpecHasNoViolations() {
        FixSessionSpec spec = FixSessionSpec.initiator(FixVersion.FIX44, "FIXAI", "SIM", "localhost", 9880);

        assertThat(new FixSessionSpecValidator().validate(spec)).isEmpty();
    }

    @Test
    void settingsBuilderProducesPerSessionInitiatorAndFixtSettings() throws Exception {
        FixSessionSpec fix44 = FixSessionSpec.initiator(FixVersion.FIX44, "FIXAI", "SIM", "localhost", 9880);
        FixSessionSpec fixt = FixSessionSpec.initiator(FixVersion.FIX50SP2, "FIXAI", "SIM", "localhost", 9881)
                .withHeartbeat(10);

        SessionSettings settings = new SessionSettingsBuilder().add(fix44).add(fixt).build();

        SessionID id44 = SessionSettingsBuilder.sessionId(fix44);
        SessionID idT = SessionSettingsBuilder.sessionId(fixt);
        assertThat(settings.getString(id44, "ConnectionType")).isEqualTo("initiator");
        assertThat(settings.getString(id44, Session.SETTING_DATA_DICTIONARY)).isEqualTo("FIX44.xml");
        assertThat(settings.getLong(id44, "SocketConnectPort")).isEqualTo(9880);
        assertThat(settings.getString(idT, Session.SETTING_TRANSPORT_DATA_DICTIONARY)).isEqualTo("FIXT11.xml");
        assertThat(settings.getString(idT, Session.SETTING_DEFAULT_APPL_VER_ID)).isEqualTo("9");
        assertThat(settings.getLong(idT, Session.SETTING_HEARTBTINT)).isEqualTo(10);
    }

    @Test
    void versionParsingAcceptsEnumNamesAndBeginStrings() {
        assertThat(FixVersion.parse("FIX44")).isEqualTo(FixVersion.FIX44);
        assertThat(FixVersion.parse("FIX.4.2")).isEqualTo(FixVersion.FIX42);
        assertThat(FixVersion.parse("FIXT.1.1")).isEqualTo(FixVersion.FIX50SP2);
        assertThatThrownBy(() -> FixVersion.parse("FIX.4.0")).isInstanceOf(IllegalArgumentException.class);
    }
}
