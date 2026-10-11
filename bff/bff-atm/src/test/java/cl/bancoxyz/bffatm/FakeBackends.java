package cl.bancoxyz.bffatm;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** accounts-service y payments-service simulados. payments es idempotente por Idempotency-Key, como el real. */
final class FakeBackends {

    final MockWebServer server = new MockWebServer();
    final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();
    final List<String> withdrawKeys = new CopyOnWriteArrayList<>();
    final Map<String, UUID> paymentByKey = new ConcurrentHashMap<>();
    final AtomicInteger paymentCreates = new AtomicInteger();
    final Map<UUID, String> accountBalance = new ConcurrentHashMap<>();

    /** Estado que devuelve GET /payments/{id}: COMPLETED | PENDING | FAILED. */
    volatile String paymentStatus = "COMPLETED";
    /** ok | down (503) | insufficient (422) */
    volatile String withdrawMode = "ok";
    volatile boolean accountsDown = false;

    void start() throws IOException {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                seen.add(r);
                String path = r.getPath();
                if (path.equals("/payments/withdraw")) {
                    return switch (withdrawMode) {
                        case "down" -> new MockResponse().setResponseCode(503);
                        case "insufficient" -> json(422, "{\"type\":\"about:blank\",\"status\":422,\"code\":\"INSUFFICIENT_FUNDS\",\"detail\":\"Saldo insuficiente\"}");
                        default -> {
                            String key = r.getHeader("Idempotency-Key");
                            withdrawKeys.add(String.valueOf(key));
                            UUID id = paymentByKey.computeIfAbsent(String.valueOf(key), k -> {
                                paymentCreates.incrementAndGet();
                                return UUID.randomUUID();
                            });
                            yield json(202, payment(id, "PENDING"));
                        }
                    };
                }
                if (path.startsWith("/payments/")) {
                    return json(200, payment(UUID.fromString(path.substring("/payments/".length())), paymentStatus));
                }
                if (accountsDown && path.startsWith("/accounts")) {
                    return new MockResponse().setResponseCode(503);
                }
                if (path.matches("/accounts/[^/]+/movements.*")) {
                    return json(200, movements());
                }
                if (path.matches("/accounts/[^/]+")) {
                    UUID id = UUID.fromString(path.substring("/accounts/".length()));
                    return json(200, "{\"id\":\"" + id + "\",\"customerId\":\"" + UUID.randomUUID() + "\",\"number\":\"CL400000000001\",\"type\":\"CHECKING\","
                            + "\"currency\":\"CLP\",\"balance\":" + new BigDecimal(accountBalance.getOrDefault(id, "7499.50")).toPlainString()
                            + ",\"status\":\"OPEN\",\"openedAt\":\"2026-01-01T00:00:00Z\",\"closedAt\":null}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
    }

    String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    void reset() {
        paymentStatus = "COMPLETED";
        withdrawMode = "ok";
        accountsDown = false;
        seen.clear();
        withdrawKeys.clear();
        paymentByKey.clear();
        paymentCreates.set(0);
    }

    long calls(String pathPrefix) {
        return seen.stream().filter(r -> r.getPath().startsWith(pathPrefix)).count();
    }

    private static String movements() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= 8; i++) {                                // el backend devuelve más; el ATM debe recortar a 5
            sb.append(i > 1 ? "," : "").append("{\"operation_id\":\"p").append(i).append(":DEBIT\",\"op_type\":\"DEBIT\",\"amount\":").append(i * 100)
                    .append(".00,\"balance_after\":1000.00,\"reference\":\"").append(UUID.randomUUID())
                    .append("\",\"movement_status\":\"CONFIRMED\",\"description\":\"dato interno\",\"created_at\":\"2026-10-1")
                    .append(i % 10).append("T12:00:00+00:00\"}");
        }
        return sb.append("]").toString();
    }

    private static String payment(UUID id, String status) {
        return "{\"paymentId\":\"" + id + "\",\"type\":\"WITHDRAWAL\",\"status\":\"" + status + "\",\"fromAccountId\":\"" + UUID.randomUUID()
                + "\",\"amount\":20000,\"currency\":\"CLP\",\"degraded\":false"
                + ("FAILED".equals(status) ? ",\"failureCode\":\"INSUFFICIENT_FUNDS\"" : "") + "}";
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }
}
