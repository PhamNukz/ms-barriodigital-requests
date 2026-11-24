package cl.duoc.barriodigital.requests.service.event;

import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.kafka.AuditEntry;

/**
 * Accion sobre un tramite que no cambia su estado (responsable, visita, aviso en camino).
 * Siempre queda en audit.timeline; si trae {@code correo}, ademas se le avisa al vecino.
 *
 * @param correo tipoNotificacion del email al vecino, o null si no se le avisa.
 */
public record TramiteAccionEvent(Tramite tramite, String usuario, AuditEntry.Accion accion,
                                 String detalle, String nota, String correo) {
}
