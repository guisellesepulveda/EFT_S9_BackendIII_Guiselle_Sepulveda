package cl.bancoxyz.platform.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import cl.bancoxyz.platform.web.ApiException;
import feign.RequestInterceptor;

/**
 * Comunicación servicio-a-servicio autenticada: cada llamada Feign lleva un token client_credentials del cliente
 * "internal-service" (scope internal), obtenido del auth-server y cacheado hasta que expira.
 * Registro OAuth2 esperado: spring.security.oauth2.client.registration.internal (ver config-repo).
 */
@AutoConfiguration(after = OAuth2ClientAutoConfiguration.class)
@ConditionalOnClass({RequestInterceptor.class, OAuth2AuthorizedClientManager.class})
@ConditionalOnProperty(prefix = "platform.client.internal-token", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PlatformClientAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformClientAutoConfiguration.class);

    @Bean
    @ConditionalOnBean({ClientRegistrationRepository.class, OAuth2AuthorizedClientService.class})
    @ConditionalOnMissingBean(OAuth2AuthorizedClientManager.class)
    OAuth2AuthorizedClientManager internalClientManager(ClientRegistrationRepository registrations,
                                                        OAuth2AuthorizedClientService clients) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder
                .builder().clientCredentials().build());
        return manager;
    }

    @Bean
    @ConditionalOnBean(OAuth2AuthorizedClientManager.class)
    RequestInterceptor internalBearerInterceptor(OAuth2AuthorizedClientManager manager,
                                                 @Value("${platform.client.internal-token.registration-id:internal}") String registrationId,
                                                 @Value("${spring.application.name:service}") String principal) {
        return template -> {
            if (template.headers().containsKey(HttpHeaders.AUTHORIZATION)) {
                return;
            }
            OAuth2AuthorizedClient client;
            try {
                client = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                        .principal(principal).build());
            } catch (RuntimeException e) {
                log.error("No se pudo obtener el token interno: {}", e.toString());
                throw ApiException.unavailable("AUTH_UNAVAILABLE", "No se pudo autenticar la llamada entre servicios");
            }
            if (client == null) {
                throw ApiException.unavailable("AUTH_UNAVAILABLE", "No se pudo autenticar la llamada entre servicios");
            }
            template.header(HttpHeaders.AUTHORIZATION, "Bearer " + client.getAccessToken().getTokenValue());
        };
    }
}
