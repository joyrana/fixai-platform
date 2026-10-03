package com.fixai.platform.workflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixai.platform.workflow.adapter.persistence.JdbcApprovalRepository;
import com.fixai.platform.workflow.adapter.persistence.JdbcAuditRepository;
import com.fixai.platform.workflow.application.port.ApprovalRepository;
import com.fixai.platform.workflow.application.port.AuditRepository;
import com.fixai.platform.workflow.application.service.ApprovalService;
import com.fixai.platform.workflow.application.service.AuditService;
import com.fixai.platform.workflow.domain.approval.ApprovalPolicy;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
public class WorkflowConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ApprovalRepository approvalRepository(JdbcClient jdbc, ObjectMapper json) {
        return new JdbcApprovalRepository(jdbc, json);
    }

    @Bean
    public AuditRepository auditRepository(JdbcClient jdbc, JdbcTemplate template, TransactionTemplate tx, ObjectMapper json) {
        return new JdbcAuditRepository(jdbc, template, tx, json);
    }

    @Bean
    public AuditService auditService(AuditRepository repository, Clock clock) {
        return new AuditService(repository, clock);
    }

    @Bean
    public ApprovalService approvalService(ApprovalRepository approvals, AuditService audit, Clock clock) {
        return new ApprovalService(approvals, audit, new ApprovalPolicy(), clock);
    }

    @Bean
    public ExpirySweeper expirySweeper(ApprovalService approvals) {
        return new ExpirySweeper(approvals);
    }

    @Bean
    public OpenAPI workflowOpenApi() {
        return new OpenAPI().info(new Info().title("FIXAI Workflow API").version("v1")
                .description("Payload-bound human approvals and the hash-chained audit log"));
    }

    /** Periodically expires overdue approvals so queues never show stale actionable items. */
    public static class ExpirySweeper {
        private final ApprovalService approvals;

        ExpirySweeper(ApprovalService approvals) {
            this.approvals = approvals;
        }

        @Scheduled(fixedDelayString = "${fixai.workflow.expiry-sweep-interval:PT1M}")
        public void sweep() {
            approvals.expireDue();
        }
    }
}
