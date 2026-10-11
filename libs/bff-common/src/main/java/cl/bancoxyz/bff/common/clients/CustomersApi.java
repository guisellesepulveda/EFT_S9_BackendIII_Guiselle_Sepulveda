package cl.bancoxyz.bff.common.clients;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import cl.bancoxyz.bff.common.clients.Dtos.CustomerDto;
import cl.bancoxyz.bff.common.clients.Dtos.ProfileDto;

@FeignClient(name = "customers-service", url = "${clients.customers.url:}")
public interface CustomersApi {

    @GetMapping("/customers/by-username/{username}")
    CustomerDto byUsername(@RequestHeader("Authorization") String bearer, @PathVariable("username") String username);

    @GetMapping("/customers/{id}")
    CustomerDto get(@RequestHeader("Authorization") String bearer, @PathVariable("id") UUID id);

    @GetMapping("/customers/{id}/profile")
    ProfileDto profile(@RequestHeader("Authorization") String bearer, @PathVariable("id") UUID id);
}
