package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.service.TramiteService.Paso;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class TramiteDtos {

    private TramiteDtos() {
    }

    /**
     * @param vecinoUsername solo para Funcionario: correo del vecino a cuyo nombre se ingresa.
     *                       Si viene vacio, el tramite es de quien lo ingresa.
     */
    public record CrearRequest(
            @NotNull Long tipoId,
            @NotBlank String descripcion,
            String direccion,
            @Email(message = "el correo del vecino no es valido") @Size(max = 150) String vecinoUsername) {

        public CrearRequest(Long tipoId, String descripcion, String direccion) {
            this(tipoId, descripcion, direccion, null);
        }
    }

    /** @param nota comentario opcional para el vecino ("se agendo la visita para el martes"). */
    public record CambiarEstadoRequest(@NotNull EstadoTramite status, @Size(max = 500) String nota) {
    }

    /** Fecha estimada y nota de un paso futuro. Ambas opcionales: null las borra. */
    public record PlanificarPasoRequest(Instant fechaEstimada, @Size(max = 500) String nota) {
    }

    public record Response(
            Long id, Long tipoId, String vecinoUsername, String descripcion, String direccion,
            EstadoTramite estado, String funcionarioAsignado,
            Instant fechaIngreso, Instant fechaAdmision, Instant fechaResolucion, String creadoPor) {

        public static Response from(Tramite t) {
            return new Response(t.getId(), t.getTipoId(), t.getVecinoUsername(), t.getDescripcion(),
                    t.getDireccion(), t.getEstado(), t.getFuncionarioAsignado(),
                    t.getFechaIngreso(), t.getFechaAdmision(), t.getFechaResolucion(), t.getCreadoPor());
        }
    }

    public record PasoResponse(EstadoTramite estado, Instant fechaCumplido, Instant fechaEstimada,
                               String nota, String actualizadoPor) {

        public static PasoResponse from(Paso p) {
            return new PasoResponse(p.estado(), p.fechaCumplido(), p.fechaEstimada(), p.nota(), p.actualizadoPor());
        }
    }
}
