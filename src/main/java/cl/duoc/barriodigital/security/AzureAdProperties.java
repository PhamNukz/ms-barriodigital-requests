package cl.duoc.barriodigital.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

// ponytail: duplicado igual en cada microservicio de BarrioDigital (bff, requests, catalog).
// Si aparece un 4to consumidor, vale la pena publicarlo como libreria compartida
// (GitHub Packages); con 3 repos, copiar estas 4 clases es mas simple que mantener
// un artefacto Maven publicado.
@ConfigurationProperties(prefix = "barriodigital.security")
public record AzureAdProperties(
        String issuerUri,
        List<String> audiences,
        String requiredScope) {

    public AzureAdProperties {
        audiences = audiences == null ? List.of() : List.copyOf(audiences);
    }
}
