package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.service.TramiteService;
import cl.duoc.barriodigital.requests.web.TramiteDtos.CambiarEstadoRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.CrearRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.Response;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
        boolean puedeVerTodos = tieneRol(auth, "Admin") || tieneRol(auth, "Funcionario");
        return service.listar(jwt.getClaimAsString("preferred_username"), puedeVerTodos,
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
        var tramite = service.obtener(id);
        boolean puedeVerTodos = tieneRol(auth, "Admin") || tieneRol(auth, "Funcionario");
        if (!puedeVerTodos && !tramite.getVecinoUsername().equals(jwt.getClaimAsString("preferred_username"))) {
            throw new org.springframework.security.access.AccessDeniedException("No puedes ver el tramite de otro vecino");
        }
        return Response.from(tramite);
    }

    /** Un vecino ingresa su propio tramite; un funcionario tambien puede ingresarlo por el. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('Vecino','Funcionario')")
    public Response crear(@Valid @RequestBody CrearRequest req, @AuthenticationPrincipal Jwt jwt) {
        return Response.from(service.crear(req, jwt.getClaimAsString("preferred_username")));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('Funcionario','Admin')")
    public Response cambiarEstado(@PathVariable Long id, @Valid @RequestBody CambiarEstadoRequest req,
                                  @AuthenticationPrincipal Jwt jwt) {
        String funcionario = jwt.getClaimAsString("preferred_username");
        return Response.from(service.cambiarEstado(id, req.status(), funcionario, jwt.getTokenValue()));
    }

    private boolean tieneRol(Authentication auth, String rol) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + rol));
    }
}
