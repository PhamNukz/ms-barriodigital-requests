package cl.duoc.barriodigital.requests.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CatalogClient {

    private final RestClient http;

    public CatalogClient(@Value("${barriodigital.catalog.base-url}") String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    public record TipoTramiteView(Long id, String nombre, Integer cupoDiario, boolean activo) {
    }

    public TipoTramiteView obtenerTipo(Long tipoId, String bearer) {
        return http.get().uri("/catalog/procedures/{id}", tipoId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .retrieve()
                .body(TipoTramiteView.class);
    }
}
