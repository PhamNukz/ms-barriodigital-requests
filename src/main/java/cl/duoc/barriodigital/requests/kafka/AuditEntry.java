package cl.duoc.barriodigital.requests.kafka;

import java.time.Instant;

/**
 * Entrada del topico "audit.timeline" (Caso seccion 9): quien (actor), que (accion,
 * detalle, nota), cuando (timestamp) y desde donde (origen). La consume
 * ms-barriodigital-audit, grupo audit-svc. TramiteEvent es el hecho de negocio
 * (estados, KPIs); esto es el registro de la accion de una persona.
 */
public record AuditEntry(
        String eventId,
        Instant timestamp,
        String actor,
        Accion accion,
        Long tramiteId,
        String vecinoUsername,
        String detalle,
        String nota,
        // ponytail: el servicio que registro la accion. La IP / user-agent del usuario
        // requiere que el BFF reenvie X-Forwarded-For; se agrega aqui cuando lo haga.
        String origen) {

    // audit guarda la accion como texto (VARCHAR2(30)): sumar valores no rompe su consumidor.
    public enum Accion { CREAR, CAMBIAR_ESTADO, ASIGNAR_RESPONSABLE, PROPONER_VISITA, RESPONDER_VISITA, AVISAR_EN_CAMINO }
}
