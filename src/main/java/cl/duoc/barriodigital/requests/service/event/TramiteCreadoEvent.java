package cl.duoc.barriodigital.requests.service.event;

import cl.duoc.barriodigital.requests.domain.Tramite;

/** @param usuario quien lo ingreso: el propio vecino, o el funcionario que lo hizo a su nombre. */
public record TramiteCreadoEvent(Tramite tramite, String usuario) {
}
