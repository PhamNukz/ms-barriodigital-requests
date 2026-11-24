package cl.duoc.barriodigital.requests;

import cl.duoc.barriodigital.requests.amqp.NotificationPublisher;
import cl.duoc.barriodigital.requests.client.CatalogClient;
import cl.duoc.barriodigital.requests.kafka.RequestsEventPublisher;
import com.jayway.jsonpath.JsonPath;
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
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato de visita y responsable (Vecino B / Funcionario B), por HTTP y con H2.
 * Mismos @MockBean que RequestsApiSecurityTest: comparten el contexto de Spring.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VisitaResponsableApiTest {

    private static final String VECINO = "vecino.visita@duocuc.cl";
    private static final String FUNC = "func1@duocuc.cl";
    private static final LocalDate FUTURO = LocalDate.now().plusDays(5);

    @Autowired
    MockMvc mvc;

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

    private static RequestPostProcessor como(String rol, String usuario) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + rol))
                .jwt(j -> j.claim("preferred_username", usuario));
    }

    private long crear() throws Exception {
        String json = mvc.perform(post("/requests").contentType("application/json")
                        .content("{\"tipoId\":1,\"descripcion\":\"Poda\",\"direccion\":\"Calle 1\"}")
                        .with(como("Vecino", VECINO)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(json, "$.id").longValue();
    }

    private ResultActions estado(long id, String estado) throws Exception {
        return mvc.perform(put("/requests/" + id + "/status").contentType("application/json")
                .content("{\"status\":\"" + estado + "\",\"nota\":\"motivo de prueba\"}")
                .with(como("Funcionario", FUNC)));
    }

    private void avanzar(long id, String... estados) throws Exception {
        for (String e : estados) estado(id, e).andExpect(status().isOk());
    }

    private ResultActions proponer(long id, LocalDate fecha) throws Exception {
        return mvc.perform(put("/requests/" + id + "/visita").contentType("application/json")
                .content("{\"fecha\":\"" + fecha + "\",\"franja\":\"MANANA\",\"cuadrilla\":\"Cuadrilla Norte\"}")
                .with(como("Funcionario", FUNC)));
    }

    private ResultActions responder(long id, RequestPostProcessor quien, String cuerpo) throws Exception {
        return mvc.perform(post("/requests/" + id + "/visita/respuesta").contentType("application/json")
                .content(cuerpo).with(quien));
    }

    private ResultActions confirmar(long id) throws Exception {
        return responder(id, como("Vecino", VECINO), "{\"respuesta\":\"CONFIRMAR\"}");
    }

    private ResultActions enCamino(long id) throws Exception {
        return mvc.perform(put("/requests/" + id + "/en-camino").with(como("Funcionario", FUNC)));
    }

    @Test
    void proponer_visita_la_deja_propuesta_y_planifica_en_terreno_al_inicio_de_la_franja() throws Exception {
        long id = crear();
        avanzar(id, "ADMITIDO");

        proponer(id, FUTURO).andExpect(status().isOk())
                .andExpect(jsonPath("$.visita.estado").value("PROPUESTA"))
                .andExpect(jsonPath("$.visita.franja").value("MANANA"))
                .andExpect(jsonPath("$.visita.cuadrilla").value("Cuadrilla Norte"))
                .andExpect(jsonPath("$.visita.fecha").value(FUTURO.toString()));

        String nueveDeChile = FUTURO.atTime(9, 0).atZone(ZoneId.of("America/Santiago")).toInstant().toString();
        mvc.perform(get("/requests/" + id + "/seguimiento").with(como("Vecino", VECINO)))
                .andExpect(jsonPath("$[3].estado").value("EN_TERRENO"))
                .andExpect(jsonPath("$[3].fechaEstimada").value(nueveDeChile));
        mvc.perform(get("/requests/" + id).with(como("Vecino", VECINO)))
                .andExpect(jsonPath("$.visita.estado").value("PROPUESTA"));
        // Los dos listados (vecino y funcionario) cargan la visita con @EntityGraph.
        for (RequestPostProcessor quien : new RequestPostProcessor[]{como("Vecino", VECINO), como("Funcionario", FUNC)}) {
            mvc.perform(get("/requests").with(quien))
                    .andExpect(jsonPath("$[0].id").value(id))
                    .andExpect(jsonPath("$[0].visita.estado").value("PROPUESTA"));
        }
    }

    @Test
    void responder_sin_ser_el_dueno_403_aunque_sea_funcionario() throws Exception {
        long id = crear();
        avanzar(id, "ADMITIDO");
        proponer(id, FUTURO).andExpect(status().isOk());

        responder(id, como("Vecino", "otro@duocuc.cl"), "{\"respuesta\":\"CONFIRMAR\"}").andExpect(status().isForbidden());
        responder(id, como("Funcionario", FUNC), "{\"respuesta\":\"CONFIRMAR\"}").andExpect(status().isForbidden());
        responder(id, como("Admin", "admin@duocuc.cl"), "{\"respuesta\":\"CONFIRMAR\"}").andExpect(status().isForbidden());
    }

    @Test
    void responder_dos_veces_409() throws Exception {
        long id = crear();
        avanzar(id, "ADMITIDO");
        proponer(id, FUTURO).andExpect(status().isOk());

        confirmar(id).andExpect(status().isOk()).andExpect(jsonPath("$.visita.estado").value("CONFIRMADA"));
        responder(id, como("Vecino", VECINO), "{\"respuesta\":\"LLAMAR\"}").andExpect(status().isConflict());
    }

    @Test
    void cambiar_sin_horario_400_y_con_horario_queda_pedido() throws Exception {
        long id = crear();
        avanzar(id, "ADMITIDO");
        proponer(id, FUTURO).andExpect(status().isOk());

        responder(id, como("Vecino", VECINO), "{\"respuesta\":\"CAMBIAR\"}").andExpect(status().isBadRequest());
        responder(id, como("Vecino", VECINO), "{\"respuesta\":\"CAMBIAR\",\"horarioPedido\":\"vie 27, tarde\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visita.estado").value("CAMBIO_PEDIDO"))
                .andExpect(jsonPath("$.visita.horarioPedido").value("vie 27, tarde"));
    }

    @Test
    void responder_sin_visita_404() throws Exception {
        long id = crear();
        confirmar(id).andExpect(status().isNotFound());
    }

    @Test
    void visita_con_fecha_pasada_400() throws Exception {
        long id = crear();
        avanzar(id, "ADMITIDO");
        proponer(id, LocalDate.now().minusDays(2)).andExpect(status().isBadRequest());
    }

    @Test
    void proponer_visita_en_ingresado_o_resuelto_409() throws Exception {
        long ingresado = crear();
        proponer(ingresado, FUTURO).andExpect(status().isConflict());

        long resuelto = crear();
        avanzar(resuelto, "ADMITIDO", "EN_GESTION", "EN_TERRENO", "RESUELTO");
        proponer(resuelto, FUTURO).andExpect(status().isConflict());
    }

    @Test
    void en_terreno_con_visita_propuesta_409_y_confirmada_se_permite() throws Exception {
        long id = crear();
        avanzar(id, "ADMITIDO", "EN_GESTION");
        proponer(id, FUTURO).andExpect(status().isOk());

        estado(id, "EN_TERRENO").andExpect(status().isConflict());

        confirmar(id).andExpect(status().isOk());
        estado(id, "EN_TERRENO").andExpect(status().isOk());
    }

    @Test
    void en_terreno_sin_visita_se_permite() throws Exception {
        long id = crear();
        avanzar(id, "ADMITIDO", "EN_GESTION");
        estado(id, "EN_TERRENO").andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("EN_TERRENO"));
    }

    @Test
    void reprogramar_en_terreno_deja_la_visita_propuesta_de_nuevo() throws Exception {
        long id = crear();
        avanzar(id, "ADMITIDO", "EN_GESTION");
        proponer(id, FUTURO).andExpect(status().isOk());
        confirmar(id).andExpect(status().isOk());
        avanzar(id, "EN_TERRENO");
        enCamino(id).andExpect(status().isOk());

        // EN_TERRENO ya se cumplio: reprogramar no puede fallar por ponerle fecha estimada.
        proponer(id, FUTURO.plusDays(2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_TERRENO"))
                .andExpect(jsonPath("$.visita.estado").value("PROPUESTA"))
                .andExpect(jsonPath("$.visita.fecha").value(FUTURO.plusDays(2).toString()))
                .andExpect(jsonPath("$.enCaminoDesde").doesNotExist());
        confirmar(id).andExpect(status().isOk()).andExpect(jsonPath("$.visita.estado").value("CONFIRMADA"));
    }

    @Test
    void en_camino_es_idempotente_y_avisa_una_sola_vez() throws Exception {
        long id = crear();
        avanzar(id, "ADMITIDO", "EN_GESTION");
        enCamino(id).andExpect(status().isConflict());
        avanzar(id, "EN_TERRENO");

        String primera = JsonPath.read(enCamino(id).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.enCaminoDesde");
        String segunda = JsonPath.read(enCamino(id).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.enCaminoDesde");

        assertEquals(primera, segunda);
        verify(notificationPublisher, after(500).times(1)).notificarEmail(any(), eq("EN_CAMINO"), isNull());
    }

    @Test
    void en_gestion_asigna_responsable_y_el_tablero_lo_reasigna() throws Exception {
        long id = crear();
        avanzar(id, "ADMITIDO");
        estado(id, "EN_GESTION").andExpect(jsonPath("$.responsable").value(FUNC));

        mvc.perform(put("/requests/" + id + "/responsable").contentType("application/json")
                        .content("{\"responsable\":\"carlos.perez@duocuc.cl\"}").with(como("Funcionario", FUNC)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responsable").value("carlos.perez@duocuc.cl"))
                .andExpect(jsonPath("$.funcionarioAsignado").value(FUNC));
        mvc.perform(put("/requests/" + id + "/responsable").contentType("application/json")
                        .content("{\"responsable\":\"no-es-correo\"}").with(como("Funcionario", FUNC)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/requests/" + id + "/responsable").contentType("application/json")
                        .content("{\"responsable\":\"carlos.perez@duocuc.cl\"}").with(como("Vecino", VECINO)))
                .andExpect(status().isForbidden());
    }

    @Test
    void reasignar_un_tramite_cerrado_409() throws Exception {
        long id = crear();
        avanzar(id, "RECHAZADO");

        mvc.perform(put("/requests/" + id + "/responsable").contentType("application/json")
                        .content("{\"responsable\":\"carlos.perez@duocuc.cl\"}").with(como("Funcionario", FUNC)))
                .andExpect(status().isConflict());
    }

    @Test
    void rechazar_publica_el_correo_con_el_motivo() throws Exception {
        long id = crear();
        avanzar(id, "RECHAZADO");

        verify(notificationPublisher, timeout(2000))
                .notificarEmail(argThat(t -> t.getId() == id), eq("RECHAZADO"), eq("motivo de prueba"));
    }
}
