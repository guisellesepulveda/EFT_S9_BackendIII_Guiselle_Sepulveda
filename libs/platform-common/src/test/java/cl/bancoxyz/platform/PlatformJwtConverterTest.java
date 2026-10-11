package cl.bancoxyz.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import cl.bancoxyz.platform.security.PlatformJwtConverter;

class PlatformJwtConverterTest {

    private static Jwt jwt(java.util.Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(b::claim);
        return b.build();
    }

    @Test
    void scopesBecomeScopeAuthoritiesAndRolesBecomeRoleAuthorities() {
        var auth = PlatformJwtConverter.create().convert(jwt(java.util.Map.of("scope", "web:read web:write",
                "roles", List.of("ADMIN", "BATCH_ADMIN"))));
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("SCOPE_web:read", "SCOPE_web:write", "ROLE_ADMIN", "ROLE_BATCH_ADMIN");
    }

    @Test
    void tokenWithoutRolesHasOnlyScopes() {
        var auth = PlatformJwtConverter.create().convert(jwt(java.util.Map.of("scope", "atm:operate")));
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("SCOPE_atm:operate");
    }
}
