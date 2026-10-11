package cl.bancoxyz.platform.resilience;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Pool donde se ejecutan las llamadas protegidas por @TimeLimiter. Propaga el contexto (traceId/spanId, MDC) al hilo
 * del pool para que las trazas de Zipkin y los logs de la llamada a la dependencia queden enlazados con la petición.
 */
@AutoConfiguration
@ConditionalOnClass(value = ThreadPoolTaskExecutor.class, name = "io.micrometer.context.ContextSnapshot")
public class PlatformResilienceAutoConfiguration {

    @Bean(name = "resilienceExecutor")
    @ConditionalOnMissingBean(name = "resilienceExecutor")
    ThreadPoolTaskExecutor resilienceExecutor(@Value("${platform.resilience.pool-size:16}") int size) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(size);
        ex.setMaxPoolSize(size);
        ex.setQueueCapacity(size * 4);
        ex.setThreadNamePrefix("resilience-");
        ex.setTaskDecorator(new ContextPropagatingTaskDecorator());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        return ex;
    }
}
