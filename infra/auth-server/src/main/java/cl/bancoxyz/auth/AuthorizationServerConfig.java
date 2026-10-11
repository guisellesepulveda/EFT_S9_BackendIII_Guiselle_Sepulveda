package cl.bancoxyz.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * Un cliente por canal (web, móvil, ATM) con scopes y grants distintos, más el cliente interno para
 * comunicación entre servicios:
 * <ul>
 *   <li>web-client: authorization_code + PKCE (confidencial, usado por bff-web) → web:read web:write</li>
 *   <li>mobile-client: authorization_code + PKCE + refresh token rotado, access token corto → mobile:read mobile:transfer
 *       (confidencial: el secreto vive en bff-mobile; ver ADR-006)</li>
 *   <li>atm-client: client_credentials → atm:operate (en la Etapa 3 se autentica además con mTLS)</li>
 *   <li>internal-service: client_credentials → internal</li>
 * </ul>
 */
@Configuration
class AuthorizationServerConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class).oidc(Customizer.withDefaults());
        http.exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(AuthProperties props, PasswordEncoder encoder) {
        AuthProperties.Users u = props.users();
        return new InMemoryUserDetailsManager(
                User.withUsername("customer1").password(encoder.encode(u.customerPassword())).roles("CUSTOMER").build(),
                User.withUsername("admin").password(encoder.encode(u.adminPassword())).roles("ADMIN").build(),
                User.withUsername("batch-admin").password(encoder.encode(u.batchAdminPassword())).roles("BATCH_ADMIN").build());
    }

    @Bean
    RegisteredClientRepository registeredClients(AuthProperties props, PasswordEncoder encoder) {
        AuthProperties.Clients c = props.clients();

        RegisteredClient web = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("web-client")
                .clientSecret(encoder.encode(c.web().secret()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(c.web().redirectUris()))
                .scope(OidcScopes.OPENID).scope("web:read").scope("web:write")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(tokens(c.web()))
                .build();

        RegisteredClient mobile = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("mobile-client")
                // Confidencial a propósito: Spring Authorization Server NO emite refresh tokens a clientes públicos
                // (auth method NONE). El secreto lo guarda bff-mobile (servidor), nunca la app; PKCE sigue siendo obligatorio.
                .clientSecret(encoder.encode(c.mobile().secret()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(c.mobile().redirectUris()))
                .scope(OidcScopes.OPENID).scope("mobile:read").scope("mobile:transfer")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(tokens(c.mobile()))
                .build();

        RegisteredClient atm = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("atm-client")
                .clientSecret(encoder.encode(c.atm().secret()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("atm:operate")
                .tokenSettings(tokens(c.atm()))
                .build();

        RegisteredClient internal = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("internal-service")
                .clientSecret(encoder.encode(c.internal().secret()))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("internal")
                .tokenSettings(tokens(c.internal()))
                .build();

        return new InMemoryRegisteredClientRepository(web, mobile, atm, internal);
    }

    private static TokenSettings tokens(AuthProperties.Client client) {
        return TokenSettings.builder()
                .accessTokenTimeToLive(client.accessTokenTtl())
                .refreshTokenTimeToLive(client.refreshTokenTtl() == null ? Duration.ofHours(1) : client.refreshTokenTtl())
                .reuseRefreshTokens(false) // rotación: cada refresh entrega un refresh token nuevo
                .idTokenSignatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }

    /** Claims útiles para los servicios: roles del usuario y canal de origen (web|mobile|atm|internal). */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            if (!"access_token".equals(context.getTokenType().getValue())) {
                return;
            }
            String clientId = context.getRegisteredClient().getClientId();
            context.getClaims().claim("channel", clientId.replace("-client", "").replace("-service", ""));
            if (context.getPrincipal() != null && context.getPrincipal().getAuthorities() != null
                    && !AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
                List<String> roles = context.getPrincipal().getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).filter(a -> a.startsWith("ROLE_"))
                        .map(a -> a.substring("ROLE_".length())).collect(Collectors.toList());
                context.getClaims().claim("roles", roles);
            }
        };
    }

    @Bean
    JWKSource<SecurityContext> jwkSource() throws Exception {
        // Par RSA generado al arrancar (demo). En producción: claves persistentes/rotadas (KMS o Secrets Manager).
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(UUID.randomUUID().toString()).build();
        return new ImmutableJWKSet<>(new JWKSet(rsa));
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(AuthProperties props) {
        return AuthorizationServerSettings.builder().issuer(props.issuer()).build();
    }
}
