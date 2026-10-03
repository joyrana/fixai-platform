package com.fixai.platform.certification.domain.scenario;

import java.util.List;

public record Suite(String id, String title, String description, List<String> scenarioIds) {

    public Suite {
        scenarioIds = List.copyOf(scenarioIds);
    }
}
