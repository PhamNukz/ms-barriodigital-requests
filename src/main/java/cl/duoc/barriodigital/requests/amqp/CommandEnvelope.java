package cl.duoc.barriodigital.requests.amqp;

import java.time.Instant;
import java.util.Map;

/** Mismo envelope que consume ms-barriodigital-notify (duplicado, ver security). */
public record CommandEnvelope(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        Map<String, Object> data) {
}
