package com.fixai.platform.certification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixai.platform.certification.adapter.out.audit.LoggingAuditAdapter;
import com.fixai.platform.certification.adapter.out.broker.UnconfiguredSessionConfigAdapter;
import com.fixai.platform.certification.adapter.out.catalogue.ClasspathScenarioCatalogue;
import com.fixai.platform.certification.adapter.out.fix.QuickFixTransport;
import com.fixai.platform.certification.adapter.out.metrics.MicrometerRunMetrics;
import com.fixai.platform.certification.adapter.out.persistence.JdbcRunRepository;
import com.fixai.platform.certification.application.engine.ScenarioExecutor;
import com.fixai.platform.certification.application.port.out.AuditPort;
import com.fixai.platform.certification.application.port.out.RunMetrics;
import com.fixai.platform.certification.application.port.out.RunRepository;
import com.fixai.platform.certification.application.port.out.ScenarioCatalogue;
import com.fixai.platform.certification.application.port.out.SessionConfigPort;
import com.fixai.platform.certification.application.service.CertificationReportService;
import com.fixai.platform.certification.application.service.CertificationRunService;
import com.fixai.platform.certification.application.service.CertificationSettings;
import com.fixai.platform.fixcore.FixMessageRedactor;
import com.fixai.platform.simulator.engine.FixSimulator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties(CertificationProperties.class)
public class CertificationConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ScenarioCatalogue scenarioCatalogue() {
        return new ClasspathScenarioCatalogue("classpath*:scenarios/*.yaml", "classpath*:suites/*.yaml");
    }

    @Bean
    public RunRepository runRepository(JdbcClient jdbc, JdbcTemplate template, TransactionTemplate tx, ObjectMapper json) {
        return new JdbcRunRepository(jdbc, template, tx, json);
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditPort auditPort() {
        return new LoggingAuditAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionConfigPort sessionConfigPort() {
        return new UnconfiguredSessionConfigAdapter();
    }

    @Bean
    public RunMetrics runMetrics(MeterRegistry registry) {
        return new MicrometerRunMetrics(registry);
    }

    @Bean
    public ScenarioExecutor scenarioExecutor(CertificationProperties properties, Clock clock) {
        FixMessageRedactor redactor = new FixMessageRedactor();
        return new ScenarioExecutor(() -> new QuickFixTransport(clock, redactor), clock, properties.scenarioTimeout(),
                properties.reconnectIntervalSeconds());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService certificationRunPool(CertificationProperties properties) {
        return new ThreadPoolExecutor(properties.maxConcurrentRuns(), properties.maxConcurrentRuns(), 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.runQueueCapacity()), named("cert-run"));
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService certificationScenarioPool(CertificationProperties properties) {
        return Executors.newFixedThreadPool(properties.maxConcurrentRuns() * properties.maxParallelScenarios(), named("cert-scenario"));
    }

    @Bean
    public CertificationRunService certificationRunService(
            ScenarioCatalogue catalogue, RunRepository runs, ScenarioExecutor executor, SessionConfigPort sessionConfigs,
            AuditPort audit, RunMetrics metrics, CertificationProperties properties, Clock clock,
            ExecutorService certificationRunPool, ExecutorService certificationScenarioPool) {
        CertificationSettings settings = new CertificationSettings(properties.simulator().host(),
                properties.simulator().port(), properties.maxParallelScenarios(), properties.scenarioTimeout(),
                properties.defaultHeartbeatSeconds(), properties.reconnectIntervalSeconds(), properties.engineVersion());
        return new CertificationRunService(catalogue, runs, executor, sessionConfigs, audit, metrics, settings, clock,
                certificationRunPool, certificationScenarioPool);
    }

    @Bean
    public CertificationReportService certificationReportService(CertificationRunService runs, RunRepository repository, Clock clock) {
        return new CertificationReportService(runs, repository, clock);
    }

    /** In-process synthetic counterparty for local development and tests. Never enable in shared environments. */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "fixai.certification.simulator", name = "embedded", havingValue = "true")
    public FixSimulator embeddedFixSimulator(CertificationProperties properties) {
        return new FixSimulator(properties.simulator().port());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns(ApplicationReadyEvent event) {
        event.getApplicationContext().getBean(CertificationRunService.class).recoverInterruptedRuns();
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
