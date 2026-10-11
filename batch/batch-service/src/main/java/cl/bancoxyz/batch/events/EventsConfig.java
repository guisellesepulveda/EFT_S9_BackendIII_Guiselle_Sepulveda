package cl.bancoxyz.batch.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import cl.bancoxyz.batch.config.BatchProperties;

@Configuration
public class EventsConfig {

    private static final Logger log = LoggerFactory.getLogger(EventsConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "batch.events", name = "enabled", havingValue = "true", matchIfMissing = true)
    NewTopic securityAlertsTopic() {
        return TopicBuilder.name("security.alerts").partitions(3).replicas(1).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "batch.events", name = "enabled", havingValue = "true", matchIfMissing = true)
    NewTopic batchJobEventsTopic(BatchProperties props) {
        // ≥ 3 particiones para poder escalar consumidores
        return TopicBuilder.name(props.events().topic()).partitions(3).replicas(1).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "batch.events", name = "enabled", havingValue = "true", matchIfMissing = true)
    JobEventPublisher kafkaJobEventPublisher(KafkaTemplate<String, String> kafkaTemplate, BatchProperties props,
                                             ObjectMapper objectMapper) {
        return new KafkaJobEventPublisher(kafkaTemplate, props.events().topic(), objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "batch.events", name = "enabled", havingValue = "false")
    JobEventPublisher noopJobEventPublisher() {
        return new JobEventPublisher() {
            @Override
            public void publish(String type, JobEventPayload payload) {
                log.debug("Eventos deshabilitados; se omite {}", type);
            }

            @Override
            public void publishAlert(String severity, String alertType, String subject, String detail) {
                log.debug("Eventos deshabilitados; se omite la alerta {}", alertType);
            }
        };
    }
}
