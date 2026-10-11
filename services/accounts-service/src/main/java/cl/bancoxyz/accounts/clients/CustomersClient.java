package cl.bancoxyz.accounts.clients;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** name = id en Eureka (balanceo con Spring Cloud LoadBalancer); url vacío salvo en tests. */
@FeignClient(name = "customers-service", url = "${clients.customers.url:}")
public interface CustomersClient {

    @GetMapping("/customers/{id}")
    CustomerView get(@PathVariable("id") UUID id);
}
