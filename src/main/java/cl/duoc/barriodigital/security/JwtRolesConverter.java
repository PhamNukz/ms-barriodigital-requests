package cl.duoc.barriodigital.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Roles de Azure AD (claim "roles": Admin, Funcionario, Vecino, Auditor) -> ROLE_x. */
public class JwtRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();

        Object roles = jwt.getClaims().get("roles");
        if (roles instanceof Collection<?> c) {
            c.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        }

        String scp = jwt.getClaimAsString("scp");
        if (scp != null) {
            for (String s : scp.split(" ")) {
                if (!s.isBlank()) authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
            }
        }
        return List.copyOf(authorities);
    }
}
