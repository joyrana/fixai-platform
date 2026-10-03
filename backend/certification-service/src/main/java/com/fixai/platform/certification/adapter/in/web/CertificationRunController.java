package com.fixai.platform.certification.adapter.in.web;

import com.fixai.platform.certification.adapter.in.web.ApiDtos.EvidenceResponse;
import com.fixai.platform.certification.adapter.in.web.ApiDtos.PageResponse;
import com.fixai.platform.certification.adapter.in.web.ApiDtos.RunResponse;
import com.fixai.platform.certification.adapter.in.web.ApiDtos.ScenarioExecutionDetail;
import com.fixai.platform.certification.adapter.in.web.ApiDtos.ScenarioExecutionResponse;
import com.fixai.platform.certification.adapter.in.web.ApiDtos.StartRunRequest;
import com.fixai.platform.certification.adapter.in.web.ApiDtos.StepResponse;
import com.fixai.platform.certification.application.port.in.StartRunCommand;
import com.fixai.platform.certification.application.port.out.RunRepository;
import com.fixai.platform.certification.application.service.CertificationExceptions;
import com.fixai.platform.certification.application.service.CertificationReport;
import com.fixai.platform.certification.application.service.CertificationReportService;
import com.fixai.platform.certification.application.service.CertificationRunService;
import com.fixai.platform.certification.application.service.ReportHtmlRenderer;
import com.fixai.platform.certification.domain.run.CertificationRun;
import com.fixai.platform.certification.domain.run.ScenarioExecution;
import com.fixai.platform.web.CorrelationIdFilter;
import com.fixai.platform.web.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/certification-runs")
@Tag(name = "Certification runs", description = "Start, monitor, cancel and replay certification runs; read evidence and reports")
public class CertificationRunController {

    private final CertificationRunService service;
    private final CertificationReportService reports;
    private final RunRepository repository;
    private final CurrentActor actors;

    public CertificationRunController(
            CertificationRunService service, CertificationReportService reports, RunRepository repository, CurrentActor actors) {
        this.service = service;
        this.reports = reports;
        this.repository = repository;
        this.actors = actors;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','CERTIFICATION_ENGINEER','AI_AGENT')")
    @Operation(summary = "Start a certification run (asynchronous; returns 202). Supports Idempotency-Key.")
    public ResponseEntity<RunResponse> start(
            @Valid @RequestBody StartRunRequest request,
            @Parameter(description = "Client-generated key; a retry with the same key and body returns the original run")
            @RequestHeader(value = "Idempotency-Key", required = false) @Pattern(regexp = "[A-Za-z0-9._:-]{8,128}") String idempotencyKey,
            HttpServletRequest http) {
        StartRunCommand command = new StartRunCommand(request.suiteId(), request.scenarioIds(), request.fixVersion(),
                request.target() == null ? null : request.target().type(),
                request.target() == null ? null : request.target().simulatorProfile(),
                request.target() == null ? null : request.target().sessionConfigId(),
                request.target() == null ? null : request.target().approvalId());
        CertificationRunService.StartResult result =
                service.start(command, actors.get(), CorrelationIdFilter.current(http), idempotencyKey);
        RunResponse body = RunResponse.of(result.run());
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(URI.create("/api/v1/certification-runs/" + body.id()))
                .body(body);
    }

    @GetMapping
    @Operation(summary = "List runs, newest first")
    public PageResponse<RunResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        List<RunResponse> items = service.list(page, size).stream().map(RunResponse::of).toList();
        return new PageResponse<>(items, page, size, service.count());
    }

    @GetMapping("/{runId}")
    @Operation(summary = "Get run status and verdict")
    public RunResponse get(@PathVariable UUID runId) {
        return RunResponse.of(service.get(runId));
    }

    @PostMapping("/{runId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','CERTIFICATION_ENGINEER')")
    @Operation(summary = "Request cancellation; running scenarios stop at the next step boundary")
    public RunResponse cancel(@PathVariable UUID runId, HttpServletRequest http) {
        return RunResponse.of(service.cancel(runId, actors.get(), CorrelationIdFilter.current(http)));
    }

    @GetMapping("/{runId}/scenarios")
    @Operation(summary = "Scenario executions of a run")
    public List<ScenarioExecutionResponse> scenarios(@PathVariable UUID runId) {
        return service.executions(runId).stream().map(ScenarioExecutionResponse::of).toList();
    }

    @GetMapping("/{runId}/scenarios/{executionId}")
    @Operation(summary = "One scenario execution with step results and assertion outcomes")
    public ScenarioExecutionDetail scenario(@PathVariable UUID runId, @PathVariable UUID executionId) {
        ScenarioExecution execution = execution(runId, executionId);
        List<StepResponse> steps = repository.steps(executionId).stream().map(StepResponse::of).toList();
        return new ScenarioExecutionDetail(ScenarioExecutionResponse.of(execution), steps);
    }

    @GetMapping("/{runId}/scenarios/{executionId}/evidence")
    @Operation(summary = "Redacted message-level evidence of a scenario execution, optionally filtered by MsgType")
    public PageResponse<EvidenceResponse> evidence(
            @PathVariable UUID runId,
            @PathVariable UUID executionId,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9]{1,4}") String msgType,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "200") @Min(1) @Max(1000) int size) {
        execution(runId, executionId);
        List<EvidenceResponse> all = repository.evidence(executionId).stream()
                .filter(r -> msgType == null || msgType.equals(r.msgType()))
                .map(EvidenceResponse::of)
                .toList();
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return new PageResponse<>(all.subList(from, to), page, size, all.size());
    }

    @PostMapping("/{runId}/replay-verification")
    @Operation(summary = "Re-evaluate every verdict offline from persisted evidence and verify the evidence digest")
    public CertificationRunService.ReplayReport replay(@PathVariable UUID runId, HttpServletRequest http) {
        return service.replay(runId, actors.get(), CorrelationIdFilter.current(http));
    }

    @GetMapping("/{runId}/report")
    @Operation(summary = "Certification report (JSON) built from persisted evidence")
    public CertificationReport report(@PathVariable UUID runId) {
        return reports.build(runId);
    }

    @GetMapping(value = "/{runId}/report.html", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Certification report (HTML)")
    public ResponseEntity<String> reportHtml(@PathVariable UUID runId) {
        return ResponseEntity.ok()
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .contentType(MediaType.TEXT_HTML)
                .body(new ReportHtmlRenderer().render(reports.build(runId)));
    }

    private ScenarioExecution execution(UUID runId, UUID executionId) {
        CertificationRun run = service.get(runId);
        return repository.scenarioExecution(executionId)
                .filter(e -> e.runId().equals(run.id()))
                .orElseThrow(() -> new CertificationExceptions.NotFound("Scenario execution", executionId));
    }
}
