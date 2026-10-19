package cl.duoc.barriodigital.requests;

import cl.duoc.barriodigital.requests.amqp.NotificationPublisher;
import cl.duoc.barriodigital.requests.client.CatalogClient;
import cl.duoc.barriodigital.requests.kafka.RequestsEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestsApiSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    JwtDecoder jwtDecoder;

    // Evitan que el @TransactionalEventListener (AFTER_COMMIT) intente conectar
    // a un RabbitMQ/Kafka real al crear un tramite en estos tests de HTTP/seguridad.
    @MockBean
    NotificationPublisher notificationPublisher;
    @MockBean
    RequestsEventPublisher requestsEventPublisher;

    @MockBean
    CatalogClient catalogClient;

    @Test
    void listar_sin_token_401() throws Exception {
        mvc.perform(get("/requests")).andExpect(status().isUnauthorized());
    }

    @Test
    void vecino_puede_crear_su_tramite() throws Exception {
        mvc.perform(post("/requests")
                .contentType("application/json")
                .content("{\"tipoId\":1,\"descripcion\":\"Bache en la calle\",\"direccion\":\"Calle Falsa 123\"}")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Vecino"))
                        .jwt(j -> j.claim("preferred_username", "vecino1@duocuc.cl"))))
           .andExpect(status().isCreated());
    }

    @Test
    void auditor_no_puede_crear_tramite_403() throws Exception {
        mvc.perform(post("/requests")
                .contentType("application/json")
                .content("{\"tipoId\":1,\"descripcion\":\"x\"}")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Auditor"))))
           .andExpect(status().isForbidden());
    }

    @Test
    void vecino_no_puede_cambiar_estado_403() throws Exception {
        mvc.perform(put("/requests/1/status")
                .contentType("application/json")
                .content("{\"status\":\"ADMITIDO\"}")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Vecino"))))
           .andExpect(status().isForbidden());
    }

    @Test
    void cupos_sin_token_401() throws Exception {
        mvc.perform(get("/requests/cupos")).andExpect(status().isUnauthorized());
    }

    /**
     * /requests/cupos compite con /requests/{id}: si ganara la ruta con variable,
     * "cupos" no parsearia como Long y devolveria 400 en vez de la lista.
     */
    @Test
    void la_ruta_cupos_no_la_captura_el_id() throws Exception {
        when(catalogClient.listarTipos(any()))
                .thenReturn(java.util.List.of(new CatalogClient.TipoTramiteView(1L, "Poda de arbol", 5, true)));

        mvc.perform(get("/requests/cupos")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Funcionario"))))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].nombre").value("Poda de arbol"))
           .andExpect(jsonPath("$[0].disponible").value(5))
           .andExpect(jsonPath("$[0].reinicia").exists());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor como(String rol, String usuario) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + rol))
                .jwt(j -> j.claim("preferred_username", usuario));
    }

    private long crearComo(String rol, String usuario, String cuerpo) throws Exception {
        String json = mvc.perform(post("/requests").contentType("application/json").content(cuerpo).with(como(rol, usuario)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.<Number>read(json, "$.id").longValue();
    }

    @Test
    void funcionario_ingresa_un_tramite_a_nombre_de_un_vecino() throws Exception {
        mvc.perform(post("/requests").contentType("application/json")
                .content("{\"tipoId\":1,\"descripcion\":\"Poda\",\"vecinoUsername\":\"vecino9@duocuc.cl\"}")
                .with(como("Funcionario", "func1@duocuc.cl")))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.vecinoUsername").value("vecino9@duocuc.cl"))
           .andExpect(jsonPath("$.creadoPor").value("func1@duocuc.cl"));
    }

    @Test
    void vecino_no_puede_ingresar_a_nombre_de_otro_403() throws Exception {
        mvc.perform(post("/requests").contentType("application/json")
                .content("{\"tipoId\":1,\"descripcion\":\"Poda\",\"vecinoUsername\":\"otro@duocuc.cl\"}")
                .with(como("Vecino", "vecino1@duocuc.cl")))
           .andExpect(status().isForbidden());
    }

    @Test
    void correo_de_vecino_invalido_400() throws Exception {
        mvc.perform(post("/requests").contentType("application/json")
                .content("{\"tipoId\":1,\"descripcion\":\"Poda\",\"vecinoUsername\":\"no-es-correo\"}")
                .with(como("Funcionario", "func1@duocuc.cl")))
           .andExpect(status().isBadRequest());
    }

    @Test
    void el_vecino_ve_el_seguimiento_de_su_tramite_y_no_el_de_otro() throws Exception {
        long id = crearComo("Vecino", "vecino2@duocuc.cl", "{\"tipoId\":1,\"descripcion\":\"Luminaria\"}");

        mvc.perform(get("/requests/" + id + "/seguimiento").with(como("Vecino", "vecino2@duocuc.cl")))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(5))
           .andExpect(jsonPath("$[0].estado").value("INGRESADO"))
           .andExpect(jsonPath("$[0].fechaCumplido").exists())
           .andExpect(jsonPath("$[1].fechaCumplido").doesNotExist());

        mvc.perform(get("/requests/" + id + "/seguimiento").with(como("Vecino", "otro@duocuc.cl")))
           .andExpect(status().isForbidden());
    }

    @Test
    void funcionario_programa_fecha_y_nota_de_un_paso_pendiente() throws Exception {
        long id = crearComo("Vecino", "vecino3@duocuc.cl", "{\"tipoId\":1,\"descripcion\":\"Bache\"}");

        mvc.perform(put("/requests/" + id + "/seguimiento/EN_TERRENO").contentType("application/json")
                .content("{\"fechaEstimada\":\"2030-01-15T13:00:00Z\",\"nota\":\"Visita en la manana\"}")
                .with(como("Funcionario", "func1@duocuc.cl")))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[3].estado").value("EN_TERRENO"))
           .andExpect(jsonPath("$[3].fechaEstimada").value("2030-01-15T13:00:00Z"))
           .andExpect(jsonPath("$[3].nota").value("Visita en la manana"));
    }

    @Test
    void no_se_puede_poner_fecha_estimada_a_un_paso_cumplido_400() throws Exception {
        long id = crearComo("Vecino", "vecino4@duocuc.cl", "{\"tipoId\":1,\"descripcion\":\"Bache\"}");

        mvc.perform(put("/requests/" + id + "/seguimiento/INGRESADO").contentType("application/json")
                .content("{\"fechaEstimada\":\"2030-01-15T13:00:00Z\"}")
                .with(como("Funcionario", "func1@duocuc.cl")))
           .andExpect(status().isBadRequest());
    }

    @Test
    void vecino_no_puede_programar_pasos_403() throws Exception {
        mvc.perform(put("/requests/1/seguimiento/ADMITIDO").contentType("application/json")
                .content("{\"nota\":\"x\"}")
                .with(como("Vecino", "vecino1@duocuc.cl")))
           .andExpect(status().isForbidden());
    }
}
