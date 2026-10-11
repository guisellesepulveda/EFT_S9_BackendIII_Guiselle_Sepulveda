package cl.bancoxyz.platform.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "jakarta.servlet.Filter")
public class PlatformWebAutoConfiguration {

    @Bean
    InstanceIdentity instanceIdentity(@Value("${spring.application.name:app}") String appName) {
        return InstanceIdentity.of(appName);
    }

    @Bean
    FilterRegistrationBean<InstanceIdFilter> instanceIdFilter(InstanceIdentity identity) {
        FilterRegistrationBean<InstanceIdFilter> reg = new FilterRegistrationBean<>(new InstanceIdFilter(identity.value()));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    @Bean
    @ConditionalOnClass(name = {"org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler",
            "jakarta.validation.ConstraintViolationException"})
    ApiExceptionAdvice apiExceptionAdvice() {
        return new ApiExceptionAdvice();
    }
}
