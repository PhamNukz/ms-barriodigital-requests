package cl.duoc.barriodigital.requests.kafka;

import java.time.Instant;

/**
 * Evento publicado en el topico "requests.events" en cada cambio relevante del
 * tramite. Lo consumen ms-barriodigital-audit (timeline) y ms-barriodigital-report
 * (KPIs); ninguno de los dos escribe de vuelta en la base de requests -- Database
 * per Service, se enteran solo por el topico.
 */
public record TramiteEvent(
        Tipo type,
        String eventId,
        Instant timestamp,
        String traceId,
        Long tramiteId,
        Long tipoId,
        String vecinoUsername,
        String estadoAnterior,
        String estadoNuevo,
        String usuario,
        // opcional: la nota del funcionario, o "a nombre de" si un funcionario lo ingreso
        String nota) {

    /** Envelope del Caso (seccion 8): el consumidor sabe que paso sin inferirlo de estadoAnterior == null. */
    public enum Tipo { TRAMITE_CREADO, ESTADO_CAMBIADO }
}
