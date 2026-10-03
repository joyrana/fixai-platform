package com.fixai.platform.certification.application.port.out;

import com.fixai.platform.certification.domain.scenario.Scenario;
import com.fixai.platform.certification.domain.scenario.Suite;
import java.util.List;
import java.util.Optional;

/** Read-only, versioned catalogue of registered scenarios and suites. */
public interface ScenarioCatalogue {

    List<Scenario> scenarios();

    Optional<Scenario> scenario(String id);

    List<Suite> suites();

    Optional<Suite> suite(String id);

    /** SHA-256 over all scenario and suite definitions; recorded on every run for reproducibility. */
    String catalogueHash();
}
