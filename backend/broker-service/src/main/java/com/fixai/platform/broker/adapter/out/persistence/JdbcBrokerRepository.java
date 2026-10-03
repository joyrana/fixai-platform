package com.fixai.platform.broker.adapter.out.persistence;

import com.fixai.platform.broker.application.port.outbound.BrokerRepositoryPort;
import com.fixai.platform.broker.application.service.BrokerAlreadyExistsException;
import com.fixai.platform.broker.domain.broker.Broker;
import com.fixai.platform.broker.domain.broker.BrokerStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL broker repository. The unique index on broker code closes the check-then-insert race. */
public class JdbcBrokerRepository implements BrokerRepositoryPort {

    private final JdbcClient jdbc;

    public JdbcBrokerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Broker save(Broker broker) {
        try {
            jdbc.sql("""
                    INSERT INTO broker (id, broker_code, name, endpoint, status, created_at, updated_at)
                    VALUES (:id, :code, :name, :endpoint, :status, :createdAt, :updatedAt)
                    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, endpoint = EXCLUDED.endpoint,
                        status = EXCLUDED.status, updated_at = EXCLUDED.updated_at
                    """)
                    .param("id", broker.id()).param("code", broker.brokerCode()).param("name", broker.name())
                    .param("endpoint", broker.endpoint()).param("status", broker.status().name())
                    .param("createdAt", Timestamp.from(broker.createdAt())).param("updatedAt", Timestamp.from(broker.updatedAt()))
                    .update();
        } catch (DuplicateKeyException exception) {
            throw new BrokerAlreadyExistsException("Broker code already exists: " + broker.brokerCode());
        }
        return broker;
    }

    @Override
    public Optional<Broker> findById(UUID brokerId) {
        return jdbc.sql("SELECT * FROM broker WHERE id = :id").param("id", brokerId).query(JdbcBrokerRepository::map).optional();
    }

    @Override
    public Optional<Broker> findByBrokerCode(String brokerCode) {
        return jdbc.sql("SELECT * FROM broker WHERE LOWER(broker_code) = LOWER(:code)").param("code", brokerCode)
                .query(JdbcBrokerRepository::map).optional();
    }

    @Override
    public List<Broker> findAll() {
        return jdbc.sql("SELECT * FROM broker ORDER BY created_at DESC").query(JdbcBrokerRepository::map).list();
    }

    @Override
    public void deleteById(UUID brokerId) {
        try {
            jdbc.sql("DELETE FROM broker WHERE id = :id").param("id", brokerId).update();
        } catch (DataIntegrityViolationException exception) {
            throw new BrokerInUseException("Broker has session configurations; retire them instead of deleting the broker");
        }
    }

    private static Broker map(ResultSet rs, int row) throws SQLException {
        return new Broker(rs.getObject("id", UUID.class), rs.getString("broker_code"), rs.getString("name"),
                rs.getString("endpoint"), BrokerStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    /** Deleting a broker referenced by session configurations is refused. */
    public static final class BrokerInUseException extends RuntimeException {
        public BrokerInUseException(String message) {
            super(message);
        }
    }
}
