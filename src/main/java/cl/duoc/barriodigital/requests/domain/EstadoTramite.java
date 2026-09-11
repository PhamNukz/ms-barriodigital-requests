package cl.duoc.barriodigital.requests.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Flujo: INGRESADO -> ADMITIDO -> EN_GESTION -> EN_TERRENO -> RESUELTO | RECHAZADO.
 * El grafo es secuencial a proposito: EN_TERRENO solo es alcanzable pasando antes
 * por ADMITIDO, que es la regla que exige el caso ("no se puede pasar a EN_TERRENO
 * sin ADMITIR") sin necesitar una validacion aparte.
 */
public enum EstadoTramite {
    INGRESADO,
    ADMITIDO,
    EN_GESTION,
    EN_TERRENO,
    RESUELTO,
    RECHAZADO;

    private static final Map<EstadoTramite, Set<EstadoTramite>> TRANSICIONES = new EnumMap<>(EstadoTramite.class);
    static {
        TRANSICIONES.put(INGRESADO, EnumSet.of(ADMITIDO, RECHAZADO));
        TRANSICIONES.put(ADMITIDO, EnumSet.of(EN_GESTION, RECHAZADO));
        TRANSICIONES.put(EN_GESTION, EnumSet.of(EN_TERRENO, RECHAZADO));
        TRANSICIONES.put(EN_TERRENO, EnumSet.of(RESUELTO, RECHAZADO));
        TRANSICIONES.put(RESUELTO, EnumSet.noneOf(EstadoTramite.class));
        TRANSICIONES.put(RECHAZADO, EnumSet.noneOf(EstadoTramite.class));
    }

    public boolean puedeTransicionarA(EstadoTramite siguiente) {
        return TRANSICIONES.get(this).contains(siguiente);
    }
}
