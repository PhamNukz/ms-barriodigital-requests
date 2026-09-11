package cl.duoc.barriodigital.requests.service.event;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;

public record TramiteEstadoCambiadoEvent(Tramite tramite, EstadoTramite estadoAnterior, String usuario) {
}
