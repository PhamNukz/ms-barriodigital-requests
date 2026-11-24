package cl.duoc.barriodigital.requests.kafka;

import cl.duoc.barriodigital.requests.domain.Tramite;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publica cada cambio del tramite en los dos topicos (nombres en barriodigital.kafka.topics.*):
 * el hecho de negocio en requests.events y la accion de la persona en audit.timeline.
 */
@Component
public class RequestsEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RequestsEventPublisher.class);

    private final KafkaTemplate<String, Object> kafka;
    private final String topicoEventos;
    private final String topicoAuditoria;
    private final String origen;

    public RequestsEventPublisher(KafkaTemplate<String, Object> kafka,
                                  @Value("${barriodigital.kafka.topics.requests-events}") String topicoEventos,
                                  @Value("${barriodigital.kafka.topics.audit-timeline}") String topicoAuditoria,
                                  @Value("${spring.application.name}") String origen) {
        this.kafka = kafka;
        this.topicoEventos = topicoEventos;
        this.topicoAuditoria = topicoAuditoria;
        this.origen = origen;
    }

    /**
     * @param usuario quien hizo el cambio (el actor); el vecino afectado va aparte, en vecinoUsername.
     *                Si al crear no coinciden, es un funcionario que lo ingreso a nombre del vecino.
     */
    public void publicar(Tramite tramite, String estadoAnterior, String usuario, String nota) {
        boolean creacion = estadoAnterior == null;
        String estadoNuevo = tramite.getEstado().name();
        var evento = new TramiteEvent(
                creacion ? TramiteEvent.Tipo.TRAMITE_CREADO : TramiteEvent.Tipo.ESTADO_CAMBIADO,
                UUID.randomUUID().toString(),
                Instant.now(),
                UUID.randomUUID().toString(),
                tramite.getId(),
                tramite.getTipoId(),
                tramite.getVecinoUsername(),
                estadoAnterior,
                estadoNuevo,
                usuario,
                nota);
        // key = tramiteId: todos los eventos de un mismo tramite van a la misma
        // particion y llegan en orden a audit/report.
        enviar(topicoEventos, String.valueOf(tramite.getId()), evento, evento.eventId(), tramite.getId());

        var entrada = new AuditEntry(
                UUID.randomUUID().toString(),
                evento.timestamp(),
                usuario,
                creacion ? AuditEntry.Accion.CREAR : AuditEntry.Accion.CAMBIAR_ESTADO,
                tramite.getId(),
                tramite.getVecinoUsername(),
                creacion ? "Ingresado en " + estadoNuevo : estadoAnterior + " -> " + estadoNuevo,
                nota,
                origen);
        // key = eventId, no tramiteId: audit.timeline es compact, y la compactacion deja solo el
        // ultimo registro de cada key. Con tramiteId se perderia la historia de cada tramite.
        enviar(topicoAuditoria, entrada.eventId(), entrada, entrada.eventId(), tramite.getId());
    }

    /** send() es asincrono: sin este callback, una falla del broker despues de enviar no dejaba rastro. */
    private void enviar(String topico, String key, Object valor, String eventId, Long tramiteId) {
        kafka.send(topico, key, valor).whenComplete((res, ex) -> {
            if (ex != null) {
                log.error("No se publico en {}: eventId={} tramiteId={}", topico, eventId, tramiteId, ex);
                return;
            }
            RecordMetadata m = res.getRecordMetadata();
            log.info("Publicado en {}-{}@{}: eventId={} tramiteId={}",
                    m.topic(), m.partition(), m.offset(), eventId, tramiteId);
        });
    }
}
