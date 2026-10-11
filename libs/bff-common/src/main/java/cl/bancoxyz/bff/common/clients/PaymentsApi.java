package cl.bancoxyz.bff.common.clients;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import cl.bancoxyz.bff.common.clients.Dtos.PaymentCommand;
import cl.bancoxyz.bff.common.clients.Dtos.PaymentDto;

@FeignClient(name = "payments-service", url = "${clients.payments.url:}")
public interface PaymentsApi {

    @PostMapping("/payments/transfer")
    PaymentDto transfer(@RequestHeader("Authorization") String bearer, @RequestHeader("Idempotency-Key") String key,
                        @RequestBody PaymentCommand command);

    @PostMapping("/payments/withdraw")
    PaymentDto withdraw(@RequestHeader("Authorization") String bearer, @RequestHeader("Idempotency-Key") String key,
                        @RequestBody PaymentCommand command);

    @GetMapping("/payments/{id}")
    PaymentDto get(@RequestHeader("Authorization") String bearer, @PathVariable("id") UUID id);
}
