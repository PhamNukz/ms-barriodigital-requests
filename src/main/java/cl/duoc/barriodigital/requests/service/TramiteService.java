package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.client.CatalogClient;
import cl.duoc.barriodigital.requests.domain.Contacto;
import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.domain.TramiteHito;
import cl.duoc.barriodigital.requests.domain.Visita;
import cl.duoc.barriodigital.requests.kafka.AuditEntry;
import cl.duoc.barriodigital.requests.repo.TramiteHitoRepository;
import cl.duoc.barriodigital.requests.repo.TramiteRepository;
import cl.duoc.barriodigital.requests.service.event.TramiteCreadoEvent;
import cl.duoc.barriodigital.requests.service.event.TramiteAccionEvent;
import cl.duoc.barriodigital.requests.service.event.TramiteEstadoCambiadoEvent;
import cl.duoc.barriodigital.requests.web.TramiteDtos.CrearRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
        Tramite nuevo = new Tramite(req.tipoId(), vecino, req.descripcion(), req.direccion(), creadoPor);
        nuevo.ubicar(req.lat(), req.lng(), req.referencia());
        Tramite tramite = repo.save(nuevo);

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

    @Transactional
    public Tramite asignarResponsable(Long id, String responsable, String usuario) {
        Tramite tramite = obtener(id);
        String anterior = tramite.getResponsable();
        tramite.asignarResponsable(responsable.trim());
        events.publishEvent(new TramiteAccionEvent(tramite, usuario, AuditEntry.Accion.ASIGNAR_RESPONSABLE,
                "Responsable " + (anterior == null ? "sin asignar" : anterior) + " -> " + tramite.getResponsable(),
                null, null));
        return tramite;
    }

    /**
     * Crea o reprograma la visita y planifica el paso EN_TERRENO para el inicio de la franja
     * (hora local). Si EN_TERRENO ya se cumplio (reprogramacion) su fecha es la real y no se toca.
     */
    @Transactional
    public Tramite proponerVisita(Long id, LocalDate fecha, Visita.Franja franja, String cuadrilla, String nota,
                                  String usuario) {
        Tramite tramite = obtener(id);
        // "Ahora" en la hora del municipio, no la del servidor (en AWS corre en UTC).
        Visita visita = tramite.proponerVisita(fecha, franja, cuadrilla, nota, LocalDateTime.now(zona));

        TramiteHito hito = hitoDe(id, EstadoTramite.EN_TERRENO);
        if (!cumplido(tramite, hito)) {
            hito.planificar(visita.inicio(zona), hito.getNota(), usuario);
            hitos.save(hito);
        }
        events.publishEvent(new TramiteAccionEvent(tramite, usuario, AuditEntry.Accion.PROPONER_VISITA,
                "Visita " + visita.getFecha() + " " + visita.getFranja() + ", " + visita.getCuadrilla(),
                visita.getNota(), "VISITA_PROPUESTA"));
        return tramite;
    }

    /**
     * Solo el vecino dueno responde, aunque quien llame sea funcionario o admin.
     * @param codigoVisto codigo de la visita que el vecino tenia en pantalla (opcional): si ya no
     *                    coincide, la reprogramaron y se responde 409 en vez de confirmar otra fecha.
     */
    @Transactional
    public Tramite responderVisita(Long id, Visita.Respuesta respuesta, String horarioPedido, String codigoVisto,
                                   String usuario) {
        Tramite tramite = delVecino(id, usuario, "responder la visita");
        Visita visita = tramite.responderVisita(respuesta, horarioPedido, codigoVisto);
        events.publishEvent(new TramiteAccionEvent(tramite, usuario, AuditEntry.Accion.RESPONDER_VISITA,
                "Visita " + visita.getEstado(), visita.getHorarioPedido(), null));
        return tramite;
    }

    /** El funcionario confirmo por telefono. Idempotente: si ya estaba CONFIRMADA no audita ni avisa. */
    @Transactional
    public Tramite confirmarVisita(Long id, String nota, String usuario) {
        Tramite tramite = obtener(id);
        if (tramite.confirmarVisitaPorTelefono()) {
            events.publishEvent(new TramiteAccionEvent(tramite, usuario, AuditEntry.Accion.CONFIRMAR_VISITA,
                    "Visita confirmada por telefono", nota, "VISITA_CONFIRMADA"));
        }
        return tramite;
    }

    @Transactional
    public Tramite agregarContacto(Long id, String nombre, String correo, String usuario) {
        Tramite tramite = delVecino(id, usuario, "agregar contactos");
        Contacto c = tramite.agregarContacto(nombre, correo);
        // flush aqui: el id del contacto va en la respuesta, y un doble clic que choque con el
        // UNIQUE de V6 sale como DataIntegrityViolationException (409) desde el repositorio.
        repo.saveAndFlush(tramite);
        events.publishEvent(new TramiteAccionEvent(tramite, usuario, AuditEntry.Accion.AGREGAR_CONTACTO,
                "Contacto " + c.getNombre() + " <" + c.getCorreo() + ">", null, null));
        return tramite;
    }

    @Transactional
    public Tramite quitarContacto(Long id, Long contactoId, String usuario) {
        Tramite tramite = delVecino(id, usuario, "quitar contactos");
        Contacto c = tramite.quitarContacto(contactoId);
        events.publishEvent(new TramiteAccionEvent(tramite, usuario, AuditEntry.Accion.QUITAR_CONTACTO,
                "Contacto " + c.getNombre() + " <" + c.getCorreo() + ">", null, null));
        return tramite;
    }

    private Tramite delVecino(Long id, String usuario, String accion) {
        Tramite tramite = obtener(id);
        if (!tramite.getVecinoUsername().equalsIgnoreCase(usuario)) {
            throw new AccessDeniedException("Solo el vecino del tramite puede " + accion);
        }
        return tramite;
    }

    /** Idempotente: si ya estaba en camino no cambia la hora ni repite el correo. */
    @Transactional
    public Tramite avisarEnCamino(Long id, String usuario) {
        Tramite tramite = obtener(id);
        // Truncado: la BD guarda microsegundos y el reloj de Windows da decimas de microsegundo,
        // asi que la respuesta del PUT y un GET posterior mostraban horas distintas.
        if (tramite.marcarEnCamino(Instant.now().truncatedTo(ChronoUnit.MILLIS))) {
            events.publishEvent(new TramiteAccionEvent(tramite, usuario, AuditEntry.Accion.AVISAR_EN_CAMINO,
                    "Cuadrilla en camino", null, "EN_CAMINO"));
        }
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
