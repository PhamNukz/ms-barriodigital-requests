package cl.duoc.barriodigital.requests.service.event;

import cl.duoc.barriodigital.requests.amqp.NotificationPublisher;
import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.kafka.RequestsEventPublisher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sin Spring, sin broker: verifica que cada estado dispara el comando correcto
 * (seccion 8 del caso) y que siempre se publica el evento a Kafka.
 */
class TramiteEventRelayTest {

    /** El id es @GeneratedValue; se fija por reflexion para simular una entidad ya persistida. */
    private static void fijarId(Tramite t, long id) {
        try {
            Field f = Tramite.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private Tramite tramiteEn(EstadoTramite estado) {
        Tramite t = new Tramite(9L, "vecino1", "Bache", "Calle Falsa 123");
        fijarId(t, 1L);
        if (estado != EstadoTramite.INGRESADO) {
            // recorre el camino valido hasta el estado pedido
            for (EstadoTramite paso : new EstadoTramite[]{EstadoTramite.ADMITIDO, EstadoTramite.EN_GESTION,
                    EstadoTramite.EN_TERRENO, EstadoTramite.RESUELTO}) {
                t.cambiarEstado(paso, "func1");
                if (paso == estado) break;
            }
        }
        return t;
    }

    @Test
    void al_admitir_notifica_email() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        var relay = new TramiteEventRelay(notif, events);

        Tramite t = tramiteEn(EstadoTramite.ADMITIDO);
        relay.alCambiarEstado(new TramiteEstadoCambiadoEvent(t, EstadoTramite.INGRESADO, "func1"));

        verify(notif).notificarEmail(eq(1L), eq("ADMITIDO"));
        verify(notif, never()).notificarCuadrilla(anyLong(), any());
        verify(events).publicar(eq(t), eq("INGRESADO"), eq("func1"));
    }

    @Test
    void al_pasar_a_terreno_notifica_email_y_cuadrilla() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        var relay = new TramiteEventRelay(notif, events);

        Tramite t = tramiteEn(EstadoTramite.EN_TERRENO);
        relay.alCambiarEstado(new TramiteEstadoCambiadoEvent(t, EstadoTramite.EN_GESTION, "func1"));

        verify(notif).notificarEmail(1L, "VISITA_AGENDADA");
        verify(notif).notificarCuadrilla(eq(1L), any());
    }

    @Test
    void al_resolver_notifica_email_y_certificado() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        var relay = new TramiteEventRelay(notif, events);

        Tramite t = tramiteEn(EstadoTramite.RESUELTO);
        relay.alCambiarEstado(new TramiteEstadoCambiadoEvent(t, EstadoTramite.EN_TERRENO, "func1"));

        verify(notif).notificarEmail(1L, "RESUELTO");
        verify(notif).generarCertificado(1L, "certificado de resolucion");
    }

    /**
     * Regresion: con los brokers caidos el publish explotaba y, al correr inline
     * en el hilo del request (AFTER_COMMIT), convertia en error una operacion
     * que en la BD ya estaba commiteada. Ahora se registra y se sigue.
     */
    @Test
    void si_kafka_falla_no_rompe_la_operacion() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        doThrow(new IllegalStateException("kafka caido")).when(events).publicar(any(), any(), any());
        var relay = new TramiteEventRelay(notif, events);

        Tramite t = tramiteEn(EstadoTramite.ADMITIDO);
        // no lanza: si lanzara, este test falla
        relay.alCambiarEstado(new TramiteEstadoCambiadoEvent(t, EstadoTramite.INGRESADO, "func1"));
    }

    @Test
    void si_rabbitmq_falla_no_rompe_la_creacion() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        doThrow(new IllegalStateException("rabbit caido")).when(notif).generarCertificado(anyLong(), any());
        var relay = new TramiteEventRelay(notif, events);

        Tramite t = new Tramite(9L, "vecino1", "Bache", "Calle Falsa 123");
        fijarId(t, 1L);
        relay.alCrear(new TramiteCreadoEvent(t));

        // el evento a Kafka igual se intento antes de que fallara la notificacion
        verify(events).publicar(eq(t), isNull(), eq("vecino1"));
    }

    @Test
    void al_crear_genera_comprobante_de_ingreso() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        var relay = new TramiteEventRelay(notif, events);

        Tramite t = new Tramite(9L, "vecino1", "Bache", "Calle Falsa 123");
        fijarId(t, 1L);
        relay.alCrear(new TramiteCreadoEvent(t));

        verify(notif).generarCertificado(eq(t.getId()), eq("comprobante de ingreso"));
        verify(events).publicar(eq(t), isNull(), eq("vecino1"));
    }
}
