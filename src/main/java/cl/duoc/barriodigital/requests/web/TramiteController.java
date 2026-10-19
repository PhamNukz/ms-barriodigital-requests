package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.service.TramiteService;
import cl.duoc.barriodigital.requests.web.TramiteDtos.CambiarEstadoRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.CrearRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.PasoResponse;
import cl.duoc.barriodigital.requests.web.TramiteDtos.PlanificarPasoRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.Response;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/requests")
public class TramiteController {

    private final TramiteService service;

    public TramiteController(TramiteService service) {
        this.service = service;
    }

    @GetMapping
    public List<Response> listar(Authentication auth,
                                  @AuthenticationPrincipal Jwt jwt,
                                  @RequestParam(required = false) EstadoTramite estado,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant desde,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant hasta,
                                  // El caso documenta el filtro como ?status=&from=&to=; se aceptan ambos
                                  // nombres para que el contrato publicado funcione tal cual esta escrito.
                                  @RequestParam(required = false) EstadoTramite status,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.listar(jwt.getClaimAsString("preferred_username"), puedeVerTodos(auth),
                        estado != null ? estado : status,
                        desde != null ? desde : from,
                        hasta != null ? hasta : to)
                .stream().map(Response::from).toList();
    }

    /** Cupo de todos los tipos: la tabla lo necesita por fila, en una sola llamada. */
    @GetMapping("/cupos")
    public List<TramiteService.CupoInfo> cuposDeHoy(@AuthenticationPrincipal Jwt jwt) {
        return service.cuposDeHoy(jwt.getTokenValue());
    }

    /** Endpoint esencial del caso (seccion 5). Un vecino solo puede ver los suyos. */
    @GetMapping("/{id}")
    public Response obtener(@PathVariable Long id, Authentication auth, @AuthenticationPrincipal Jwt jwt) {
        return Response.from(visiblePara(id, auth, jwt));
    }

    /** El camino del tramite paso a paso: fechas reales, estimadas y notas del funcionario. */
    @GetMapping("/{id}/seguimiento")
    public List<PasoResponse> seguimiento(@PathVariable Long id, Authentication auth, @AuthenticationPrincipal Jwt jwt) {
        visiblePara(id, auth, jwt);
        return service.seguimiento(id).stream().map(PasoResponse::from).toList();
    }

    /**
     * Un vecino ingresa su propio tramite. Un funcionario tambien puede ingresarlo a nombre
     * de un vecino (vecinoUsername): queda como creadoPor y asi lo registra la auditoria.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('Vecino','Funcionario')")
    public Response crear(@Valid @RequestBody CrearRequest req, Authentication auth, @AuthenticationPrincipal Jwt jwt) {
        return Response.from(service.crear(req, jwt.getClaimAsString("preferred_username"), tieneRol(auth, "Funcionario")));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('Funcionario','Admin')")
    public Response cambiarEstado(@PathVariable Long id, @Valid @RequestBody CambiarEstadoRequest req,
                                  @AuthenticationPrincipal Jwt jwt) {
        String funcionario = jwt.getClaimAsString("preferred_username");
        return Response.from(service.cambiarEstado(id, req.status(), funcionario, jwt.getTokenValue(), req.nota()));
    }

    /** El funcionario programa cuando estima que se cumplira un paso y le deja una nota al vecino. */
    @PutMapping("/{id}/seguimiento/{estado}")
    @PreAuthorize("hasAnyRole('Funcionario','Admin')")
    public List<PasoResponse> planificar(@PathVariable Long id, @PathVariable EstadoTramite estado,
                                         @Valid @RequestBody PlanificarPasoRequest req,
                                         @AuthenticationPrincipal Jwt jwt) {
        service.planificar(id, estado, req.fechaEstimada(), req.nota(), jwt.getClaimAsString("preferred_username"));
        return service.seguimiento(id).stream().map(PasoResponse::from).toList();
    }

    private Tramite visiblePara(Long id, Authentication auth, Jwt jwt) {
        Tramite tramite = service.obtener(id);
        if (!puedeVerTodos(auth) && !tramite.getVecinoUsername().equals(jwt.getClaimAsString("preferred_username"))) {
            throw new AccessDeniedException("No puedes ver el tramite de otro vecino");
        }
        return tramite;
    }

    private boolean puedeVerTodos(Authentication auth) {
        return tieneRol(auth, "Admin") || tieneRol(auth, "Funcionario");
    }

    private boolean tieneRol(Authentication auth, String rol) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + rol));
    }
}
