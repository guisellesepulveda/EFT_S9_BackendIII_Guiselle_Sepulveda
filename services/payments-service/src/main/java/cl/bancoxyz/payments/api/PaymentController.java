package cl.bancoxyz.payments.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.bancoxyz.platform.events.payload.TransactionType;
import cl.bancoxyz.platform.security.CanRead;
import cl.bancoxyz.platform.security.CanWrite;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/payments")
@Tag(name = "Pagos", description = "Transferencias, depósitos y retiros (saga asíncrona; consultar el estado con GET /payments/{id})")
public class PaymentController {

    private static final String IDEMPOTENCY = "Idempotency-Key";

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping("/transfer")
    @CanWrite
    @Bulkhead(name = "payments", type = Bulkhead.Type.SEMAPHORE)
    @Operation(summary = "Transferencia entre cuentas (202 Accepted; saga débito → crédito)")
    public ResponseEntity<PaymentResponse> transfer(@Valid @RequestBody PaymentRequest req,
                                                    @RequestHeader(value = IDEMPOTENCY, required = false) String key) {
        return respond(service.request(TransactionType.TRANSFER, req, key));
    }

    @PostMapping("/deposit")
    @CanWrite
    @Bulkhead(name = "payments", type = Bulkhead.Type.SEMAPHORE)
    @Operation(summary = "Depósito en una cuenta")
    public ResponseEntity<PaymentResponse> deposit(@Valid @RequestBody PaymentRequest req,
                                                   @RequestHeader(value = IDEMPOTENCY, required = false) String key) {
        return respond(service.request(TransactionType.DEPOSIT, req, key));
    }

    @PostMapping("/withdraw")
    @CanWrite
    @Bulkhead(name = "payments", type = Bulkhead.Type.SEMAPHORE)
    @Operation(summary = "Retiro desde una cuenta")
    public ResponseEntity<PaymentResponse> withdraw(@Valid @RequestBody PaymentRequest req,
                                                    @RequestHeader(value = IDEMPOTENCY, required = false) String key) {
        return respond(service.request(TransactionType.WITHDRAWAL, req, key));
    }

    @GetMapping("/{id}")
    @CanRead
    @Operation(summary = "Estado del pago con su historial")
    public PaymentResponse get(@PathVariable UUID id) {
        return PaymentResponse.of(service.get(id), service.history(id));
    }

    @GetMapping
    @CanRead
    @Operation(summary = "Pagos de una cuenta (más recientes primero)")
    public List<PaymentResponse> byAccount(@RequestParam UUID accountId, @RequestParam(defaultValue = "20") int limit) {
        return service.byAccount(accountId, limit).stream().map(p -> PaymentResponse.of(p, null)).toList();
    }

    private ResponseEntity<PaymentResponse> respond(PaymentService.Outcome outcome) {
        PaymentResponse body = PaymentResponse.of(outcome.payment(), service.history(outcome.payment().id()));
        if (outcome.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).location(URI.create("/payments/" + body.paymentId())).body(body);
    }
}
