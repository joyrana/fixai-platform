package com.fixai.platform.certification.adapter.in.web;

import com.fixai.platform.certification.adapter.in.web.ApiDtos.ScenarioResponse;
import com.fixai.platform.certification.adapter.in.web.ApiDtos.SuiteResponse;
import com.fixai.platform.certification.adapter.in.web.ApiDtos.TestPlanRequest;
import com.fixai.platform.certification.adapter.in.web.ApiDtos.TestPlanValidation;
import com.fixai.platform.certification.application.port.in.StartRunCommand;
import com.fixai.platform.certification.application.port.out.ScenarioCatalogue;
import com.fixai.platform.certification.application.service.CertificationExceptions;
import com.fixai.platform.certification.application.service.CertificationRunService;
import com.fixai.platform.certification.domain.scenario.ScenarioCategory;
import com.fixai.platform.fixcore.FixVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Scenario catalogue", description = "Registered, versioned certification scenarios and suites")
public class ScenarioController {

    private final ScenarioCatalogue catalogue;
    private final CertificationRunService runs;

    public ScenarioController(ScenarioCatalogue catalogue, CertificationRunService runs) {
        this.catalogue = catalogue;
        this.runs = runs;
    }

    @GetMapping("/scenarios")
    @Operation(summary = "List registered scenarios, optionally filtered by FIX version and category")
    public List<ScenarioResponse> scenarios(
            @RequestParam(required = false) FixVersion fixVersion,
            @RequestParam(required = false) ScenarioCategory category) {
        return catalogue.scenarios().stream()
                .filter(s -> fixVersion == null || s.supports(fixVersion))
                .filter(s -> category == null || s.category() == category)
                .map(ScenarioResponse::of)
                .toList();
    }

    @GetMapping("/scenarios/{scenarioId}")
    @Operation(summary = "Get one scenario definition")
    public ScenarioResponse scenario(@PathVariable String scenarioId) {
        return catalogue.scenario(scenarioId).map(ScenarioResponse::of)
                .orElseThrow(() -> new CertificationExceptions.NotFound("Scenario", scenarioId));
    }

    @GetMapping("/suites")
    @Operation(summary = "List scenario suites")
    public List<SuiteResponse> suites() {
        return catalogue.suites().stream().map(SuiteResponse::of).toList();
    }

    @PostMapping("/test-plans/validate")
    @Operation(summary = "Validate a test plan (registered scenarios only, version compatibility) without running it")
    public TestPlanValidation validate(@Valid @RequestBody TestPlanRequest request) {
        try {
            List<String> ids = runs.validatePlan(new StartRunCommand(request.suiteId(), request.scenarioIds(),
                    request.fixVersion(), null, null, null, null));
            return new TestPlanValidation(true, List.of(), ids);
        } catch (CertificationExceptions.InvalidRequest exception) {
            return new TestPlanValidation(false, exception.problems(), List.of());
        }
    }
}
