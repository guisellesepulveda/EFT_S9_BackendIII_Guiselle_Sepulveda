package cl.bancoxyz.payments.clients;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "accounts-service", url = "${clients.accounts.url:}")
public interface AccountsClient {

    @GetMapping("/accounts/{id}")
    AccountView get(@PathVariable("id") UUID id);

    @PostMapping("/accounts/{id}/credit")
    void credit(@PathVariable("id") UUID id, @RequestBody CreditRequest request);
}
