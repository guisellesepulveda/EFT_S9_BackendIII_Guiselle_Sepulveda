package cl.bancoxyz.bff.common.clients;

import java.util.List;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import cl.bancoxyz.bff.common.clients.Dtos.AccountDto;
import cl.bancoxyz.bff.common.clients.Dtos.BalanceDto;
import cl.bancoxyz.bff.common.clients.Dtos.MovementDto;

/** accounts-service por Eureka. El token del USUARIO se reenvía (token relay): los servicios autorizan por scope del canal. */
@FeignClient(name = "accounts-service", url = "${clients.accounts.url:}")
public interface AccountsApi {

    @GetMapping("/accounts")
    List<AccountDto> byCustomer(@RequestHeader("Authorization") String bearer, @RequestParam("customerId") UUID customerId);

    @GetMapping("/accounts/{id}")
    AccountDto get(@RequestHeader("Authorization") String bearer, @PathVariable("id") UUID id);

    @GetMapping("/accounts/{id}/balance")
    BalanceDto balance(@RequestHeader("Authorization") String bearer, @PathVariable("id") UUID id);

    @GetMapping("/accounts/{id}/movements")
    List<MovementDto> movements(@RequestHeader("Authorization") String bearer, @PathVariable("id") UUID id,
                                @RequestParam("limit") int limit);
}
