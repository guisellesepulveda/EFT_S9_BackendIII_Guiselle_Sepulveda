package cl.bancoxyz.payments.clients;

import java.math.BigDecimal;

public record CreditRequest(String operationId, BigDecimal amount, String reference, String description) {
}
