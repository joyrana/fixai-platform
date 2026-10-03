package com.fixai.platform.workflow.domain.approval;

import com.fixai.platform.workflow.domain.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The exact action an approval authorises. The hash binds the approval to these values: any change to the action,
 * target, environment or any argument produces a different hash and the approval cannot be consumed.
 */
public record ApprovalPayload(String action, String targetType, String targetId, String environment, Map<String, Object> arguments) {

    public ApprovalPayload {
        arguments = arguments == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public String canonical() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("action", action);
        document.put("targetType", targetType);
        document.put("targetId", targetId);
        document.put("environment", environment);
        document.put("arguments", arguments);
        return CanonicalJson.write(document);
    }

    public String hash() {
        return CanonicalJson.sha256(canonical());
    }
}
