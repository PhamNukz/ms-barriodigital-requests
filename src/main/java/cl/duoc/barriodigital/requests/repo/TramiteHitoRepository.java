package cl.duoc.barriodigital.requests.repo;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.TramiteHito;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TramiteHitoRepository extends JpaRepository<TramiteHito, Long> {

    List<TramiteHito> findAllByTramiteId(Long tramiteId);

    Optional<TramiteHito> findByTramiteIdAndEstado(Long tramiteId, EstadoTramite estado);
}
