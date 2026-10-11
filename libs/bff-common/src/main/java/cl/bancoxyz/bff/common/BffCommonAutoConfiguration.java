package cl.bancoxyz.bff.common;

import java.time.Duration;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

import cl.bancoxyz.bff.common.clients.AccountsApi;
import cl.bancoxyz.bff.common.clients.AccountsBackend;
import cl.bancoxyz.bff.common.clients.CustomersBackend;
import cl.bancoxyz.bff.common.clients.PaymentsBackend;
import cl.bancoxyz.bff.common.security.CustomerResolver;

@AutoConfiguration
@EnableFeignClients(basePackageClasses = AccountsApi.class)
public class BffCommonAutoConfiguration {

    @Bean
    AccountsBackend accountsBackend(AccountsApi api, @Qualifier("resilienceExecutor") Executor executor) {
        return new AccountsBackend(api, executor);
    }

    @Bean
    CustomersBackend customersBackend(cl.bancoxyz.bff.common.clients.CustomersApi api, @Qualifier("resilienceExecutor") Executor executor) {
        return new CustomersBackend(api, executor);
    }

    @Bean
    PaymentsBackend paymentsBackend(cl.bancoxyz.bff.common.clients.PaymentsApi api, @Qualifier("resilienceExecutor") Executor executor) {
        return new PaymentsBackend(api, executor);
    }

    @Bean
    @ConditionalOnMissingBean
    CustomerResolver customerResolver(CustomersBackend customers, AccountsBackend accounts,
                                      @Value("${bff.customer-cache-ttl:5m}") Duration ttl) {
        return new CustomerResolver(customers, accounts, ttl);
    }
}
