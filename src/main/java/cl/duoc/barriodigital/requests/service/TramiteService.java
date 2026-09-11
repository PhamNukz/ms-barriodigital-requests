package cl.duoc.barriodigital.requests.service;

import cl.duoc.barriodigital.requests.client.CatalogClient;
import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.repo.TramiteRepository;
import cl.duoc.barriodigital.requests.web.TramiteDtos.CrearRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class TramiteService {

    private final TramiteRepository repo;
    private final CatalogClient catalogClient;

    public TramiteService(TramiteRepository repo, CatalogClient catalogClient) {
        this.repo = repo;
        this.catalogClient = catalogClient;
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

    @Transactional
    public Tramite crear(CrearRequest req, String vecinoUsername) {
        return repo.save(new Tramite(req.tipoId(), vecinoUsername, req.descripcion(), req.direccion()));
    }

    @Transactional
    public Tramite cambiarEstado(Long id, EstadoTramite nuevo, String funcionario, String bearer) {
        Tramite tramite = obtener(id);
        if (nuevo == EstadoTramite.ADMITIDO) {
            verificarCupoDisponible(tramite.getTipoId(), bearer);
        }
        tramite.cambiarEstado(nuevo, funcionario);
        // TODO fase 2: publicar evento en RabbitMQ (email al vecino, ticket a la cuadrilla)
        return tramite;
    }

    private void verificarCupoDisponible(Long tipoId, String bearer) {
        CatalogClient.TipoTramiteView tipo = catalogClient.obtenerTipo(tipoId, bearer);
        Instant inicioHoy = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        long admitidosHoy = repo.countByTipoIdAndFechaAdmisionBetween(tipoId, inicioHoy, Instant.now());
        if (admitidosHoy >= tipo.cupoDiario()) {
            throw new CupoExcedidoException(
                    "Cupo diario agotado para " + tipo.nombre() + " (" + tipo.cupoDiario() + "/dia)");
        }
    }
}
