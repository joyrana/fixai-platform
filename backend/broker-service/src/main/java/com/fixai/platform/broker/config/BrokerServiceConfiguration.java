package com.fixai.platform.broker.config;

import com.fixai.platform.broker.adapter.out.audit.AuditAdapters;
import com.fixai.platform.broker.adapter.out.persistence.JdbcBrokerRepository;
import com.fixai.platform.broker.adapter.out.persistence.JdbcSessionConfigRepository;
import com.fixai.platform.broker.adapter.out.workflow.WorkflowApprovalGateway;
import com.fixai.platform.broker.application.port.outbound.ApprovalGateway;
import com.fixai.platform.broker.application.port.outbound.AuditPort;
import com.fixai.platform.broker.application.port.outbound.BrokerRepositoryPort;
import com.fixai.platform.broker.application.port.outbound.SessionConfigRepository;
import com.fixai.platform.broker.application.port.outbound.UnitOfWork;
import com.fixai.platform.broker.application.service.SessionConfigService;
import com.fixai.platform.broker.domain.session.SessionConfigPolicy;
import com.fixai.platform.fixcore.FixSessionSpecValidator;
import com.fixai.platform.web.AuditOutbox;
import com.fixai.platform.web.CurrentActor;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration
public class BrokerServiceConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public BrokerRepositoryPort brokerRepository(JdbcClient jdbc) {
        return new JdbcBrokerRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionConfigRepository sessionConfigRepository(JdbcClient jdbc) {
        return new JdbcSessionConfigRepository(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditPort auditPort(ObjectProvider<AuditOutbox> outbox, CurrentActor actors) {
        AuditOutbox available = outbox.getIfAvailable();
        return available != null ? AuditAdapters.outbox(available, actors) : AuditAdapters.log(actors);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalGateway approvalGateway(RestClient.Builder builder, @Value("${fixai.broker.workflow-url}") String workflowUrl) {
        return new WorkflowApprovalGateway(builder.baseUrl(workflowUrl).build());
    }

    @Bean
    public UnitOfWork unitOfWork(TransactionTemplate transactions) {
        return new UnitOfWork() {
            @Override
            public <T> T call(java.util.function.Supplier<T> work) {
                return transactions.execute(status -> work.get());
            }
        };
    }

    @Bean
    public SessionConfigService sessionConfigService(SessionConfigRepository configs, BrokerRepositoryPort brokers,
                                                     ApprovalGateway approvals, AuditPort audit, UnitOfWork unitOfWork, Clock clock) {
        return new SessionConfigService(configs, brokers, approvals, audit,
                new SessionConfigPolicy(new FixSessionSpecValidator()), unitOfWork, clock);
    }
}
