package cl.duoc.barriodigital.requests.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Component
public class CatalogClient {

    private final RestClient http;

    /**
     * Timeouts cortos: crear un tramite consulta el catalogo, y sin limite un catalogo colgado dejaba
     * el ingreso colgado (el BFF corta a los 10 s). Con el timeout, la creacion sigue sin validar.
     */
    public CatalogClient(@Value("${barriodigital.catalog.base-url}") String baseUrl) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    public record TipoTramiteView(Long id, String nombre, Integer cupoDiario, boolean activo) {
    }

    public TipoTramiteView obtenerTipo(Long tipoId, String bearer) {
        return http.get().uri("/catalog/procedures/{id}", tipoId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .retrieve()
                .body(TipoTramiteView.class);
    }

    /** Todos los tipos, para calcular el cupo de cada uno en una sola consulta. */
    public List<TipoTramiteView> listarTipos(String bearer) {
        List<TipoTramiteView> tipos = http.get().uri("/catalog/procedures")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .retrieve()
                .body(new ParameterizedTypeReference<List<TipoTramiteView>>() {});
        return tipos == null ? List.of() : tipos;
    }
}
