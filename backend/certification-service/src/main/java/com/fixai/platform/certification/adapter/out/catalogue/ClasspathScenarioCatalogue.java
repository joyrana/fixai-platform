package com.fixai.platform.certification.adapter.out.catalogue;

import com.fixai.platform.certification.application.engine.ScenarioLinter;
import com.fixai.platform.certification.application.port.out.ScenarioCatalogue;
import com.fixai.platform.certification.domain.scenario.Scenario;
import com.fixai.platform.certification.domain.scenario.Suite;
import com.fixai.platform.fixcore.FixMessageRedactor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Loads, lints and freezes the scenario catalogue from {@code classpath:scenarios/**} and {@code classpath:suites/**}.
 * An invalid catalogue prevents the service from starting.
 */
public final class ClasspathScenarioCatalogue implements ScenarioCatalogue {

    private final Map<String, Scenario> scenarios = new LinkedHashMap<>();
    private final Map<String, Suite> suites = new LinkedHashMap<>();
    private final String hash;

    public ClasspathScenarioCatalogue(String scenarioPattern, String suitePattern) {
        ScenarioYamlParser parser = new ScenarioYamlParser();
        StringBuilder digestInput = new StringBuilder();
        for (Resource resource : resources(scenarioPattern)) {
            String content = read(resource);
            digestInput.append(resource.getFilename()).append('\n').append(content).append('\n');
            Scenario scenario = parser.parseScenario(resource.getFilename(), content);
            if (scenarios.put(scenario.id(), scenario) != null) {
                throw new IllegalStateException("Duplicate scenario id " + scenario.id());
            }
        }
        for (Resource resource : resources(suitePattern)) {
            String content = read(resource);
            digestInput.append(resource.getFilename()).append('\n').append(content).append('\n');
            Suite suite = parser.parseSuite(resource.getFilename(), content);
            suites.put(suite.id(), suite);
        }
        Map<String, List<String>> suiteMembers = new LinkedHashMap<>();
        suites.values().forEach(s -> suiteMembers.put(s.id(), s.scenarioIds()));
        ScenarioLinter.requireValid(List.copyOf(scenarios.values()), suiteMembers);
        this.hash = FixMessageRedactor.sha256(digestInput.toString());
    }

    @Override
    public List<Scenario> scenarios() {
        return List.copyOf(scenarios.values());
    }

    @Override
    public Optional<Scenario> scenario(String id) {
        return Optional.ofNullable(scenarios.get(id));
    }

    @Override
    public List<Suite> suites() {
        return List.copyOf(suites.values());
    }

    @Override
    public Optional<Suite> suite(String id) {
        return Optional.ofNullable(suites.get(id));
    }

    @Override
    public String catalogueHash() {
        return hash;
    }

    private static List<Resource> resources(String pattern) {
        try {
            List<Resource> found = new ArrayList<>(Arrays.asList(new PathMatchingResourcePatternResolver().getResources(pattern)));
            found.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
            return found;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to list " + pattern, exception);
        }
    }

    private static String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + resource.getFilename(), exception);
        }
    }
}
