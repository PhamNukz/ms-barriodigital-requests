package cl.duoc.barriodigital.requests.service.event;

import cl.duoc.barriodigital.requests.amqp.NotificationPublisher;
import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.kafka.AuditEntry;
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

        verify(notif).notificarEmail(eq(t), eq("ADMITIDO"), isNull());
        verify(notif, never()).notificarCuadrilla(anyLong(), any());
        verify(events).publicar(eq(t), eq("INGRESADO"), eq("func1"), isNull());
    }

    @Test
    void al_pasar_a_terreno_notifica_email_y_cuadrilla() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        var relay = new TramiteEventRelay(notif, events);

        Tramite t = tramiteEn(EstadoTramite.EN_TERRENO);
        relay.alCambiarEstado(new TramiteEstadoCambiadoEvent(t, EstadoTramite.EN_GESTION, "func1"));

        verify(notif).notificarEmail(t, "VISITA_AGENDADA", null);
        verify(notif).difundirVisitaAgendada(t);
        verify(notif, never()).notificarCuadrilla(anyLong(), any());
    }

    @Test
    void al_resolver_notifica_email_y_certificado() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        var relay = new TramiteEventRelay(notif, events);

        Tramite t = tramiteEn(EstadoTramite.RESUELTO);
        relay.alCambiarEstado(new TramiteEstadoCambiadoEvent(t, EstadoTramite.EN_TERRENO, "func1"));

        verify(notif).difundirResuelto(t);
        verify(notif, never()).notificarEmail(any(), eq("RESUELTO"), any());
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
        doThrow(new IllegalStateException("kafka caido")).when(events).publicar(any(), any(), any(), any());
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
        relay.alCrear(new TramiteCreadoEvent(t, "vecino1"));

        // el evento a Kafka igual se intento antes de que fallara la notificacion
        verify(events).publicar(eq(t), isNull(), eq("vecino1"), isNull());
    }

    @Test
    void al_crear_genera_comprobante_de_ingreso() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        var relay = new TramiteEventRelay(notif, events);

        Tramite t = new Tramite(9L, "vecino1", "Bache", "Calle Falsa 123");
        fijarId(t, 1L);
        relay.alCrear(new TramiteCreadoEvent(t, "vecino1"));

        verify(notif).generarCertificado(eq(t.getId()), eq("comprobante de ingreso"));
        verify(events).publicar(eq(t), isNull(), eq("vecino1"), isNull());
    }

    /** La auditoria tiene que poder decir "el funcionario X lo ingreso a nombre del vecino Y". */
    @Test
    void al_crear_a_nombre_de_un_vecino_el_actor_es_el_funcionario() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        var relay = new TramiteEventRelay(notif, events);

        Tramite t = new Tramite(9L, "vecino1", "Bache", "Calle Falsa 123", "func1");
        fijarId(t, 1L);
        relay.alCrear(new TramiteCreadoEvent(t, "func1"));

        verify(events).publicar(eq(t), isNull(), eq("func1"), eq("Ingresado por func1 a nombre de vecino1"));
    }

    /** Antes RECHAZADO no avisaba nada: el vecino se enteraba solo si entraba a mirar. */
    @Test
    void al_rechazar_envia_correo_con_el_motivo() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        var relay = new TramiteEventRelay(notif, events);

        Tramite t = tramiteEn(EstadoTramite.INGRESADO);
        t.cambiarEstado(EstadoTramite.RECHAZADO, "func1");
        relay.alCambiarEstado(new TramiteEstadoCambiadoEvent(t, EstadoTramite.INGRESADO, "func1", "Fuera de la comuna"));

        verify(notif).notificarEmail(t, "RECHAZADO", "Fuera de la comuna");
    }

    @Test
    void una_accion_se_audita_y_avisa_al_vecino_solo_si_trae_correo() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        var relay = new TramiteEventRelay(notif, events);
        Tramite t = tramiteEn(EstadoTramite.EN_GESTION);

        relay.alRegistrarAccion(new TramiteAccionEvent(t, "vecino1", AuditEntry.Accion.RESPONDER_VISITA,
                "Visita CONFIRMADA", null, null));
        relay.alRegistrarAccion(new TramiteAccionEvent(t, "func1", AuditEntry.Accion.PROPONER_VISITA,
                "Visita ...", null, "VISITA_PROPUESTA"));

        verify(events).auditar(t, "vecino1", AuditEntry.Accion.RESPONDER_VISITA, "Visita CONFIRMADA", null);
        verify(notif).notificarEmail(t, "VISITA_PROPUESTA", null);
        verifyNoMoreInteractions(notif);
    }

    @Test
    void la_nota_del_cambio_de_estado_viaja_al_evento() {
        NotificationPublisher notif = mock(NotificationPublisher.class);
        RequestsEventPublisher events = mock(RequestsEventPublisher.class);
        var relay = new TramiteEventRelay(notif, events);

        Tramite t = tramiteEn(EstadoTramite.ADMITIDO);
        relay.alCambiarEstado(new TramiteEstadoCambiadoEvent(t, EstadoTramite.INGRESADO, "func1", "Revisaremos el lunes"));

        verify(events).publicar(eq(t), eq("INGRESADO"), eq("func1"), eq("Revisaremos el lunes"));
    }
}
