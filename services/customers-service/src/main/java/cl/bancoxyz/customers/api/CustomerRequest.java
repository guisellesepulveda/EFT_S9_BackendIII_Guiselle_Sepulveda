package cl.bancoxyz.customers.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomerRequest(
        @NotBlank @Pattern(regexp = "^[0-9]{7,8}-[0-9Kk]$", message = "debe tener formato RUT sin puntos, p. ej. 12345678-5")
        String documentNumber,
        @NotBlank @Size(max = 80) String firstName,
        @NotBlank @Size(max = 80) String lastName,
        @NotBlank @Email @Size(max = 160) String email,
        @Pattern(regexp = "^\\+?[0-9]{8,15}$", message = "debe tener entre 8 y 15 dígitos, con + opcional") String phone,
        @Pattern(regexp = "^[a-z0-9._-]{3,60}$", message = "solo minúsculas, dígitos, punto, guion y guion bajo (3 a 60)") String username) {
}
