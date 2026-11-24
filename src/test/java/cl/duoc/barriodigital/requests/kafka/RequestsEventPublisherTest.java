package cl.duoc.barriodigital.requests.kafka;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Sin Spring, sin broker: a que topico y con que key sale cada evento. */
class RequestsEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final RequestsEventPublisher publisher =
            new RequestsEventPublisher(kafka, "requests.events", "audit.timeline", "ms-barriodigital-requests");

    @BeforeEach
    void sendNoTerminaNunca() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());
    }

    private static Tramite tramite(long id) {
        Tramite t = new Tramite(9L, "vecino1", "Bache", "Calle Falsa 123");
        try {
            Field f = Tramite.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return t;
    }

    private Object enviadoA(String topico, String key) {
        ArgumentCaptor<Object> valor = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(topico), eq(key), valor.capture());
        return valor.getValue();
    }

    @Test
    void cambio_de_estado_va_a_requests_events_con_key_tramiteId() {
        Tramite t = tramite(7L);
        t.cambiarEstado(EstadoTramite.ADMITIDO, "func1");

        publisher.publicar(t, "INGRESADO", "func1", "Revisaremos el lunes");

        TramiteEvent e = (TramiteEvent) enviadoA("requests.events", "7");
        assertEquals(TramiteEvent.Tipo.ESTADO_CAMBIADO, e.type());
        assertEquals("INGRESADO", e.estadoAnterior());
        assertEquals("ADMITIDO", e.estadoNuevo());
        assertEquals("Revisaremos el lunes", e.nota());
    }

    @Test
    void creacion_lleva_type_tramite_creado() {
        publisher.publicar(tramite(8L), null, "vecino1", null);

        TramiteEvent e = (TramiteEvent) enviadoA("requests.events", "8");
        assertEquals(TramiteEvent.Tipo.TRAMITE_CREADO, e.type());
    }

    /** key = eventId: con compact y key = tramiteId, Kafka borraria la historia del tramite. */
    @Test
    void la_accion_va_a_audit_timeline_con_key_eventId() {
        publisher.publicar(tramite(8L), null, "func1", "Ingresado por func1 a nombre de vecino1");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valor = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("audit.timeline"), key.capture(), valor.capture());
        AuditEntry a = (AuditEntry) valor.getValue();
        assertEquals(a.eventId(), key.getValue());
        assertEquals(AuditEntry.Accion.CREAR, a.accion());
        assertEquals("func1", a.actor());
        assertEquals("vecino1", a.vecinoUsername());
        assertEquals(8L, a.tramiteId());
        assertEquals("ms-barriodigital-requests", a.origen());
    }

    @Test
    void una_falla_asincrona_del_broker_queda_en_el_log_y_no_lanza() {
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker caido")));

        assertDoesNotThrow(() -> publisher.publicar(tramite(9L), null, "vecino1", null));
    }
}
