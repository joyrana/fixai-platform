package com.fixai.platform.certification.adapter.in.web;

import com.fixai.platform.fixcore.FixMessageInspector;
import com.fixai.platform.fixcore.FixVersion;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Structural and dictionary validation of a single FIX message. This is the gate for any externally supplied or
 * AI-generated message: nothing is interpreted as FIX until it passes here. The response contains only the redacted
 * view; credentials in the input are never echoed.
 */
@RestController
@RequestMapping("/api/v1/fix")
@Tag(name = "FIX inspection", description = "Validate and decode a FIX message with the platform dictionaries")
public class FixInspectionController {

    private final FixMessageInspector inspector = new FixMessageInspector();

    public record InspectRequest(@NotBlank @Size(max = 65536) String raw, FixVersion expectedVersion) {
    }

    @PostMapping("/inspect")
    @Operation(summary = "Validate BodyLength, CheckSum, structure and dictionary rules; return issues and a redacted view")
    public FixMessageInspector.Result inspect(@Valid @RequestBody InspectRequest request) {
        return inspector.inspect(request.raw(), Optional.ofNullable(request.expectedVersion()));
    }
}
