package cl.bancoxyz.payments;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


/** Consumidor "espía" de pruebas: guarda los eventos de los tópicos indicados para poder afirmar sobre ellos. */
class KafkaTap implements AutoCloseable {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, List<JsonNode>> events = new ConcurrentHashMap<>();
    private final Thread poller;
    private final String bootstrap;
    private volatile boolean running = true;

    KafkaTap(String bootstrap, String... topics) {
        this.bootstrap = bootstrap;
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "tap-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1000, // detecta pronto las particiones añadidas al crear los tópicos
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topics));
        poller = new Thread(() -> {
            try (consumer) {
                while (running) {
                    for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
                        try {
                            events.computeIfAbsent(r.topic(), t -> new CopyOnWriteArrayList<>()).add(mapper.readTree(r.value()));
                        } catch (Exception ignored) {
                            // mensajes no JSON (pruebas de DLT) no interesan aquí
                        }
                    }
                }
            }
        }, "kafka-tap");
        poller.setDaemon(true);
        poller.start();
    }

    List<JsonNode> events(String topic) {
        return events.getOrDefault(topic, List.of());
    }

    /** Eventos de un pago concreto en un tópico. */
    List<JsonNode> forPayment(String topic, UUID paymentId) {
        return events(topic).stream().filter(e -> paymentId.toString().equals(e.path("payload").path("paymentId").asText())).toList();
    }

    JsonNode await(String topic, Predicate<JsonNode> predicate) {
        JsonNode[] found = new JsonNode[1];
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(100)).until(() -> {
            found[0] = events(topic).stream().filter(predicate).findFirst().orElse(null);
            return found[0] != null;
        });
        return found[0];
    }

    JsonNode awaitForPayment(String topic, UUID paymentId) {
        return await(topic, e -> paymentId.toString().equals(e.path("payload").path("paymentId").asText()));
    }

    /** Espera a que el grupo haya consumido (y confirmado) todo lo publicado hasta ahora en el tópico. */
    void awaitGroupCaughtUp(String group, String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap));
             KafkaConsumer<String, String> probe = new KafkaConsumer<>(Map.of(
                     ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                     ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                     ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            List<TopicPartition> partitions = probe.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(topic, p.partition())).toList();
            Map<TopicPartition, Long> end = probe.endOffsets(partitions);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200)).until(() -> {
                Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
                return end.entrySet().stream().allMatch(e -> e.getValue() == 0
                        || (committed.get(e.getKey()) != null && committed.get(e.getKey()).offset() >= e.getValue()));
            });
        }
    }

    @Override
    public void close() {
        running = false;
    }
}
