package cl.duoc.barriodigital.requests.repo;

import cl.duoc.barriodigital.requests.domain.Tramite;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TramiteRepository extends JpaRepository<Tramite, Long> {

    // La visita es el lado inverso de un @OneToOne: sin el join, cada fila del listado
    // dispararia su propio SELECT a visita (N+1).
    @Override
    @EntityGraph(attributePaths = {"visita", "contactos"})
    List<Tramite> findAll(Sort sort);

    @EntityGraph(attributePaths = {"visita", "contactos"})
    List<Tramite> findAllByVecinoUsernameOrderByFechaIngresoDesc(String vecinoUsername);

    /** Cupo consumido hoy: tramites de ese tipo admitidos entre las fechas dadas. */
    long countByTipoIdAndFechaAdmisionBetween(Long tipoId, Instant desde, Instant hasta);
}
