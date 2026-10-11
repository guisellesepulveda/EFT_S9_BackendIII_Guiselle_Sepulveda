package cl.bancoxyz.accounts.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.bancoxyz.accounts.ledger.LedgerService;
import cl.bancoxyz.accounts.ledger.OperationResult;
import cl.bancoxyz.accounts.ledger.OperationType;
import cl.bancoxyz.platform.security.InternalOnly;
import cl.bancoxyz.platform.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Operaciones síncronas entre servicios (compensaciones). Solo scope internal; idempotentes por operationId. */
@RestController
@RequestMapping("/accounts/{id}")
@Tag(name = "Operaciones internas", description = "Solo servicio-a-servicio (scope internal)")
public class OperationController {

    private final LedgerService ledger;

    public OperationController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/debit")
    @InternalOnly
    @Transactional
    @Operation(summary = "Debita la cuenta; idempotente por operationId")
    public OperationResponse debit(@PathVariable UUID id, @Valid @RequestBody OperationRequest r) {
        return respond(ledger.apply(r.operationId(), id, OperationType.DEBIT, r.amount(), r.reference(), r.description()));
    }

    @PostMapping("/credit")
    @InternalOnly
    @Transactional
    @Operation(summary = "Acredita la cuenta; idempotente por operationId")
    public OperationResponse credit(@PathVariable UUID id, @Valid @RequestBody OperationRequest r) {
        return respond(ledger.apply(r.operationId(), id, OperationType.CREDIT, r.amount(), r.reference(), r.description()));
    }

    private static OperationResponse respond(OperationResult result) {
        if (!result.applied()) {
            HttpStatus status = OperationResult.ACCOUNT_NOT_FOUND.equals(result.reasonCode()) ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
            throw new ApiException(status, result.reasonCode(), "Operación rechazada: " + result.reasonCode());
        }
        return new OperationResponse(result.operationId(), true, result.balanceAfter(), result.replayed());
    }
}
