package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.domain.Visita;
import cl.duoc.barriodigital.requests.service.TramiteService.Paso;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TramiteDtos {

    private TramiteDtos() {
    }

    static final String FUERA = "el punto marcado queda fuera de la comuna de Valparaiso";

    /**
     * @param vecinoUsername solo para Funcionario: correo del vecino a cuyo nombre se ingresa.
     *                       Si viene vacio, el tramite es de quien lo ingresa.
     * @param lat            opcional, junto con lng: el punto marcado en el mapa. Debe caer en el
     *                       rectangulo que contiene la comuna de Valparaiso continental (limite
     *                       comunal de OpenStreetMap, relacion 110808, redondeado hacia afuera).
     * @param referencia     opcional: cerro, pasaje, escalera, color de la casa.
     */
    public record CrearRequest(
            @NotNull Long tipoId,
            @NotBlank String descripcion,
            @Size(max = 300) String direccion,
            @Email(message = "el correo del vecino no es valido") @Size(max = 150) String vecinoUsername,
            // ponytail: rectangulo, no el poligono comunal: deja pasar bordes de Vina del Mar y
            // Casablanca. Si hace falta precision, validar contra el limite oficial (IDE Chile).
            @DecimalMin(value = "-33.22", message = FUERA) @DecimalMax(value = "-33.01", message = FUERA) BigDecimal lat,
            @DecimalMin(value = "-71.76", message = FUERA) @DecimalMax(value = "-71.38", message = FUERA) BigDecimal lng,
            @Size(max = 300) String referencia) {

        public CrearRequest(Long tipoId, String descripcion, String direccion) {
            this(tipoId, descripcion, direccion, null, null, null, null);
        }

        /** Un punto a medias (solo lat o solo lng) no sirve para llegar. */
        @AssertTrue(message = "lat y lng van juntos")
        public boolean isUbicacionCompleta() {
            return (lat == null) == (lng == null);
        }
    }

    /** @param nota comentario opcional para el vecino ("se agendo la visita para el martes"). */
    public record CambiarEstadoRequest(@NotNull EstadoTramite status, @Size(max = 500) String nota) {
    }

    /** Fecha estimada y nota de un paso futuro. Ambas opcionales: null las borra. */
    public record PlanificarPasoRequest(Instant fechaEstimada, @Size(max = 500) String nota) {
    }

    public record ResponsableRequest(
            @NotBlank @Email(message = "el correo del responsable no es valido") @Size(max = 150) String responsable) {
    }

    public record VisitaRequest(@NotNull LocalDate fecha, @NotNull Visita.Franja franja,
                                @NotBlank @Size(max = 100) String cuadrilla, @Size(max = 500) String nota) {
    }

    /**
     * @param horarioPedido obligatorio solo con CAMBIAR ("vie 27, tarde").
     * @param codigo        opcional: el visita.codigo que el vecino tenia en pantalla. Si la
     *                      reprogramaron mientras tanto no coincide y se responde 409.
     */
    public record RespuestaVisitaRequest(@NotNull Visita.Respuesta respuesta, @Size(max = 100) String horarioPedido,
                                         @Pattern(regexp = "\\d{4}", message = "el codigo son 4 digitos") String codigo) {
    }

    /** Body opcional de la confirmacion por telefono. */
    public record ConfirmarVisitaRequest(@Size(max = 500) String nota) {
    }

    public record ContactoRequest(@NotBlank @Size(max = 100) String nombre,
                                  @NotBlank @Email(message = "el correo del contacto no es valido") @Size(max = 150) String correo) {
    }

    public record Response(
            Long id, Long tipoId, String vecinoUsername, String descripcion, String direccion,
            EstadoTramite estado, String funcionarioAsignado,
            Instant fechaIngreso, Instant fechaAdmision, Instant fechaResolucion, String creadoPor,
            BigDecimal lat, BigDecimal lng, String referencia,
            String responsable, Instant enCaminoDesde, VisitaResponse visita, List<ContactoResponse> contactos) {

        public static Response from(Tramite t) {
            return new Response(t.getId(), t.getTipoId(), t.getVecinoUsername(), t.getDescripcion(),
                    t.getDireccion(), t.getEstado(), t.getFuncionarioAsignado(),
                    t.getFechaIngreso(), t.getFechaAdmision(), t.getFechaResolucion(), t.getCreadoPor(),
                    t.getLat(), t.getLng(), t.getReferencia(),
                    t.getResponsable(), t.getEnCaminoDesde(), VisitaResponse.from(t.getVisita()),
                    t.getContactos().stream().map(c -> new ContactoResponse(c.getId(), c.getNombre(), c.getCorreo())).toList());
        }
    }

    public record ContactoResponse(Long id, String nombre, String correo) {
    }

    public record VisitaResponse(LocalDate fecha, Visita.Franja franja, String cuadrilla, String nota,
                                 Visita.Estado estado, String horarioPedido, Instant actualizadoEn, String codigo) {

        static VisitaResponse from(Visita v) {
            return v == null ? null : new VisitaResponse(v.getFecha(), v.getFranja(), v.getCuadrilla(), v.getNota(),
                    v.getEstado(), v.getHorarioPedido(), v.getActualizadoEn(), v.getCodigo());
        }
    }

    public record PasoResponse(EstadoTramite estado, Instant fechaCumplido, Instant fechaEstimada,
                               String nota, String actualizadoPor) {

        public static PasoResponse from(Paso p) {
            return new PasoResponse(p.estado(), p.fechaCumplido(), p.fechaEstimada(), p.nota(), p.actualizadoPor());
        }
    }
}
