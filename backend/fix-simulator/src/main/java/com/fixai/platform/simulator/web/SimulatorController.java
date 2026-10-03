package com.fixai.platform.simulator.web;

import com.fixai.platform.simulator.engine.FixSimulator;
import com.fixai.platform.simulator.engine.ReferenceData;
import com.fixai.platform.simulator.engine.SimulatorProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only simulator metadata. Defect descriptions are intentionally not exposed: they are evaluation ground truth.
 */
@RestController
@RequestMapping("/api/v1/simulator")
@Tag(name = "Simulator", description = "Synthetic FIX counterparty metadata")
public class SimulatorController {

    private final FixSimulator simulator;

    public SimulatorController(FixSimulator simulator) {
        this.simulator = simulator;
    }

    public record ProfileResponse(String name, String compId, boolean compliant) {
    }

    @GetMapping("/profiles")
    @Operation(summary = "List behaviour profiles and the TargetCompID that selects each")
    public List<ProfileResponse> profiles() {
        return Arrays.stream(SimulatorProfile.values())
                .map(p -> new ProfileResponse(p.name(), p.compId(), p.defect().isEmpty()))
                .toList();
    }

    @GetMapping("/reference-data")
    @Operation(summary = "Synthetic instruments and fixed reference prices")
    public Map<String, BigDecimal> referenceData() {
        return ReferenceData.prices();
    }

    @GetMapping("/sessions")
    @Operation(summary = "Active simulator sessions")
    public List<FixSimulator.SessionSnapshot> sessions() {
        return simulator.sessions();
    }
}
