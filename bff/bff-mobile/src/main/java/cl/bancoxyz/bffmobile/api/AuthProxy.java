package cl.bancoxyz.bffmobile.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

import cl.bancoxyz.bffmobile.MobileProperties;
import cl.bancoxyz.bffmobile.api.MobileDtos.Tokens;
import cl.bancoxyz.platform.web.ApiException;

/**
 * Intercambia el código (o el refresh token) con el auth-server usando el secreto del cliente confidencial mobile-client.
 * La app hace Authorization Code + PKCE (ella genera y guarda el code_verifier) pero NUNCA conoce el secreto: ver ADR-006.
 * Solo se admiten las redirect URI registradas y solo los grants authorization_code y refresh_token.
 */
@Component
public class AuthProxy {

    private final MobileProperties.Auth config;
    private final RestClient http;

    public AuthProxy(MobileProperties props, RestClient.Builder builder) {
        this.config = props.auth();
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1500);
        factory.setReadTimeout(3000);
        this.http = builder.requestFactory(factory).build();
    }

    public Tokens exchangeCode(String code, String codeVerifier, String redirectUri) {
        List<String> allowed = config.allowedRedirectUris();
        if (!allowed.contains(redirectUri)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REDIRECT_URI_NOT_ALLOWED", "redirectUri no autorizada");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("code_verifier", codeVerifier);
        return call(form);
    }

    public Tokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return call(form);
    }

    private Tokens call(MultiValueMap<String, String> form) {
        try {
            JsonNode res = http.post().uri(config.tokenUri())
                    .headers(h -> h.setBasicAuth(config.clientId(), config.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
            if (res == null || !res.hasNonNull("access_token")) {
                throw ApiException.unavailable("AUTH_UNAVAILABLE", "Servicio temporalmente no disponible");
            }
            return new Tokens(res.get("access_token").asText(), res.path("refresh_token").asText(null),
                    res.path("expires_in").asLong(), res.path("scope").asText(null));
        } catch (HttpClientErrorException e) {
            // 400/401 del auth-server: código inválido, verifier incorrecto, refresh revocado… (detalle neutro hacia la app)
            String error = "invalid_grant";
            try {
                error = e.getResponseBodyAs(Map.class) instanceof Map<?, ?> m && m.get("error") != null ? String.valueOf(m.get("error")) : error;
            } catch (RuntimeException ignored) {
                // cuerpo no JSON
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_" + error.toUpperCase(), "No se pudo completar la autenticación");
        } catch (RestClientException e) {
            throw ApiException.unavailable("AUTH_UNAVAILABLE", "Servicio temporalmente no disponible");
        }
    }
}
