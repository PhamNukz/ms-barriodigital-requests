package cl.duoc.barriodigital.requests.repo;

import cl.duoc.barriodigital.requests.domain.Tramite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface TramiteRepository extends JpaRepository<Tramite, Long> {

    List<Tramite> findAllByVecinoUsernameOrderByFechaIngresoDesc(String vecinoUsername);

    /** Cupo consumido hoy: tramites de ese tipo admitidos entre las fechas dadas. */
    long countByTipoIdAndFechaAdmisionBetween(Long tipoId, Instant desde, Instant hasta);
}
