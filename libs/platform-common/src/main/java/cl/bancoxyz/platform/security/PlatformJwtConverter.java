package cl.bancoxyz.platform.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/** Autoridades = SCOPE_<scope> (claim scope/scp) + ROLE_<rol> (claim roles). */
public final class PlatformJwtConverter {

    private PlatformJwtConverter() {
    }

    public static JwtAuthenticationConverter create() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter(); // SCOPE_ por defecto
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter((Converter<Jwt, Collection<GrantedAuthority>>) jwt -> {
            List<GrantedAuthority> all = new ArrayList<>(scopes.convert(jwt));
            Object roles = jwt.getClaims().get("roles");
            if (roles instanceof Collection<?> c) {
                c.forEach(r -> all.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
            return all;
        });
        return converter;
    }

    /** Útil para pruebas: tipo explícito del convertidor. */
    public static Converter<Jwt, AbstractAuthenticationToken> asConverter() {
        return create();
    }
}
