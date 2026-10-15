package cl.duoc.barriodigital.requests.amqp;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Publica los 3 comandos de la seccion 8 del caso hacia cmd.direct. */
@Component
public class NotificationPublisher {

    private final RabbitTemplate rabbit;
    private final RabbitProperties props;

    public NotificationPublisher(RabbitTemplate rabbit, RabbitProperties props) {
        this.rabbit = rabbit;
        this.props = props;
    }

    public void notificarEmail(Long tramiteId, String tipoNotificacion) {
        publicar("email.send", props.routingKeys().emailSend(), Map.of(
                "tramiteId", tramiteId,
                "tipoNotificacion", tipoNotificacion));
    }

    public void notificarCuadrilla(Long tramiteId, String direccion) {
        publicar("crew.ticket", props.routingKeys().crewTicket(), Map.of(
                "tramiteId", tramiteId,
                "direccion", direccion == null ? "" : direccion));
    }

    public void generarCertificado(Long tramiteId, String motivo) {
        publicar("certificate.gen", props.routingKeys().certificateGen(), Map.of(
                "tramiteId", tramiteId,
                "motivo", motivo));
    }

    private void publicar(String tipo, String routingKey, Map<String, Object> data) {
        String tramiteId = String.valueOf(data.get("tramiteId"));
        var envelope = new CommandEnvelope(
                tipo, UUID.randomUUID().toString(), Instant.now(),
                UUID.randomUUID().toString(), tramiteId, data);
        rabbit.convertAndSend(props.exchanges().direct(), routingKey, envelope);
    }
}
