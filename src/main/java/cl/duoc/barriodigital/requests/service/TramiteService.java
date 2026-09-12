package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.client.CatalogClient;
import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.repo.TramiteRepository;
import cl.duoc.barriodigital.requests.service.event.TramiteCreadoEvent;
import cl.duoc.barriodigital.requests.service.event.TramiteEstadoCambiadoEvent;
import cl.duoc.barriodigital.requests.web.TramiteDtos.CrearRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class TramiteService {

    private final TramiteRepository repo;
    private final CatalogClient catalogClient;
    private final ApplicationEventPublisher events;
    /** Zona con la que se define "hoy" para el cupo diario (dia civil local, no UTC). */
    private final ZoneId zona;

    public TramiteService(TramiteRepository repo, CatalogClient catalogClient, ApplicationEventPublisher events,
                          @Value("${barriodigital.zona-horaria:America/Santiago}") String zonaHoraria) {
        this.repo = repo;
        this.catalogClient = catalogClient;
        this.events = events;
        this.zona = ZoneId.of(zonaHoraria);
    }

    @Transactional(readOnly = true)
    public List<Tramite> listar(String username, boolean puedeVerTodos,
                                 EstadoTramite estado, Instant desde, Instant hasta) {
        List<Tramite> base = puedeVerTodos
                ? repo.findAll()
                : repo.findAllByVecinoUsernameOrderByFechaIngresoDesc(username);

        // ponytail: filtro en memoria; si el volumen de tramites crece, pasar a
        // JPA Specifications en vez de listas completas.
        return base.stream()
                .filter(t -> estado == null || t.getEstado() == estado)
                .filter(t -> desde == null || !t.getFechaIngreso().isBefore(desde))
                .filter(t -> hasta == null || !t.getFechaIngreso().isAfter(hasta))
                .toList();
    }

    @Transactional(readOnly = true)
    public Tramite obtener(Long id) {
        return repo.findById(id).orElseThrow(() -> new NoSuchElementException("Tramite " + id + " no existe"));
    }

    public record CupoInfo(int cupoDiario, long admitidosHoy, long disponible) {
    }

    /** Cupo del dia para un tipo de tramite -- lo consulta el vecino antes de ingresar uno nuevo. */
    @Transactional(readOnly = true)
    public CupoInfo cupoDeHoy(Long tipoId, String bearer) {
        CatalogClient.TipoTramiteView tipo = catalogClient.obtenerTipo(tipoId, bearer);
        long admitidosHoy = admitidosHoy(tipoId);
        long disponible = Math.max(0, tipo.cupoDiario() - admitidosHoy);
        return new CupoInfo(tipo.cupoDiario(), admitidosHoy, disponible);
    }

    @Transactional
    public Tramite crear(CrearRequest req, String vecinoUsername) {
        Tramite tramite = repo.save(new Tramite(req.tipoId(), vecinoUsername, req.descripcion(), req.direccion()));
        events.publishEvent(new TramiteCreadoEvent(tramite));
        return tramite;
    }

    @Transactional
    public Tramite cambiarEstado(Long id, EstadoTramite nuevo, String funcionario, String bearer) {
        Tramite tramite = obtener(id);
        if (nuevo == EstadoTramite.ADMITIDO) {
            verificarCupoDisponible(tramite.getTipoId(), bearer);
        }
        EstadoTramite anterior = tramite.getEstado();
        tramite.cambiarEstado(nuevo, funcionario);
        events.publishEvent(new TramiteEstadoCambiadoEvent(tramite, anterior, funcionario));
        return tramite;
    }

    private void verificarCupoDisponible(Long tipoId, String bearer) {
        CatalogClient.TipoTramiteView tipo = catalogClient.obtenerTipo(tipoId, bearer);
        if (admitidosHoy(tipoId) >= tipo.cupoDiario()) {
            throw new CupoExcedidoException(
                    "Cupo diario agotado para " + tipo.nombre() + " (" + tipo.cupoDiario() + "/dia)");
        }
    }

    private long admitidosHoy(Long tipoId) {
        Instant ahora = Instant.now();
        return repo.countByTipoIdAndFechaAdmisionBetween(tipoId, inicioDelDiaLocal(zona, ahora), ahora);
    }

    /**
     * Comienzo del dia civil local (no del dia UTC).
     *
     * <p>Antes se contaba sobre el dia UTC, asi que el cupo diario se reiniciaba a
     * las 21:00 de Chile y las admisiones de la noche anterior se descontaban del
     * cupo del dia siguiente. Paso en produccion: dos tramites admitidos a las
     * 22:10 del 11-sep (01:10 UTC del 12) dejaron sin cupo el 12-sep.
     */
    static Instant inicioDelDiaLocal(ZoneId zona, Instant ahora) {
        return ahora.atZone(zona).toLocalDate().atStartOfDay(zona).toInstant();
    }
}
