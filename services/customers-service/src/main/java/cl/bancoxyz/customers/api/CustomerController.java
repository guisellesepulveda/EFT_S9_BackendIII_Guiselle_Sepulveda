package cl.bancoxyz.customers.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.bancoxyz.customers.domain.CustomerStatus;
import cl.bancoxyz.platform.security.CanRead;
import cl.bancoxyz.platform.security.CanWrite;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/customers")
@Tag(name = "Clientes", description = "Datos personales y perfil")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @PostMapping
    @CanWrite
    @Operation(summary = "Crea un cliente (documento y email únicos)")
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        CustomerResponse created = CustomerResponse.of(service.create(request));
        return ResponseEntity.created(URI.create("/customers/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @CanRead
    @Operation(summary = "Obtiene un cliente")
    public CustomerResponse get(@PathVariable UUID id) {
        return CustomerResponse.of(service.get(id));
    }

    @GetMapping("/by-username/{username}")
    @CanRead
    @Operation(summary = "Cliente vinculado a un usuario de inicio de sesión (lo usan los BFF para resolver 'mi cliente')")
    public CustomerResponse byUsername(@PathVariable String username) {
        return CustomerResponse.of(service.byUsername(username));
    }

    @PutMapping("/{id}")
    @CanWrite
    @Operation(summary = "Actualiza datos de contacto (el documento no cambia)")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest request) {
        return CustomerResponse.of(service.update(id, request));
    }

    @GetMapping
    @CanRead
    @Operation(summary = "Lista clientes con paginación y filtro opcional por estado")
    public PageResponse<CustomerResponse> list(@RequestParam(required = false) CustomerStatus status,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.list(status, page, size), CustomerResponse::of);
    }

    @GetMapping("/{id}/profile")
    @CanRead
    @Operation(summary = "Perfil del cliente: datos esenciales y resumen de cuentas (proyección de accounts.lifecycle)")
    public ProfileResponse profile(@PathVariable UUID id) {
        return service.profile(id);
    }
}
