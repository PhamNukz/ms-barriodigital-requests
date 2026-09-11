package cl.duoc.barriodigital.requests.kafka;

import java.time.Instant;

/**
 * Evento publicado en el topico "requests.events" en cada cambio relevante del
 * tramite. Lo consumen ms-barriodigital-audit (timeline) y ms-barriodigital-report
 * (KPIs); ninguno de los dos escribe de vuelta en la base de requests -- Database
 * per Service, se enteran solo por el topico.
 */
public record TramiteEvent(
        String eventId,
        Instant timestamp,
        String traceId,
        Long tramiteId,
        Long tipoId,
        String vecinoUsername,
        String estadoAnterior,
        String estadoNuevo,
        String usuario) {
}
