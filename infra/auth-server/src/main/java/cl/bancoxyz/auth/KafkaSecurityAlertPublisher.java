package cl.bancoxyz.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import cl.bancoxyz.platform.events.EventCodec;
import cl.bancoxyz.platform.events.EventEnvelope;
import cl.bancoxyz.platform.events.EventTypes;
import cl.bancoxyz.platform.events.Topics;
import cl.bancoxyz.platform.events.payload.SecurityAlert;

/** Publica la alerta; un fallo de Kafka se registra pero nunca rompe el login. */
@Component
class KafkaSecurityAlertPublisher implements SecurityAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaSecurityAlertPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final EventCodec codec;

    KafkaSecurityAlertPublisher(KafkaTemplate<String, String> kafka, EventCodec codec) {
        this.kafka = kafka;
        this.codec = codec;
    }

    @Override
    public void publish(SecurityAlert alert) {
        try {
            String json = codec.write(EventEnvelope.of(EventTypes.SECURITY_ALERT, alert));
            kafka.send(Topics.SECURITY_ALERTS, alert.subject(), json).whenComplete((r, e) -> {
                if (e != null) {
                    log.error("No se pudo publicar la alerta de seguridad: {}", e.toString());
                }
            });
        } catch (RuntimeException e) {
            log.error("Error publicando alerta de seguridad: {}", e.toString());
        }
    }
}
