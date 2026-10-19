package cl.duoc.barriodigital.requests.amqp;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Publica los 3 comandos de la seccion 8 del caso.
 * <ul>
 *   <li>cmd.direct: comando con destinatario conocido, routing key exacta (email.send, crew.ticket,
 *       certificate.gen).</li>
 *   <li>cmd.topic: difusion de un cambio de estado con routing key jerarquica (email.resuelto,
 *       crew.visita.agendada). Le llega a cualquier cola cuyo patron calce (email.*, crew.#) sin que
 *       este productor sepa cuales son: sumar un consumidor no toca esta clase.</li>
 * </ul>
 * El {@code type} del envelope es el tipo de comando y no depende del exchange: notify procesa igual
 * un email.send venga por donde venga.
 */
@Component
public class NotificationPublisher {

    private final RabbitTemplate rabbit;
    private final RabbitProperties props;

    public NotificationPublisher(RabbitTemplate rabbit, RabbitProperties props) {
        this.rabbit = rabbit;
        this.props = props;
    }

    public void notificarEmail(Long tramiteId, String tipoNotificacion) {
        publicar(props.exchanges().direct(), "email.send", props.routingKeys().emailSend(), Map.of(
                "tramiteId", tramiteId,
                "tipoNotificacion", tipoNotificacion));
    }

    public void notificarCuadrilla(Long tramiteId, String direccion) {
        publicar(props.exchanges().direct(), "crew.ticket", props.routingKeys().crewTicket(), Map.of(
                "tramiteId", tramiteId,
                "direccion", direccion == null ? "" : direccion));
    }

    public void generarCertificado(Long tramiteId, String motivo) {
        publicar(props.exchanges().direct(), "certificate.gen", props.routingKeys().certificateGen(), Map.of(
                "tramiteId", tramiteId,
                "motivo", motivo));
    }

    /** Difunde por cmd.topic que el tramite quedo resuelto (cae en email.*). */
    public void difundirResuelto(Long tramiteId) {
        publicar(props.exchanges().topic(), "email.send", props.routingKeys().emailResuelto(), Map.of(
                "tramiteId", tramiteId,
                "tipoNotificacion", "RESUELTO"));
    }

    /** Difunde por cmd.topic que se agendo la visita en terreno (cae en crew.#). */
    public void difundirVisitaAgendada(Long tramiteId, String direccion) {
        publicar(props.exchanges().topic(), "crew.ticket", props.routingKeys().crewVisitaAgendada(), Map.of(
                "tramiteId", tramiteId,
                "direccion", direccion == null ? "" : direccion));
    }

    private void publicar(String exchange, String tipo, String routingKey, Map<String, Object> data) {
        String tramiteId = String.valueOf(data.get("tramiteId"));
        var envelope = new CommandEnvelope(
                tipo, UUID.randomUUID().toString(), Instant.now(),
                UUID.randomUUID().toString(), tramiteId, data);
        rabbit.convertAndSend(exchange, routingKey, envelope);
    }
}
