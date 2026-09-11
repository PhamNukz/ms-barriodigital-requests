package cl.duoc.barriodigital.requests.service.event;

import cl.duoc.barriodigital.requests.amqp.NotificationPublisher;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.kafka.RequestsEventPublisher;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

/**
 * Publica a RabbitMQ/Kafka solo despues de que la transaccion de BD hizo
 * commit (AFTER_COMMIT) -- evita notificar un cambio que despues se revierte,
 * y evita que audit/report vean el evento antes de que exista la fila en
 * requests. Aqui vive la regla de que estado dispara que comando (seccion 8
 * del caso).
 */
@Component
public class TramiteEventRelay {

    private final NotificationPublisher notifications;
    private final RequestsEventPublisher events;

    public TramiteEventRelay(NotificationPublisher notifications, RequestsEventPublisher events) {
        this.notifications = notifications;
        this.events = events;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alCrear(TramiteCreadoEvent evt) {
        Tramite t = evt.tramite();
        events.publicar(t, null, t.getVecinoUsername());
        notifications.generarCertificado(t.getId(), "comprobante de ingreso");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void alCambiarEstado(TramiteEstadoCambiadoEvent evt) {
        Tramite t = evt.tramite();
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
    }
}
