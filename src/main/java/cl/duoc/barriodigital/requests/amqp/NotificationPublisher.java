package cl.duoc.barriodigital.requests.amqp;

import cl.duoc.barriodigital.requests.domain.Contacto;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.domain.Visita;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * El {@code type} del envelope es el tipo de comando, y se toma de la routing key canonica del comando
 * (la de cmd.direct) para no repetir el nombre en el codigo. No depende del exchange: notify procesa
 * igual un email.send venga por donde venga. Todos los nombres salen de {@link RabbitProperties}.
 */
@Component
public class NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);

    private final RabbitTemplate rabbit;
    private final RabbitProperties props;

    public NotificationPublisher(RabbitTemplate rabbit, RabbitProperties props) {
        this.rabbit = rabbit;
        this.props = props;
    }

    /** @param motivo solo para RECHAZADO (la nota del cambio de estado); null en el resto. */
    public void notificarEmail(Tramite t, String tipoNotificacion, String motivo) {
        if (ocultoAlVecino(t, tipoNotificacion)) return;
        publicar(props.exchanges().direct(), props.routingKeys().emailSend(), props.routingKeys().emailSend(),
                datosCorreo(t, tipoNotificacion, motivo));
    }

    /**
     * Un tramite que el Admin oculto al vecino no le llega por correo: si le avisaramos, le
     * revelariamos lo que en la app le responde 404. La cuadrilla y el certificado siguen igual.
     */
    private static boolean ocultoAlVecino(Tramite t, String tipo) {
        if (t.isVisibleVecino()) return false;
        log.info("Tramite {} oculto al vecino: no se envia el correo {}", t.getId(), tipo);
        return true;
    }

    /** Correos que hablan de la visita: solo estos la llevan, con su codigo. */
    static final Set<String> CORREOS_DE_VISITA =
            Set.of("VISITA_PROPUESTA", "VISITA_AGENDADA", "VISITA_CONFIRMADA", "EN_CAMINO");

    /**
     * Lo que notify necesita para escribir el correo sin volver a consultar a requests.
     * La visita va solo en los correos de visita: un RECHAZADO o RESUELTO que la mencione
     * confunde (el vecino creeria que la visita sigue en pie).
     */
    static Map<String, Object> datosCorreo(Tramite t, String tipoNotificacion, String motivo) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tramiteId", t.getId());
        data.put("tipoNotificacion", tipoNotificacion);
        data.put("destinatario", t.getVecinoUsername());
        List<String> cc = t.getContactos().stream().map(Contacto::getCorreo).toList();
        if (!cc.isEmpty()) data.put("cc", cc);
        data.put("direccion", t.getDireccion() == null ? "" : t.getDireccion());
        Visita v = t.getVisita();
        if (v != null && CORREOS_DE_VISITA.contains(tipoNotificacion)) {
            Map<String, Object> visita = new LinkedHashMap<>();
            visita.put("fecha", v.getFecha().toString());
            visita.put("franja", v.getFranja().name());
            visita.put("cuadrilla", v.getCuadrilla());
            visita.put("nota", v.getNota());
            visita.put("estado", v.getEstado().name());
            visita.put("codigo", v.getCodigo());
            data.put("visita", visita);
        }
        if (motivo != null && !motivo.isBlank()) data.put("motivo", motivo);
        return data;
    }

    public void notificarCuadrilla(Long tramiteId, String direccion) {
        publicar(props.exchanges().direct(), props.routingKeys().crewTicket(), props.routingKeys().crewTicket(), Map.of(
                "tramiteId", tramiteId,
                "direccion", direccion == null ? "" : direccion));
    }

    public void generarCertificado(Long tramiteId, String motivo) {
        publicar(props.exchanges().direct(), props.routingKeys().certificateGen(), props.routingKeys().certificateGen(), Map.of(
                "tramiteId", tramiteId,
                "motivo", motivo));
    }

    /** Difunde por cmd.topic que el tramite quedo resuelto (cae en email.*). */
    public void difundirResuelto(Tramite t) {
        if (ocultoAlVecino(t, "RESUELTO")) return;
        publicar(props.exchanges().topic(), props.routingKeys().emailSend(), props.routingKeys().emailResuelto(),
                datosCorreo(t, "RESUELTO", null));
    }

    /** Difunde por cmd.topic que se agendo la visita en terreno (cae en crew.#). */
    public void difundirVisitaAgendada(Tramite t) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tramiteId", t.getId());
        data.put("direccion", t.getDireccion() == null ? "" : t.getDireccion());
        // El vecino le pide el codigo a la cuadrilla en la puerta: asi sabe que es la municipalidad.
        if (t.getVisita() != null) data.put("codigo", t.getVisita().getCodigo());
        publicar(props.exchanges().topic(), props.routingKeys().crewTicket(), props.routingKeys().crewVisitaAgendada(), data);
    }

    private void publicar(String exchange, String tipo, String routingKey, Map<String, Object> data) {
        String tramiteId = String.valueOf(data.get("tramiteId"));
        var envelope = new CommandEnvelope(
                tipo, UUID.randomUUID().toString(), Instant.now(),
                UUID.randomUUID().toString(), tramiteId, data);
        rabbit.convertAndSend(exchange, routingKey, envelope);
    }
}
