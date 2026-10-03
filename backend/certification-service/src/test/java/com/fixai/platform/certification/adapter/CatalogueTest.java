package com.fixai.platform.certification.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fixai.platform.certification.adapter.out.catalogue.ClasspathScenarioCatalogue;
import com.fixai.platform.certification.adapter.out.catalogue.ScenarioYamlParser;
import com.fixai.platform.certification.application.engine.ScenarioLinter;
import com.fixai.platform.certification.application.service.ReportHtmlRenderer;
import com.fixai.platform.certification.domain.scenario.Scenario;
import com.fixai.platform.certification.domain.scenario.ScenarioCategory;
import com.fixai.platform.certification.domain.scenario.Step;
import com.fixai.platform.fixcore.FixVersion;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogueTest {

    private final ScenarioYamlParser parser = new ScenarioYamlParser();

    @Test
    void shippedCatalogueLoadsLintsAndCoversEveryCategoryAndVersion() {
        ClasspathScenarioCatalogue catalogue =
                new ClasspathScenarioCatalogue("classpath*:scenarios/*.yaml", "classpath*:suites/*.yaml");

        assertThat(catalogue.scenarios()).hasSize(24);
        assertThat(catalogue.scenarios()).extracting(Scenario::category).containsAll(EnumSet.allOf(ScenarioCategory.class));
        assertThat(catalogue.scenarios()).allSatisfy(s -> assertThat(s.fixVersions()).containsAll(EnumSet.allOf(FixVersion.class)));
        assertThat(catalogue.catalogueHash()).hasSize(64);
        assertThat(catalogue.suite("full-certification").orElseThrow().scenarioIds()).hasSize(24);
    }

    @Test
    void parserRejectsUnknownKeysStepsAndChecks() {
        assertThatThrownBy(() -> parser.parseScenario("x", base() + "unexpected: 1\n"))
                .hasMessageContaining("unknown key 'unexpected'");
        assertThatThrownBy(() -> parser.parseScenario("x", base() + "steps:\n  - teleport: {}\n"))
                .hasMessageContaining("unknown step type 'teleport'");
        assertThatThrownBy(() -> parser.parseScenario("x", base()
                + "steps:\n  - expect: {msgType: '8', assert: {ExecType: {roughly: '0'}}}\n"))
                .hasMessageContaining("unknown check 'roughly'");
        assertThatThrownBy(() -> parser.parseScenario("x", base() + "steps:\n  - pause: {ms: 999999}\n"))
                .hasMessageContaining("pause must be");
    }

    @Test
    void linterReportsUnknownFieldsMessageTypesAndUncapturedVariables() {
        Scenario scenario = parser.parseScenario("x", base() + """
                steps:
                  - send: {msgType: D, fields: {ClientOrderId: "x"}}
                  - expect: {msgType: ZZ, assert: {Bogus: {present: true}}}
                  - expect: {msgType: '8', match: {ClOrdID: "${var:neverCaptured}"}}
                """);

        List<String> problems = new ScenarioLinter().lint(scenario);

        assertThat(problems).anySatisfy(p -> assertThat(p).contains("ClientOrderId"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("MsgType ZZ"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("neverCaptured"));
    }

    @Test
    void parserMapsVersionSpecificEqualsWithDefault() {
        Scenario scenario = parser.parseScenario("x", base() + """
                steps:
                  - expect:
                      msgType: '8'
                      direction: outbound
                      assert:
                        ExecType: {equals: {FIX42: '2', default: 'F'}}
                """);
        Step.Expect expect = (Step.Expect) scenario.steps().get(0);

        assertThat(expect.assertions().get("ExecType").expectedFor(FixVersion.FIX42)).isEqualTo("2");
        assertThat(expect.assertions().get("ExecType").expectedFor(FixVersion.FIX50SP2)).isEqualTo("F");
        assertThat(expect.direction().name()).isEqualTo("OUTBOUND");
    }

    @Test
    void reportRendererEscapesHtml() {
        assertThat(new ReportHtmlRendererProbe().escape("<script>alert('x')</script>&\""))
                .isEqualTo("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&amp;&quot;");
    }

    private static String base() {
        return """
                id: T-1
                version: 1
                title: test
                category: POSITIVE
                fixVersions: [FIX44]
                """;
    }

    /** Exposes the package-private escaper for testing. */
    private static final class ReportHtmlRendererProbe {
        String escape(String value) {
            try {
                var method = ReportHtmlRenderer.class.getDeclaredMethod("e", String.class);
                method.setAccessible(true);
                return (String) method.invoke(null, value);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
