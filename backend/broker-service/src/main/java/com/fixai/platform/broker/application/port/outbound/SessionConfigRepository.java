package com.fixai.platform.broker.application.port.outbound;

import com.fixai.platform.broker.domain.session.SessionConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionConfigRepository {

    void insert(SessionConfig config);

    /**
     * Compare-and-set update: succeeds only if the stored content version and status still equal the expected values.
     * The content version changes only when configuration values change (it is part of the approved payload); status
     * transitions keep it.
     */
    boolean update(SessionConfig config, long expectedVersion, com.fixai.platform.broker.domain.session.SessionConfigStatus expectedStatus);

    Optional<SessionConfig> find(UUID id);

    List<SessionConfig> findByBroker(UUID brokerId);

    /** True if another non-retired configuration uses the same environment, version and CompID pair. */
    boolean duplicateExists(SessionConfig config);
}
