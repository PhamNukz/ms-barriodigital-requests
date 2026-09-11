package cl.duoc.barriodigital.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final List<String> aceptadas;
    private final String requiredScope;

    public AudienceValidator(List<String> aceptadas, String requiredScope) {
        this.aceptadas = aceptadas;
        this.requiredScope = requiredScope;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (aceptadas.stream().noneMatch(a -> jwt.getAudience().contains(a))) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "El claim aud " + jwt.getAudience() + " no corresponde a esta API",
                    null));
        }
        if (requiredScope != null && !requiredScope.isBlank()) {
            String scp = jwt.getClaimAsString("scp");
            boolean ok = scp != null && List.of(scp.split(" ")).contains(requiredScope);
            if (!ok) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "insufficient_scope",
                        "Falta el scope requerido: " + requiredScope,
                        null));
            }
        }
        return OAuth2TokenValidatorResult.success();
    }
}
