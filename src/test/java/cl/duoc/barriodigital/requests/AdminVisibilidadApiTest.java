package cl.duoc.barriodigital.requests;

import cl.duoc.barriodigital.requests.amqp.NotificationPublisher;
import cl.duoc.barriodigital.requests.client.CatalogClient;
import cl.duoc.barriodigital.requests.kafka.AuditEntry;
import cl.duoc.barriodigital.requests.kafka.RequestsEventPublisher;
import cl.duoc.barriodigital.requests.repo.ReglaVisibilidadRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Seccion 6 del contrato (Admin B): visibilidad al vecino, papelera, restaurar y reglas.
 * Mismos @MockBean que las otras clases HTTP: comparten el contexto. Las reglas son globales,
 * asi que se vuelven a los valores por defecto despues de cada test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminVisibilidadApiTest {

    private static final String VECINO = "vecino.admin@duocuc.cl";
    private static final String FUNC = "func1@duocuc.cl";
    private static final String ADMIN = "admin@duocuc.cl";
    private static final String AUDITOR = "auditor@duocuc.cl";
    private static final String REGLAS_POR_DEFECTO =
            "{\"mostrarNombreFuncionario\":true,\"mostrarRechazados\":true,\"mesesHistorial\":12,"
                    + "\"diasPapelera\":90,\"alVencer\":\"ANONIMIZAR\",\"auditorRestaura\":false}";

    @Autowired
    MockMvc mvc;
    @Autowired
    ReglaVisibilidadRepository reglas;

    @MockBean
    JwtDecoder jwtDecoder;
    @MockBean
    NotificationPublisher notificationPublisher;
    @MockBean
    RequestsEventPublisher requestsEventPublisher;
    @MockBean
    CatalogClient catalogClient;

    @BeforeEach
    void hayCupo() {
        when(catalogClient.obtenerTipo(anyLong(), any()))
                .thenReturn(new CatalogClient.TipoTramiteView(1L, "Poda de arbol", 10_000, true));
    }

    @AfterEach
    void reglasPorDefecto() {
        reglas.deleteAll();
    }

    private static RequestPostProcessor como(String rol, String usuario) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + rol))
                .jwt(j -> j.claim("preferred_username", usuario));
    }

    private static final RequestPostProcessor VEC = como("Vecino", VECINO);
    private static final RequestPostProcessor FUN = como("Funcionario", FUNC);
    private static final RequestPostProcessor ADM = como("Admin", ADMIN);
    private static final RequestPostProcessor AUD = como("Auditor", AUDITOR);

    private long crear(RequestPostProcessor quien, String cuerpo) throws Exception {
        String json = mvc.perform(post("/requests").contentType("application/json").content(cuerpo).with(quien))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(json, "$.id").longValue();
    }

    private long crear() throws Exception {
        return crear(VEC, "{\"tipoId\":1,\"descripcion\":\"Poda\",\"direccion\":\"Calle 1\"}");
    }

    private ResultActions estado(long id, String estado) throws Exception {
        return mvc.perform(put("/requests/" + id + "/status").contentType("application/json")
                .content("{\"status\":\"" + estado + "\"}").with(FUN));
    }

    private ResultActions visibilidad(long id, RequestPostProcessor quien, boolean visible, String motivo) throws Exception {
        String m = motivo == null ? "" : ",\"motivo\":\"" + motivo + "\"";
        return mvc.perform(put("/requests/" + id + "/visibilidad").contentType("application/json")
                .content("{\"visibleVecino\":" + visible + m + "}").with(quien));
    }

    private ResultActions papelera(long id, String motivo) throws Exception {
        var del = delete("/requests/" + id).with(ADM);
        return mvc.perform(motivo == null ? del : del.param("motivo", motivo));
    }

    private ResultActions restaurar(long id, RequestPostProcessor quien, String motivo) throws Exception {
        return mvc.perform(post("/requests/" + id + "/restaurar").contentType("application/json")
                .content(motivo == null ? "{}" : "{\"motivo\":\"" + motivo + "\"}").with(quien));
    }

    private ResultActions ponerReglas(String json) throws Exception {
        return mvc.perform(put("/requests/reglas").contentType("application/json").content(json).with(ADM));
    }

    private String regla(String campo, Object valor) {
        String v = valor instanceof String ? "\"" + valor + "\"" : String.valueOf(valor);
        return REGLAS_POR_DEFECTO.replaceFirst("\"" + campo + "\":[^,}]+", "\"" + campo + "\":" + v);
    }

    private List<Integer> idsListados(RequestPostProcessor quien, String query) throws Exception {
        String json = mvc.perform(get("/requests" + query).with(quien)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$[*].id");
    }

    @Test
    void el_vecino_recibe_404_y_no_403_en_su_tramite_oculto_o_eliminado() throws Exception {
        long oculto = crear();
        visibilidad(oculto, ADM, false, "Duplicado del 12").andExpect(status().isOk())
                .andExpect(jsonPath("$.visibleVecino").value(false))
                .andExpect(jsonPath("$.motivoVisibilidad").value("Duplicado del 12"));

        mvc.perform(get("/requests/" + oculto).with(VEC)).andExpect(status().isNotFound());
        mvc.perform(get("/requests/" + oculto + "/seguimiento").with(VEC)).andExpect(status().isNotFound());
        assertFalse(idsListados(VEC, "").contains((int) oculto));
        // tampoco sus acciones lo delatan
        mvc.perform(post("/requests/" + oculto + "/contactos").contentType("application/json")
                .content("{\"nombre\":\"Hija\",\"correo\":\"hija@correo.cl\"}").with(VEC)).andExpect(status().isNotFound());
        // el funcionario si lo ve
        mvc.perform(get("/requests/" + oculto).with(FUN)).andExpect(status().isOk());
        assertTrue(idsListados(FUN, "").contains((int) oculto));

        long eliminado = crear();
        papelera(eliminado, "Ingresado por error").andExpect(status().isOk())
                .andExpect(jsonPath("$.eliminadoEn").exists())
                .andExpect(jsonPath("$.eliminadoPor").value(ADMIN));
        mvc.perform(get("/requests/" + eliminado).with(VEC)).andExpect(status().isNotFound());
        mvc.perform(get("/requests/" + eliminado).with(FUN)).andExpect(status().isNotFound());
        mvc.perform(get("/requests/" + eliminado).with(ADM)).andExpect(status().isOk());
        mvc.perform(get("/requests/" + eliminado).with(AUD)).andExpect(status().isOk());
    }

    @Test
    void ocultar_dos_veces_409_sin_motivo_400_y_solo_admin() throws Exception {
        long id = crear();
        visibilidad(id, ADM, false, "Duplicado").andExpect(status().isOk());
        visibilidad(id, ADM, false, "Duplicado").andExpect(status().isConflict());

        visibilidad(id, ADM, true, null).andExpect(status().isBadRequest());
        visibilidad(id, ADM, true, "  ab ").andExpect(status().isBadRequest());
        visibilidad(id, ADM, true, "x".repeat(201)).andExpect(status().isBadRequest());
        visibilidad(id, FUN, true, "Lo veo yo").andExpect(status().isForbidden());

        verify(requestsEventPublisher, timeout(2000)).auditar(argThat(t -> t.getId() == id), eq(ADMIN),
                eq(AuditEntry.Accion.OCULTAR_VECINO), eq("Oculto al vecino"), eq("Duplicado"));
    }

    @Test
    void cualquier_accion_sobre_un_tramite_en_la_papelera_409() throws Exception {
        long id = crear();
        estado(id, "ADMITIDO").andExpect(status().isOk());
        papelera(id, null).andExpect(status().isBadRequest());
        papelera(id, "Ingresado por error").andExpect(status().isOk());

        estado(id, "EN_GESTION").andExpect(status().isConflict());
        mvc.perform(put("/requests/" + id + "/visita").contentType("application/json")
                .content("{\"fecha\":\"" + LocalDate.now().plusDays(5) + "\",\"franja\":\"MANANA\",\"cuadrilla\":\"Norte\"}")
                .with(FUN)).andExpect(status().isConflict());
        mvc.perform(put("/requests/" + id + "/responsable").contentType("application/json")
                .content("{\"responsable\":\"carlos@duocuc.cl\"}").with(FUN)).andExpect(status().isConflict());
        mvc.perform(put("/requests/" + id + "/en-camino").with(FUN)).andExpect(status().isConflict());
        mvc.perform(put("/requests/" + id + "/visita/confirmar").with(FUN)).andExpect(status().isConflict());
        mvc.perform(put("/requests/" + id + "/seguimiento/EN_GESTION").contentType("application/json")
                .content("{\"nota\":\"x\"}").with(FUN)).andExpect(status().isConflict());
        visibilidad(id, ADM, false, "Duplicado").andExpect(status().isConflict());
        papelera(id, "Otra vez").andExpect(status().isConflict());
        // el vecino no se entera de que existe: 404, no el 409 de la papelera
        mvc.perform(post("/requests/" + id + "/contactos").contentType("application/json")
                .content("{\"nombre\":\"Hija\",\"correo\":\"hija@correo.cl\"}").with(VEC)).andExpect(status().isNotFound());
    }

    @Test
    void restaurar_deja_la_visibilidad_que_tenia() throws Exception {
        long oculto = crear();
        visibilidad(oculto, ADM, false, "Duplicado").andExpect(status().isOk());
        papelera(oculto, "Limpieza").andExpect(status().isOk());
        restaurar(oculto, ADM, null).andExpect(status().isBadRequest());
        restaurar(oculto, ADM, "Se borro por error").andExpect(status().isOk())
                .andExpect(jsonPath("$.eliminadoEn").doesNotExist())
                .andExpect(jsonPath("$.eliminadoPor").doesNotExist())
                .andExpect(jsonPath("$.visibleVecino").value(false));
        mvc.perform(get("/requests/" + oculto).with(VEC)).andExpect(status().isNotFound());

        long visible = crear();
        papelera(visible, "Limpieza").andExpect(status().isOk());
        restaurar(visible, ADM, "Se borro por error").andExpect(status().isOk())
                .andExpect(jsonPath("$.visibleVecino").value(true));
        mvc.perform(get("/requests/" + visible).with(VEC)).andExpect(status().isOk());

        restaurar(visible, ADM, "Otra vez").andExpect(status().isConflict()); // ya no esta en la papelera
    }

    @Test
    void el_auditor_restaura_solo_con_la_regla_auditorRestaura() throws Exception {
        long id = crear();
        papelera(id, "Limpieza").andExpect(status().isOk());

        restaurar(id, AUD, "Revision").andExpect(status().isForbidden());
        restaurar(id, FUN, "Revision").andExpect(status().isForbidden());
        restaurar(id, VEC, "Revision").andExpect(status().isForbidden());

        ponerReglas(regla("auditorRestaura", true)).andExpect(status().isOk());
        restaurar(id, AUD, "Revision").andExpect(status().isOk()).andExpect(jsonPath("$.eliminadoEn").doesNotExist());
    }

    @Test
    void sin_mostrarNombreFuncionario_el_vecino_no_ve_nombres_ni_en_el_seguimiento() throws Exception {
        long id = crear(FUN, "{\"tipoId\":1,\"descripcion\":\"Poda\",\"vecinoUsername\":\"" + VECINO + "\"}");
        estado(id, "ADMITIDO").andExpect(status().isOk());
        estado(id, "EN_GESTION").andExpect(status().isOk());
        mvc.perform(get("/requests/" + id).with(VEC))
                .andExpect(jsonPath("$.creadoPor").value(FUNC))
                .andExpect(jsonPath("$.responsable").value(FUNC));

        ponerReglas(regla("mostrarNombreFuncionario", false)).andExpect(status().isOk());

        mvc.perform(get("/requests/" + id).with(VEC)).andExpect(status().isOk())
                .andExpect(jsonPath("$.funcionarioAsignado").doesNotExist())
                .andExpect(jsonPath("$.responsable").doesNotExist())
                .andExpect(jsonPath("$.creadoPor").doesNotExist());
        String lista = mvc.perform(get("/requests").with(VEC)).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> mios = JsonPath.read(lista, "$[?(@.id == " + id + ")]");
        assertNull(mios.get(0).get("funcionarioAsignado"));
        assertNull(mios.get(0).get("creadoPor"));
        String pasos = mvc.perform(get("/requests/" + id + "/seguimiento").with(VEC)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Object> actualizadoPor = JsonPath.read(pasos, "$[*].actualizadoPor");
        assertEquals(5, actualizadoPor.size());
        assertTrue(actualizadoPor.stream().allMatch(java.util.Objects::isNull), pasos);
        // tambien en la respuesta de una accion del vecino
        mvc.perform(post("/requests/" + id + "/contactos").contentType("application/json")
                        .content("{\"nombre\":\"Hija\",\"correo\":\"hija@correo.cl\"}").with(VEC))
                .andExpect(jsonPath("$.responsable").doesNotExist());

        // el funcionario sigue viendo todo
        mvc.perform(get("/requests/" + id).with(FUN)).andExpect(jsonPath("$.creadoPor").value(FUNC));
        mvc.perform(get("/requests/" + id + "/seguimiento").with(FUN))
                .andExpect(jsonPath("$[1].actualizadoPor").value(FUNC));
    }

    /** El motivo de ocultar es una nota interna del Admin: aunque vuelva a mostrarse, al vecino no le llega. */
    @Test
    void el_vecino_nunca_ve_el_motivo_interno() throws Exception {
        long id = crear();
        visibilidad(id, ADM, false, "Sospecha de fraude").andExpect(status().isOk());
        visibilidad(id, ADM, true, "Revisado, era real").andExpect(status().isOk());

        mvc.perform(get("/requests/" + id).with(VEC)).andExpect(status().isOk())
                .andExpect(jsonPath("$.visibleVecino").value(true))
                .andExpect(jsonPath("$.motivoVisibilidad").doesNotExist());
        mvc.perform(get("/requests/" + id).with(ADM))
                .andExpect(jsonPath("$.motivoVisibilidad").value("Revisado, era real"));
    }

    @Test
    void reglas_fuera_de_las_opciones_400_y_cambios_auditados() throws Exception {
        mvc.perform(get("/requests/reglas").with(VEC)).andExpect(status().isOk())
                .andExpect(jsonPath("$.mesesHistorial").value(12))
                .andExpect(jsonPath("$.diasPapelera").value(90))
                .andExpect(jsonPath("$.alVencer").value("ANONIMIZAR"));

        ponerReglas(regla("mesesHistorial", 7)).andExpect(status().isBadRequest());
        ponerReglas(regla("diasPapelera", 60)).andExpect(status().isBadRequest());
        ponerReglas(regla("alVencer", "QUEMAR")).andExpect(status().isBadRequest());
        ponerReglas("{\"mostrarNombreFuncionario\":true}").andExpect(status().isBadRequest());
        mvc.perform(put("/requests/reglas").contentType("application/json").content(REGLAS_POR_DEFECTO).with(FUN))
                .andExpect(status().isForbidden());

        ponerReglas(regla("mesesHistorial", 24)).andExpect(status().isOk()).andExpect(jsonPath("$.mesesHistorial").value(24));
        mvc.perform(get("/requests/reglas").with(FUN)).andExpect(jsonPath("$.mesesHistorial").value(24));
        verify(requestsEventPublisher, timeout(2000)).auditarReglas(ADMIN, "mesesHistorial 12 -> 24");

        // guardar lo mismo no deja una entrada vacia en la auditoria
        ponerReglas(regla("mesesHistorial", 24)).andExpect(status().isOk());
        verify(requestsEventPublisher, after(300).times(1)).auditarReglas(any(), any());
    }

    @Test
    void con_mostrarRechazados_apagado_el_vecino_no_ve_sus_rechazados() throws Exception {
        long id = crear();
        estado(id, "RECHAZADO").andExpect(status().isOk());
        assertTrue(idsListados(VEC, "").contains((int) id));

        ponerReglas(regla("mostrarRechazados", false)).andExpect(status().isOk());

        assertFalse(idsListados(VEC, "").contains((int) id));
        mvc.perform(get("/requests/" + id).with(VEC)).andExpect(status().isNotFound());
        assertTrue(idsListados(FUN, "").contains((int) id));
    }

    @Test
    void la_papelera_solo_la_ven_admin_y_auditor() throws Exception {
        long vigente = crear();
        long borrado = crear();
        papelera(borrado, "Limpieza").andExpect(status().isOk());

        mvc.perform(get("/requests").param("papelera", "true").with(FUN)).andExpect(status().isForbidden());
        mvc.perform(get("/requests").param("papelera", "true").with(VEC)).andExpect(status().isForbidden());

        List<Integer> papeleraAdmin = idsListados(ADM, "?papelera=true");
        assertTrue(papeleraAdmin.contains((int) borrado));
        assertFalse(papeleraAdmin.contains((int) vigente));
        assertTrue(idsListados(AUD, "?papelera=true").contains((int) borrado));

        List<Integer> vigentes = idsListados(ADM, "");
        assertTrue(vigentes.contains((int) vigente));
        assertFalse(vigentes.contains((int) borrado));
        assertFalse(idsListados(FUN, "").contains((int) borrado));
    }

    @Test
    void enviar_a_la_papelera_no_devuelve_el_cupo_del_dia() throws Exception {
        when(catalogClient.listarTipos(any()))
                .thenReturn(List.of(new CatalogClient.TipoTramiteView(1L, "Poda de arbol", 10_000, true)));
        long id = crear();
        estado(id, "ADMITIDO").andExpect(status().isOk());
        int antes = JsonPath.read(mvc.perform(get("/requests/cupos").with(FUN)).andReturn().getResponse()
                .getContentAsString(), "$[0].admitidosHoy");

        papelera(id, "Limpieza").andExpect(status().isOk());

        int despues = JsonPath.read(mvc.perform(get("/requests/cupos").with(FUN)).andReturn().getResponse()
                .getContentAsString(), "$[0].admitidosHoy");
        assertEquals(antes, despues);
    }

    @Test
    void solo_el_admin_envia_a_la_papelera() throws Exception {
        long id = crear();
        mvc.perform(delete("/requests/" + id).param("motivo", "Limpieza").with(FUN)).andExpect(status().isForbidden());
        mvc.perform(delete("/requests/" + id).param("motivo", "Limpieza").with(AUD)).andExpect(status().isForbidden());
        mvc.perform(delete("/requests/" + id).param("motivo", "Limpieza").with(VEC)).andExpect(status().isForbidden());
    }
}
