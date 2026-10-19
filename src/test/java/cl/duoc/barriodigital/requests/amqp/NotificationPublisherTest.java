package cl.duoc.barriodigital.requests.amqp;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void resuelto_se_difunde_por_topic() {
        publisher.difundirResuelto(7L);

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
        publisher.notificarEmail(9L, "ADMITIDO");

        enviadoA("cmd.direct", "email.send");
        verifyNoMoreInteractions(rabbit);
    }
}
