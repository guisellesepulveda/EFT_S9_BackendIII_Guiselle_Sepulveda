package cl.bancoxyz.platform.kafka;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.Topics;

/**
 * Kafka común: productores idempotentes (acks=all), consumidores con reintentos con backoff exponencial y
 * Dead Letter Topic (<topic>.DLT), y creación de todos los tópicos (3 particiones) y sus DLT.
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(PlatformKafkaProperties.class)
public class PlatformKafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    EventCodec eventCodec(ObjectMapper mapper) {
        return new EventCodec(mapper);
    }

    @Bean
    @ConditionalOnBean(KafkaOperations.class)
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler platformKafkaErrorHandler(KafkaOperations<Object, Object> kafka, PlatformKafkaProperties props) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> new TopicPartition(Topics.dlt(record.topic()), record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(props.initialBackoff().toMillis(), props.multiplier());
        backOff.setMaxInterval(props.maxBackoff().toMillis());
        backOff.setMaxAttempts(props.maxRetries());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Datos corruptos: reintentar no ayuda → directo a la DLT
        handler.addNotRetryableExceptions(JsonProcessingException.class, IllegalArgumentException.class);
        return handler;
    }

    /** Todos los servicios declaran los mismos tópicos (KafkaAdmin es idempotente): evita depender del orden de arranque. */
    @Bean
    @ConditionalOnProperty(prefix = "platform.kafka", name = "topics-enabled", havingValue = "true", matchIfMissing = true)
    KafkaAdmin.NewTopics platformTopics() {
        List<NewTopic> topics = new ArrayList<>();
        for (String t : Topics.ALL) {
            topics.add(TopicBuilder.name(t).partitions(Topics.PARTITIONS).replicas(1).build());
            topics.add(TopicBuilder.name(Topics.dlt(t)).partitions(Topics.PARTITIONS).replicas(1).build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }
}
