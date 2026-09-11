package cl.duoc.barriodigital.requests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestsApiSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    JwtDecoder jwtDecoder;

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
}
