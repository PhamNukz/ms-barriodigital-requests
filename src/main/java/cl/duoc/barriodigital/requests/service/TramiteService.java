package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.client.CatalogClient;
import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.domain.TramiteHito;
import cl.duoc.barriodigital.requests.repo.TramiteHitoRepository;
import cl.duoc.barriodigital.requests.repo.TramiteRepository;
import cl.duoc.barriodigital.requests.service.event.TramiteCreadoEvent;
import cl.duoc.barriodigital.requests.service.event.TramiteEstadoCambiadoEvent;
import cl.duoc.barriodigital.requests.web.TramiteDtos.CrearRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TramiteService {

    /** Camino normal del tramite, en orden. RECHAZADO se agrega solo si ocurrio. */
    private static final List<EstadoTramite> FLUJO = List.of(
            EstadoTramite.INGRESADO, EstadoTramite.ADMITIDO, EstadoTramite.EN_GESTION,
            EstadoTramite.EN_TERRENO, EstadoTramite.RESUELTO);

    private final TramiteRepository repo;
    private final TramiteHitoRepository hitos;
    private final CatalogClient catalogClient;
    private final ApplicationEventPublisher events;
    /** Zona con la que se define "hoy" para el cupo diario (dia civil local, no UTC). */
    private final ZoneId zona;

    public TramiteService(TramiteRepository repo, TramiteHitoRepository hitos, CatalogClient catalogClient,
                          ApplicationEventPublisher events,
                          @Value("${barriodigital.zona-horaria:America/Santiago}") String zonaHoraria) {
        this.repo = repo;
        this.hitos = hitos;
        this.catalogClient = catalogClient;
        this.events = events;
        this.zona = ZoneId.of(zonaHoraria);
    }

    @Transactional(readOnly = true)
    public List<Tramite> listar(String username, boolean puedeVerTodos,
                                 EstadoTramite estado, Instant desde, Instant hasta) {
        // findAll() sin Sort devolvia las filas en el orden que quisiera la base: a
        // Admin/Funcionario la tabla le llegaba desordenada. El vecino ya venia
        // ordenado por su propia query; ahora ambos caminos coinciden.
        List<Tramite> base = puedeVerTodos
                ? repo.findAll(Sort.by(Sort.Direction.DESC, "fechaIngreso"))
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

    /**
     * @param reinicia momento en que vuelve a haber cupo (medianoche local siguiente).
     *                 Lo calcula el servidor porque es quien conoce la zona configurada.
     */
    public record CupoInfo(Long tipoId, String nombre, int cupoDiario, long admitidosHoy,
                           long disponible, Instant reinicia) {
    }

    /** Cupo del dia de todos los tipos, para mostrarlo en la tabla sin una llamada por fila. */
    @Transactional(readOnly = true)
    public List<CupoInfo> cuposDeHoy(String bearer) {
        return catalogClient.listarTipos(bearer).stream().map(this::cupoDe).toList();
    }

    private CupoInfo cupoDe(CatalogClient.TipoTramiteView tipo) {
        long admitidosHoy = admitidosHoy(tipo.id());
        long disponible = Math.max(0, tipo.cupoDiario() - admitidosHoy);
        return new CupoInfo(tipo.id(), tipo.nombre(), tipo.cupoDiario(), admitidosHoy, disponible,
                inicioDelDiaSiguienteLocal(zona, Instant.now()));
    }

    /**
     * @param actor                  quien hace la llamada (preferred_username del token).
     * @param puedeIngresarPorOtro   true si el actor es Funcionario: puede ingresar el tramite a
     *                               nombre de otro vecino (el que no sabe hacerlo solo). Queda
     *                               registrado como creadoPor, y la auditoria lo muestra.
     */
    @Transactional
    public Tramite crear(CrearRequest req, String actor, boolean puedeIngresarPorOtro) {
        String vecino = actor;
        String creadoPor = null;
        if (req.vecinoUsername() != null && !req.vecinoUsername().isBlank()
                && !req.vecinoUsername().trim().equalsIgnoreCase(actor)) {
            if (!puedeIngresarPorOtro) {
                throw new AccessDeniedException("Solo un funcionario puede ingresar un tramite a nombre de otro vecino");
            }
            vecino = req.vecinoUsername().trim();
            creadoPor = actor;
        }
        Tramite tramite = repo.save(new Tramite(req.tipoId(), vecino, req.descripcion(), req.direccion(), creadoPor));

        TramiteHito ingreso = new TramiteHito(tramite.getId(), EstadoTramite.INGRESADO);
        ingreso.cumplir(tramite.getFechaIngreso(), actor,
                creadoPor != null ? "Ingresado por un funcionario a nombre del vecino" : null);
        hitos.save(ingreso);

        events.publishEvent(new TramiteCreadoEvent(tramite, actor));
        return tramite;
    }

    @Transactional
    public Tramite cambiarEstado(Long id, EstadoTramite nuevo, String funcionario, String bearer, String nota) {
        Tramite tramite = obtener(id);
        if (nuevo == EstadoTramite.ADMITIDO) {
            verificarCupoDisponible(tramite.getTipoId(), bearer);
        }
        EstadoTramite anterior = tramite.getEstado();
        tramite.cambiarEstado(nuevo, funcionario);

        TramiteHito hito = hitoDe(id, nuevo);
        hito.cumplir(Instant.now(), funcionario, nota);
        hitos.save(hito);

        events.publishEvent(new TramiteEstadoCambiadoEvent(tramite, anterior, funcionario, nota));
        return tramite;
    }

    /**
     * Fecha estimada y nota de un paso que todavia no se cumple. Sobre un paso ya cumplido
     * solo se puede cambiar la nota: su fecha es la real, no una estimacion.
     */
    @Transactional
    public TramiteHito planificar(Long id, EstadoTramite estado, Instant fechaEstimada, String nota, String usuario) {
        Tramite tramite = obtener(id);
        TramiteHito hito = hitoDe(id, estado);
        if (fechaEstimada != null && cumplido(tramite, hito)) {
            throw new IllegalArgumentException("El paso " + estado + " ya se cumplio: no se le puede poner fecha estimada");
        }
        hito.planificar(fechaEstimada, nota, usuario);
        return hitos.save(hito);
    }

    /** Un paso del seguimiento, tal como lo ve el vecino. */
    public record Paso(EstadoTramite estado, Instant fechaCumplido, Instant fechaEstimada,
                       String nota, String actualizadoPor) {
    }

    /**
     * El camino completo del tramite, cumplido o no. Los tramites anteriores a esta
     * version no tienen hitos guardados: para ellos la fecha real sale de las columnas
     * que el tramite ya tenia (ingreso, admision, resolucion).
     */
    @Transactional(readOnly = true)
    public List<Paso> seguimiento(Long id) {
        Tramite t = obtener(id);
        Map<EstadoTramite, TramiteHito> guardados = hitos.findAllByTramiteId(id).stream()
                .collect(Collectors.toMap(TramiteHito::getEstado, Function.identity()));

        List<EstadoTramite> estados = new ArrayList<>(FLUJO);
        if (t.getEstado() == EstadoTramite.RECHAZADO) estados.add(EstadoTramite.RECHAZADO);

        return estados.stream().map(e -> {
            TramiteHito h = guardados.get(e);
            Instant cumplido = h != null && h.getFechaCumplido() != null ? h.getFechaCumplido() : fechaLegada(t, e);
            return new Paso(e, cumplido,
                    h == null ? null : h.getFechaEstimada(),
                    h == null ? null : h.getNota(),
                    h == null ? null : h.getActualizadoPor());
        }).toList();
    }

    private TramiteHito hitoDe(Long id, EstadoTramite estado) {
        return hitos.findByTramiteIdAndEstado(id, estado).orElseGet(() -> new TramiteHito(id, estado));
    }

    private boolean cumplido(Tramite t, TramiteHito h) {
        return h.getFechaCumplido() != null || fechaLegada(t, h.getEstado()) != null;
    }

    /** Fecha real de un paso segun las columnas propias del tramite (datos anteriores a los hitos). */
    private static Instant fechaLegada(Tramite t, EstadoTramite e) {
        return switch (e) {
            case INGRESADO -> t.getFechaIngreso();
            case ADMITIDO -> t.getFechaAdmision();
            case RESUELTO -> t.getEstado() == EstadoTramite.RESUELTO ? t.getFechaResolucion() : null;
            case RECHAZADO -> t.getEstado() == EstadoTramite.RECHAZADO ? t.getFechaResolucion() : null;
            default -> null;
        };
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

    /**
     * Momento en que se reinicia el cupo: medianoche local del dia siguiente.
     *
     * <p>Se calcula sumando un dia al LocalDate y no 24 horas al Instant: en los
     * cambios de horario de verano el dia local dura 23 o 25 horas, y sumar 24h
     * caeria en la hora equivocada.
     */
    static Instant inicioDelDiaSiguienteLocal(ZoneId zona, Instant ahora) {
        return ahora.atZone(zona).toLocalDate().plusDays(1).atStartOfDay(zona).toInstant();
    }
}
