package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public final class TramiteDtos {

    private TramiteDtos() {
    }

    public record CrearRequest(
            @NotNull Long tipoId,
            @NotBlank String descripcion,
            String direccion) {
    }

    public record CambiarEstadoRequest(@NotNull EstadoTramite status) {
    }

    public record Response(
            Long id, Long tipoId, String vecinoUsername, String descripcion, String direccion,
            EstadoTramite estado, String funcionarioAsignado, String cuadrillaAsignada,
            Instant fechaIngreso, Instant fechaAdmision, Instant fechaResolucion) {

        public static Response from(Tramite t) {
            return new Response(t.getId(), t.getTipoId(), t.getVecinoUsername(), t.getDescripcion(),
                    t.getDireccion(), t.getEstado(), t.getFuncionarioAsignado(), t.getCuadrillaAsignada(),
                    t.getFechaIngreso(), t.getFechaAdmision(), t.getFechaResolucion());
        }
    }
}
