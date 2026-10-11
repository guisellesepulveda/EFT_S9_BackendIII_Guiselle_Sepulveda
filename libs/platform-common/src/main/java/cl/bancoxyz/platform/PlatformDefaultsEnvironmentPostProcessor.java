package cl.bancoxyz.platform;

import java.io.IOException;
import java.util.Properties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Agrega platform-defaults.properties con la PRECEDENCIA MÁS BAJA: application.yml, variables de entorno y el
 * Config Server siempre ganan. Reemplaza a @PropertySource, que no es fiable en auto-configuraciones.
 */
public class PlatformDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String NAME = "platformDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(NAME)) {
            return;
        }
        try {
            Properties p = PropertiesLoaderUtils.loadProperties(new ClassPathResource("platform-defaults.properties"));
            environment.getPropertySources().addLast(new PropertiesPropertySource(NAME, p));
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer platform-defaults.properties", e);
        }
    }
}
