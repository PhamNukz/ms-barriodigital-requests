package cl.duoc.barriodigital.requests.service.event;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;

/** @param nota comentario opcional que el funcionario deja al cambiar el estado. */
public record TramiteEstadoCambiadoEvent(Tramite tramite, EstadoTramite estadoAnterior, String usuario, String nota) {

    public TramiteEstadoCambiadoEvent(Tramite tramite, EstadoTramite estadoAnterior, String usuario) {
        this(tramite, estadoAnterior, usuario, null);
    }
}
