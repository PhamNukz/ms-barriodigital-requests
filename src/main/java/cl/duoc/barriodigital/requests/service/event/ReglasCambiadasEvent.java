package cl.duoc.barriodigital.requests.service.event;

/** El Admin cambio las reglas de visibilidad. @param cambios "mesesHistorial 12 -> 24, ..." */
public record ReglasCambiadasEvent(String usuario, String cambios) {
}
