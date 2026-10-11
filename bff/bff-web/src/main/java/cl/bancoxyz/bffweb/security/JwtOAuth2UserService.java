package cl.bancoxyz.bffweb.security;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;

/**
 * Construye el usuario de la sesión a partir de los claims del ACCESS TOKEN (JWT del auth-server, obtenido por el canal
 * seguro back-channel), sin llamar a un endpoint de userinfo. Rechaza el inicio de sesión si el token no es del canal web:
 * un token emitido para otro cliente (móvil, ATM) jamás abre una sesión del BFF web.
 */
public class JwtOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    public static final String CHANNEL = "web";

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        JWTClaimsSet claims;
        try {
            claims = JWTParser.parse(request.getAccessToken().getTokenValue()).getJWTClaimsSet();
        } catch (ParseException e) {
            throw reject("invalid_token", "El access token no es un JWT válido");
        }
        String channel = claims.getClaim("channel") instanceof String c ? c : null;
        if (!CHANNEL.equals(channel)) {
            throw reject("wrong_channel", "El token no pertenece al canal web");
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        request.getAccessToken().getScopes().forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));
        Object roles = claims.getClaim("roles");
        if (roles instanceof List<?> list) {
            list.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sub", claims.getSubject());
        attributes.put("channel", channel);
        attributes.put("roles", roles == null ? List.of() : roles);
        return new DefaultOAuth2User(authorities, attributes, "sub");
    }

    private static OAuth2AuthenticationException reject(String code, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, description, null));
    }
}
