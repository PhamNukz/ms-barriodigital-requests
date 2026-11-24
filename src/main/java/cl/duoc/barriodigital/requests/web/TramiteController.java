package cl.duoc.barriodigital.requests.web;

import cl.duoc.barriodigital.requests.domain.EstadoTramite;
import cl.duoc.barriodigital.requests.domain.Tramite;
import cl.duoc.barriodigital.requests.service.TramiteService;
import cl.duoc.barriodigital.requests.service.TramiteService.Lector;
import cl.duoc.barriodigital.requests.service.TramiteService.Rol;
import cl.duoc.barriodigital.requests.web.TramiteDtos.CambiarEstadoRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.ConfirmarVisitaRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.ContactoRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.MotivoRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.ReglasDto;
import cl.duoc.barriodigital.requests.web.TramiteDtos.VisibilidadRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.CrearRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.PasoResponse;
import cl.duoc.barriodigital.requests.web.TramiteDtos.PlanificarPasoRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.ResponsableRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.Response;
import cl.duoc.barriodigital.requests.web.TramiteDtos.RespuestaVisitaRequest;
import cl.duoc.barriodigital.requests.web.TramiteDtos.VisitaRequest;
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
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                  // Solo Admin y Auditor: la papelera en vez de los vigentes.
                                  @RequestParam(defaultValue = "false") boolean papelera) {
        Lector lector = lector(auth, jwt);
        List<Tramite> tramites = service.listar(lector,
                estado != null ? estado : status,
                desde != null ? desde : from,
                hasta != null ? hasta : to,
                papelera);
        if (!lector.esVecino()) return tramites.stream().map(Response::from).toList();
        boolean nombres = service.reglas().isMostrarNombreFuncionario();
        return tramites.stream().map(t -> Response.from(t).paraVecino(nombres)).toList();
    }

    /** Cupo de todos los tipos: la tabla lo necesita por fila, en una sola llamada. */
    @GetMapping("/cupos")
    public List<TramiteService.CupoInfo> cuposDeHoy(@AuthenticationPrincipal Jwt jwt) {
        return service.cuposDeHoy(jwt.getTokenValue());
    }

    /** Las lee cualquier autenticado: el vecino necesita mesesHistorial para armar su historial. */
    @GetMapping("/reglas")
    public ReglasDto reglas() {
        return ReglasDto.from(service.reglas());
    }

    @PutMapping("/reglas")
    @PreAuthorize("hasRole('Admin')")
    public ReglasDto cambiarReglas(@Valid @RequestBody ReglasDto r, @AuthenticationPrincipal Jwt jwt) {
        return ReglasDto.from(service.cambiarReglas(r.mostrarNombreFuncionario(), r.mostrarRechazados(),
                r.mesesHistorial(), r.diasPapelera(), r.alVencer(), r.auditorRestaura(),
                jwt.getClaimAsString("preferred_username")));
    }

    /** Endpoint esencial del caso (seccion 5). Un vecino solo puede ver los suyos. */
    @GetMapping("/{id}")
    public Response obtener(@PathVariable Long id, Authentication auth, @AuthenticationPrincipal Jwt jwt) {
        Lector lector = lector(auth, jwt);
        return vista(service.obtenerPara(id, lector), lector);
    }

    /** El camino del tramite paso a paso: fechas reales, estimadas y notas del funcionario. */
    @GetMapping("/{id}/seguimiento")
    public List<PasoResponse> seguimiento(@PathVariable Long id, Authentication auth, @AuthenticationPrincipal Jwt jwt) {
        Lector lector = lector(auth, jwt);
        service.obtenerPara(id, lector);
        boolean sinNombres = lector.esVecino() && !service.reglas().isMostrarNombreFuncionario();
        return service.seguimiento(id).stream().map(PasoResponse::from)
                .map(p -> sinNombres ? p.sinNombre() : p).toList();
    }

    /**
     * Un vecino ingresa su propio tramite. Un funcionario tambien puede ingresarlo a nombre
     * de un vecino (vecinoUsername): queda como creadoPor y asi lo registra la auditoria.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('Vecino','Funcionario')")
    public Response crear(@Valid @RequestBody CrearRequest req, Authentication auth, @AuthenticationPrincipal Jwt jwt) {
        return vista(service.crear(req, jwt.getClaimAsString("preferred_username"), tieneRol(auth, "Funcionario")),
                lector(auth, jwt));
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

    /** Reasignacion desde el tablero del funcionario. */
    @PutMapping("/{id}/responsable")
    @PreAuthorize("hasAnyRole('Funcionario','Admin')")
    public Response asignarResponsable(@PathVariable Long id, @Valid @RequestBody ResponsableRequest req,
                                       @AuthenticationPrincipal Jwt jwt) {
        return Response.from(service.asignarResponsable(id, req.responsable(), jwt.getClaimAsString("preferred_username")));
    }

    /** Propone (o reprograma) la visita en terreno; el vecino la responde con un boton. */
    @PutMapping("/{id}/visita")
    @PreAuthorize("hasAnyRole('Funcionario','Admin')")
    public Response proponerVisita(@PathVariable Long id, @Valid @RequestBody VisitaRequest req,
                                   @AuthenticationPrincipal Jwt jwt) {
        return Response.from(service.proponerVisita(id, req.fecha(), req.franja(), req.cuadrilla(), req.nota(),
                jwt.getClaimAsString("preferred_username")));
    }

    /** Solo el vecino dueno: el servicio responde 403 a cualquier otro, aunque sea funcionario. */
    @PostMapping("/{id}/visita/respuesta")
    public Response responderVisita(@PathVariable Long id, @Valid @RequestBody RespuestaVisitaRequest req,
                                    Authentication auth, @AuthenticationPrincipal Jwt jwt) {
        return vista(service.responderVisita(id, req.respuesta(), req.horarioPedido(), req.codigo(),
                jwt.getClaimAsString("preferred_username")), lector(auth, jwt));
    }

    /** El funcionario confirmo la visita por telefono (vecinos que no usan la app). */
    @PutMapping("/{id}/visita/confirmar")
    @PreAuthorize("hasAnyRole('Funcionario','Admin')")
    public Response confirmarVisita(@PathVariable Long id, @Valid @RequestBody(required = false) ConfirmarVisitaRequest req,
                                    @AuthenticationPrincipal Jwt jwt) {
        return Response.from(service.confirmarVisita(id, req == null ? null : req.nota(),
                jwt.getClaimAsString("preferred_username")));
    }

    /** Familiar que recibe copia de los avisos. Solo el vecino dueno (403 a cualquier otro). */
    @PostMapping("/{id}/contactos")
    public Response agregarContacto(@PathVariable Long id, @Valid @RequestBody ContactoRequest req,
                                    Authentication auth, @AuthenticationPrincipal Jwt jwt) {
        return vista(service.agregarContacto(id, req.nombre(), req.correo(),
                jwt.getClaimAsString("preferred_username")), lector(auth, jwt));
    }

    @DeleteMapping("/{id}/contactos/{contactoId}")
    public Response quitarContacto(@PathVariable Long id, @PathVariable Long contactoId,
                                   Authentication auth, @AuthenticationPrincipal Jwt jwt) {
        return vista(service.quitarContacto(id, contactoId, jwt.getClaimAsString("preferred_username")),
                lector(auth, jwt));
    }

    @PutMapping("/{id}/en-camino")
    @PreAuthorize("hasAnyRole('Funcionario','Admin')")
    public Response avisarEnCamino(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return Response.from(service.avisarEnCamino(id, jwt.getClaimAsString("preferred_username")));
    }

    // ---- Admin B (seccion 6): visibilidad, papelera, restaurar ----

    @PutMapping("/{id}/visibilidad")
    @PreAuthorize("hasRole('Admin')")
    public Response cambiarVisibilidad(@PathVariable Long id, @Valid @RequestBody VisibilidadRequest req,
                                       @AuthenticationPrincipal Jwt jwt) {
        return Response.from(service.cambiarVisibilidad(id, req.visibleVecino(), req.motivo(),
                jwt.getClaimAsString("preferred_username")));
    }

    /** Borrado logico: va a la papelera con el motivo (query, 3 a 200 caracteres). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('Admin')")
    public Response enviarAPapelera(@PathVariable Long id, @RequestParam(required = false) String motivo,
                                    @AuthenticationPrincipal Jwt jwt) {
        return Response.from(service.enviarAPapelera(id, motivo, jwt.getClaimAsString("preferred_username")));
    }

    @PostMapping("/{id}/restaurar")
    @PreAuthorize("hasAnyRole('Admin','Auditor')")
    public Response restaurar(@PathVariable Long id, @RequestBody MotivoRequest req, Authentication auth,
                              @AuthenticationPrincipal Jwt jwt) {
        return Response.from(service.restaurar(id, req.motivo(), jwt.getClaimAsString("preferred_username"),
                tieneRol(auth, "Admin")));
    }

    private Lector lector(Authentication auth, Jwt jwt) {
        // ponytail: un rol por persona, el mas amplio; un Funcionario que ademas es Auditor lista como
        // Funcionario y no ve la papelera. Si aparece ese caso, separar "ve la papelera" del rol.
        Rol rol = tieneRol(auth, "Admin") ? Rol.ADMIN
                : tieneRol(auth, "Funcionario") ? Rol.FUNCIONARIO
                : tieneRol(auth, "Auditor") ? Rol.AUDITOR
                : Rol.VECINO;
        return new Lector(jwt.getClaimAsString("preferred_username"), rol);
    }

    /** Lo que ve quien llama: al vecino, sin motivos internos y, si la regla lo dice, sin nombres de funcionarios. */
    private Response vista(Tramite t, Lector lector) {
        Response r = Response.from(t);
        return lector.esVecino() ? r.paraVecino(service.reglas().isMostrarNombreFuncionario()) : r;
    }

    private boolean tieneRol(Authentication auth, String rol) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + rol));
    }
}
