package cl.duoc.barriodigital.requests.service.event;

import cl.duoc.barriodigital.requests.amqp.NotificationPublisher;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.kafka.RequestsEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

/**
 * Publica a RabbitMQ/Kafka solo despues de que la transaccion de BD hizo
 * commit (AFTER_COMMIT) -- evita notificar un cambio que despues se revierte,
 * y evita que audit/report vean el evento antes de que exista la fila en
 * requests. Aqui vive la regla de que estado dispara que comando (seccion 8
 * del caso).
 *
 * <p>Por que @Async: los listeners AFTER_COMMIT corren inline en el hilo del
 * request HTTP. Con los brokers caidos, kafka.send() bloquea hasta max.block.ms
 * esperando metadata y RabbitMQ espera su connection timeout, asi que el usuario
 * se comia esa espera completa y el API Gateway cortaba con 503 -- pese a que el
 * cambio de estado YA estaba commiteado en Oracle. Publicar en otro hilo
 * desacopla la respuesta HTTP de la disponibilidad de la mensajeria.
 *
 * <p>Por que se tragan las excepciones: cuando llegamos aca la transaccion ya
 * hizo commit y la BD es la fuente de verdad. Un broker caido degrada las
 * notificaciones, pero no puede convertir una operacion exitosa en un error
 * para el usuario. Se registra en el log para poder detectarlo.
 *
 * <p>ponytail: esto entrega "at-most-once" -- si el broker esta caido el evento
 * se pierde y solo queda en el log. Para entrega garantizada el patron correcto
 * es un outbox transaccional (guardar el evento en la misma transaccion de BD y
 * que un poller lo publique con reintentos); no se implementa aqui porque para
 * el alcance del caso basta con no bloquear ni fallar el request.
 */
@Component
public class TramiteEventRelay {

    private static final Logger log = LoggerFactory.getLogger(TramiteEventRelay.class);

    private final NotificationPublisher notifications;
    private final RequestsEventPublisher events;

    public TramiteEventRelay(NotificationPublisher notifications, RequestsEventPublisher events) {
        this.notifications = notifications;
        this.events = events;
    }

    @Async("eventRelayExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alCrear(TramiteCreadoEvent evt) {
        Tramite t = evt.tramite();
        publicarSinRomper("tramite creado " + t.getId(), () -> {
            events.publicar(t, null, t.getVecinoUsername());
            notifications.generarCertificado(t.getId(), "comprobante de ingreso");
        });
    }

    @Async("eventRelayExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alCambiarEstado(TramiteEstadoCambiadoEvent evt) {
        Tramite t = evt.tramite();
        publicarSinRomper("tramite " + t.getId() + " -> " + t.getEstado(), () -> {
            events.publicar(t, evt.estadoAnterior().name(), evt.usuario());

            switch (t.getEstado()) {
                case ADMITIDO -> notifications.notificarEmail(t.getId(), "ADMITIDO");
                case EN_TERRENO -> {
                    notifications.notificarEmail(t.getId(), "VISITA_AGENDADA");
                    notifications.notificarCuadrilla(t.getId(), t.getDireccion());
                }
                case RESUELTO -> {
                    notifications.notificarEmail(t.getId(), "RESUELTO");
                    notifications.generarCertificado(t.getId(), "certificado de resolucion");
                }
                default -> { /* INGRESADO, EN_GESTION, RECHAZADO: sin comando en esta version */ }
            }
        });
    }

    private void publicarSinRomper(String contexto, Runnable publicacion) {
        try {
            publicacion.run();
        } catch (RuntimeException e) {
            log.error("No se pudo publicar el evento ({}): la BD ya commiteo, "
                    + "el evento se pierde. Revisar RabbitMQ/Kafka.", contexto, e);
        }
    }
}
