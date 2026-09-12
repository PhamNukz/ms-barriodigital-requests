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
import static org.mockito.ArgumentMatchers.anyLong;
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
    void cupo_sin_token_401() throws Exception {
        mvc.perform(get("/requests/tipos/1/cupo")).andExpect(status().isUnauthorized());
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

    @Test
    void vecino_puede_ver_cupo_del_dia() throws Exception {
        when(catalogClient.obtenerTipo(anyLong(), any()))
                .thenReturn(new CatalogClient.TipoTramiteView(1L, "Poda de arbol", 5, true));

        mvc.perform(get("/requests/tipos/1/cupo")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_Vecino"))))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.cupoDiario").value(5))
           .andExpect(jsonPath("$.admitidosHoy").value(0))
           .andExpect(jsonPath("$.disponible").value(5));
    }
}
