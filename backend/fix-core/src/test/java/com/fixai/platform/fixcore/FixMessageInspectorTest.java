package com.fixai.platform.fixcore;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixai.platform.fixcore.FixMessageInspector.Code;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FixMessageInspectorTest {

    private static final String NOS_44 = "8=FIX.4.4|9=0|35=D|34=2|49=CLIENT|52=20261003-10:00:00.000|56=SIM|"
            + "11=ORD-1|21=1|55=AAPL|54=1|60=20261003-10:00:00.000|38=100|40=2|44=150.25|10=000|";

    private final FixMessageInspector inspector = new FixMessageInspector();

    @Test
    void acceptsWellFormedNewOrderSingle() {
        String wire = FixMessageInspector.withComputedLengthAndChecksum(NOS_44);

        FixMessageInspector.Result result = inspector.inspect(wire, Optional.of(FixVersion.FIX44));

        assertThat(result.issues()).isEmpty();
        assertThat(result.valid()).isTrue();
        assertThat(result.msgType()).isEqualTo("D");
        assertThat(result.view().value("ClOrdID")).contains("ORD-1");
        assertThat(result.view().value(55)).contains("AAPL");
        assertThat(result.view().msgSeqNum()).isEqualTo(2);
    }

    @Test
    void acceptsPipeDelimitedInputAndReportsChecksumMismatch() {
        FixMessageInspector.Result result = inspector.inspect(NOS_44.replace("9=0", "9=128"), Optional.empty());

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(FixMessageInspector.Issue::code).contains(Code.CHECKSUM_MISMATCH);
    }

    @Test
    void reportsBodyLengthMismatch() {
        String wire = FixMessageInspector.withComputedLengthAndChecksum(NOS_44);
        String tampered = wire.replaceFirst("9=\\d+", "9=5");

        assertThat(inspector.inspect(tampered, Optional.empty()).issues())
                .extracting(FixMessageInspector.Issue::code)
                .contains(Code.BODY_LENGTH_MISMATCH);
    }

    @Test
    void reportsMissingRequiredField() {
        String withoutSide = FixMessageInspector.withComputedLengthAndChecksum(NOS_44.replace("54=1|", ""));

        FixMessageInspector.Result result = inspector.inspect(withoutSide, Optional.empty());

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo(Code.REQUIRED_TAG_MISSING);
            assertThat(issue.tag()).isEqualTo(54);
        });
    }

    @Test
    void reportsIncorrectEnumValue() {
        String badSide = FixMessageInspector.withComputedLengthAndChecksum(NOS_44.replace("54=1|", "54=Z|"));

        assertThat(inspector.inspect(badSide, Optional.empty()).issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo(Code.VALUE_IS_INCORRECT);
                    assertThat(issue.tag()).isEqualTo(54);
                });
    }

    @Test
    void reportsIncorrectDataFormat() {
        String badQty = FixMessageInspector.withComputedLengthAndChecksum(NOS_44.replace("38=100|", "38=ABC|"));

        assertThat(inspector.inspect(badQty, Optional.empty()).issues())
                .extracting(FixMessageInspector.Issue::code)
                .contains(Code.INCORRECT_DATA_FORMAT);
    }

    @Test
    void reportsUnknownMessageType() {
        String unknown = FixMessageInspector.withComputedLengthAndChecksum(NOS_44.replace("35=D", "35=ZZ"));

        assertThat(inspector.inspect(unknown, Optional.empty()).issues())
                .extracting(FixMessageInspector.Issue::code)
                .contains(Code.UNKNOWN_MSG_TYPE);
    }

    @Test
    void reportsStructuralProblems() {
        assertThat(inspector.inspect("", Optional.empty()).issues())
                .extracting(FixMessageInspector.Issue::code).containsExactly(Code.EMPTY_MESSAGE);
        assertThat(inspector.inspect("35=D|8=FIX.4.4|10=000|", Optional.empty()).issues())
                .extracting(FixMessageInspector.Issue::code).containsExactly(Code.BEGIN_STRING_NOT_FIRST);
        assertThat(inspector.inspect("8=FIX.9.9|9=5|35=0|10=000|", Optional.empty()).issues())
                .extracting(FixMessageInspector.Issue::code).containsExactly(Code.UNSUPPORTED_VERSION);
        assertThat(inspector.inspect("8=FIX.4.4|garbage|10=000|", Optional.empty()).issues())
                .extracting(FixMessageInspector.Issue::code).contains(Code.MALFORMED_FIELD);
    }

    @Test
    void flagsVersionDifferentFromExpected() {
        String wire = FixMessageInspector.withComputedLengthAndChecksum(NOS_44);

        FixMessageInspector.Result result = inspector.inspect(wire, Optional.of(FixVersion.FIX42));

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(FixMessageInspector.Issue::code).contains(Code.UNSUPPORTED_VERSION);
    }

    @Test
    void redactsCredentialsInView() {
        String logon = FixMessageInspector.withComputedLengthAndChecksum(
                "8=FIX.4.4|9=0|35=A|34=1|49=CLIENT|52=20261003-10:00:00.000|56=SIM|98=0|108=30|553=trader|554=hunter2|10=000|");

        FixMessageInspector.Result result = inspector.inspect(logon, Optional.empty());

        assertThat(result.valid()).isTrue();
        assertThat(result.view().rawRedacted()).contains("554=***").contains("553=***").doesNotContain("hunter2");
        assertThat(result.view().value(554)).contains(FixMessageRedactor.MASK);
        assertThat(result.view().sha256()).hasSize(64);
    }

    @Test
    void validatesFix42AndFixtApplicationMessages() {
        String nos42 = FixMessageInspector.withComputedLengthAndChecksum(
                "8=FIX.4.2|9=0|35=D|34=2|49=CLIENT|52=20261003-10:00:00.000|56=SIM|11=A|21=1|55=IBM|54=2|"
                        + "60=20261003-10:00:00.000|38=10|40=1|10=000|");
        String nos50 = FixMessageInspector.withComputedLengthAndChecksum(
                "8=FIXT.1.1|9=0|35=D|34=2|49=CLIENT|52=20261003-10:00:00.000|56=SIM|1128=9|11=A|55=IBM|54=2|"
                        + "60=20261003-10:00:00.000|38=10|40=1|10=000|");

        assertThat(inspector.inspect(nos42, Optional.empty()).issues()).isEmpty();
        FixMessageInspector.Result fixt = inspector.inspect(nos50, Optional.empty());
        assertThat(fixt.issues()).isEmpty();
        assertThat(fixt.version()).isEqualTo(FixVersion.FIX50SP2);
    }

    @ParameterizedTest
    @EnumSource(FixVersion.class)
    void loadsBundledDictionariesForEveryVersion(FixVersion version) {
        assertThat(DictionaryRegistry.shared().application(version).isMsgType("D")).isTrue();
        assertThat(DictionaryRegistry.shared().transport(version).isMsgType("A")).isTrue();
    }
}
