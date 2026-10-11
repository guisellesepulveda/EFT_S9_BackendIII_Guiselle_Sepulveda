package cl.bancoxyz.platform.outbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.idempotency.ProcessedEventStore;
import cl.bancoxyz.platform.kafka.PlatformKafkaAutoConfiguration;

/** Outbox + consumidores idempotentes. Se activan solo en servicios con JdbcTemplate y Kafka. */
@AutoConfiguration(after = {JdbcTemplateAutoConfiguration.class, PlatformKafkaAutoConfiguration.class})
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnBean({JdbcTemplate.class, EventCodec.class})
public class PlatformOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxService outboxService(JdbcTemplate jdbc, EventCodec codec) {
        return new OutboxService(jdbc, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessedEventStore processedEventStore(JdbcTemplate jdbc) {
        return new ProcessedEventStore(jdbc);
    }

    @AutoConfiguration
    @ConditionalOnBean({KafkaTemplate.class, JdbcTemplate.class})
    @ConditionalOnProperty(prefix = "platform.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    static class Publisher {

        @Bean
        OutboxPublisher outboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
                                        PlatformTransactionManager txManager,
                                        @Value("${platform.outbox.batch-size:50}") int batchSize,
                                        ObjectProvider<io.micrometer.tracing.Tracer> tracer,
                                        ObjectProvider<io.micrometer.tracing.propagation.Propagator> propagator) {
            return new OutboxPublisher(jdbc, kafka, new TransactionTemplate(txManager), batchSize,
                    tracer.getIfAvailable(), propagator.getIfAvailable());
        }
    }
}
