package cl.bancoxyz.payments.clients;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "customers-service", url = "${clients.customers.url:}")
public interface CustomersClient {

    @GetMapping("/customers/{id}")
    CustomerView get(@PathVariable("id") UUID id);
}
