package cl.duoc.barriodigital.requests.kafka;

import cl.duoc.barriodigital.requests.domain.Tramite;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class RequestsEventPublisher {

    private static final String TOPIC = "requests.events";

    private final KafkaTemplate<String, Object> kafka;

    public RequestsEventPublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    public void publicar(Tramite tramite, String estadoAnterior, String usuario) {
        var evento = new TramiteEvent(
                UUID.randomUUID().toString(),
                Instant.now(),
                UUID.randomUUID().toString(),
                tramite.getId(),
                tramite.getTipoId(),
                tramite.getVecinoUsername(),
                estadoAnterior,
                tramite.getEstado().name(),
                usuario);
        // key = tramiteId: todos los eventos de un mismo tramite van a la misma
        // particion y llegan en orden a audit/report.
        kafka.send(TOPIC, String.valueOf(tramite.getId()), evento);
    }
}
