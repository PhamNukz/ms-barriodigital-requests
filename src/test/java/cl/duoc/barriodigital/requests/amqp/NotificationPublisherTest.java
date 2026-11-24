package cl.duoc.barriodigital.requests.amqp;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.domain.Visita;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sin Spring, sin broker (E2): verifica a que exchange y con que routing key sale cada comando.
 * Las keys de topic tienen que calzar con los bindings de notify (email.*, crew.#).
 */
class NotificationPublisherTest {

    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final NotificationPublisher publisher = new NotificationPublisher(rabbit, new RabbitProperties(
            new RabbitProperties.Exchanges("cmd.direct", "cmd.topic", "cmd.dead.dlx"),
            null,
            new RabbitProperties.RoutingKeys("email.send", "email.*", "email.dlq",
                    "crew.ticket", "crew.#", "crew.dlq",
                    "certificate.gen", "certificate.*", "certificate.dlq",
                    "email.resuelto", "crew.visita.agendada")));

    private CommandEnvelope enviadoA(String exchange, String routingKey) {
        ArgumentCaptor<CommandEnvelope> env = ArgumentCaptor.forClass(CommandEnvelope.class);
        verify(rabbit).convertAndSend(eq(exchange), eq(routingKey), env.capture());
        return env.getValue();
    }

    private static Tramite tramite(long id) {
        Tramite t = new Tramite(9L, "vecino1@duocuc.cl", "Poda", "Calle 1");
        try {
            Field f = Tramite.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return t;
    }

    @Test
    void resuelto_se_difunde_por_topic() {
        publisher.difundirResuelto(tramite(7L));

        CommandEnvelope env = enviadoA("cmd.topic", "email.resuelto");
        assertEquals("email.send", env.type());
        assertEquals(7L, env.data().get("tramiteId"));
        assertEquals("RESUELTO", env.data().get("tipoNotificacion"));
    }

    @Test
    void visita_agendada_se_difunde_por_topic_con_key_jerarquica() {
        publisher.difundirVisitaAgendada(8L, "Calle 1");

        CommandEnvelope env = enviadoA("cmd.topic", "crew.visita.agendada");
        assertEquals("crew.ticket", env.type());
        assertEquals("Calle 1", env.data().get("direccion"));
    }

    @Test
    void comando_puntual_sigue_por_direct() {
        publisher.notificarEmail(tramite(9L), "ADMITIDO", null);

        enviadoA("cmd.direct", "email.send");
        verifyNoMoreInteractions(rabbit);
    }

    /** notify arma el correo con esto, sin volver a consultar a requests. */
    @Test
    void el_correo_lleva_destinatario_direccion_visita_y_motivo() {
        Tramite t = tramite(10L);
        t.cambiarEstado(EstadoTramite.ADMITIDO, "func1");
        t.proponerVisita(LocalDate.of(2030, 1, 15), Visita.Franja.TARDE, "Cuadrilla Norte", null, LocalDate.of(2030, 1, 1));

        publisher.notificarEmail(t, "VISITA_PROPUESTA", null);
        Map<String, Object> data = enviadoA("cmd.direct", "email.send").data();
        assertEquals("vecino1@duocuc.cl", data.get("destinatario"));
        assertEquals("Calle 1", data.get("direccion"));
        Map<?, ?> visita = (Map<?, ?>) data.get("visita");
        assertEquals("2030-01-15", visita.get("fecha"));
        assertEquals("TARDE", visita.get("franja"));
        assertEquals("PROPUESTA", visita.get("estado"));
        assertFalse(data.containsKey("motivo"));

        clearInvocations(rabbit);
        publisher.notificarEmail(t, "RECHAZADO", "Fuera de la comuna");
        assertEquals("Fuera de la comuna", enviadoA("cmd.direct", "email.send").data().get("motivo"));
    }
}
