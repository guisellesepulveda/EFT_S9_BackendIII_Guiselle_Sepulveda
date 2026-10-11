package cl.bancoxyz.bffweb;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** customers-service, accounts-service y payments-service simulados en un único servidor HTTP. */
final class FakeBackends {

    record Cust(UUID id, String username, String status) {
    }

    record Acc(UUID id, UUID customerId, String number, String type, String status, String balance) {
    }

    final MockWebServer server = new MockWebServer();
    final Map<String, Cust> customersByUser = new ConcurrentHashMap<>();
    final Map<UUID, Acc> accounts = new ConcurrentHashMap<>();
    final List<RecordedRequest> seen = new CopyOnWriteArrayList<>();
    final List<String> transferBodies = new CopyOnWriteArrayList<>();
    final Map<UUID, UUID> paymentFrom = new ConcurrentHashMap<>();
    final AtomicInteger customerLookups = new AtomicInteger();

    volatile int delayMs = 0;
    volatile boolean movementsFail = false;
    volatile boolean profileFail = false;
    volatile boolean accountsFail = false;
    volatile boolean paymentsFail = false;

    void start() throws IOException {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                seen.add(r);
                String path = r.getPath();
                MockResponse res;
                if (path.startsWith("/customers/by-username/")) {
                    customerLookups.incrementAndGet();
                    Cust c = customersByUser.get(path.substring("/customers/by-username/".length()));
                    res = c == null ? json(404, "{\"code\":\"NOT_FOUND\"}") : json(200, customer(c));
                } else if (path.matches("/customers/[^/]+/profile")) {
                    res = profileFail ? new MockResponse().setResponseCode(503)
                            : json(200, "{\"customerId\":\"" + path.split("/")[2] + "\",\"fullName\":\"Camila Rojas\",\"accountsOpen\":2,\"accountsClosed\":1}");
                } else if (path.startsWith("/accounts?customerId=")) {
                    UUID cid = UUID.fromString(path.substring("/accounts?customerId=".length()));
                    res = accountsFail ? new MockResponse().setResponseCode(503)
                            : json(200, "[" + String.join(",", accounts.values().stream().filter(a -> a.customerId().equals(cid)).sorted(java.util.Comparator.comparing(Acc::number)).map(FakeBackends::account).toList()) + "]");
                } else if (path.matches("/accounts/[^/]+/movements.*")) {
                    res = movementsFail ? new MockResponse().setResponseCode(503) : json(200, movements());
                } else if (path.matches("/accounts/[^/]+")) {
                    Acc a = accounts.get(UUID.fromString(path.substring("/accounts/".length())));
                    res = a == null ? json(404, "{\"code\":\"NOT_FOUND\"}") : json(200, account(a));
                } else if (path.equals("/payments/transfer")) {
                    String body = r.getBody().readUtf8();
                    transferBodies.add(body);
                    UUID id = UUID.randomUUID();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"fromAccountId\":\"([^\"]+)\"").matcher(body);
                    if (m.find()) {
                        paymentFrom.put(id, UUID.fromString(m.group(1)));
                    }
                    res = paymentsFail ? new MockResponse().setResponseCode(503) : json(202, payment(id, "PENDING"));
                } else if (path.startsWith("/payments/")) {
                    res = json(200, payment(UUID.fromString(path.substring("/payments/".length())), "COMPLETED"));
                } else {
                    res = new MockResponse().setResponseCode(404);
                }
                return delayMs > 0 ? res.setBodyDelay(delayMs, TimeUnit.MILLISECONDS) : res;
            }
        });
        server.start();
    }

    String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    void reset() {
        delayMs = 0;
        movementsFail = false;
        profileFail = false;
        accountsFail = false;
        paymentsFail = false;
        seen.clear();
        transferBodies.clear();
        customerLookups.set(0);
        paymentFrom.clear();
        customersByUser.clear();
        accounts.clear();
    }

    Cust customer(String username) {
        Cust c = new Cust(UUID.randomUUID(), username, "ACTIVE");
        customersByUser.put(username, c);
        return c;
    }

    Acc account(UUID customerId, String number, String status, String balance) {
        Acc a = new Acc(UUID.randomUUID(), customerId, number, "CHECKING", status, balance);
        accounts.put(a.id(), a);
        return a;
    }

    private static String customer(Cust c) {
        return "{\"id\":\"" + c.id() + "\",\"documentNumber\":\"12345678-5\",\"firstName\":\"Camila\",\"lastName\":\"Rojas\","
                + "\"email\":\"camila@example.com\",\"phone\":\"+56912345678\",\"username\":\"" + c.username() + "\",\"status\":\"" + c.status() + "\"}";
    }

    private static String account(Acc a) {
        return "{\"id\":\"" + a.id() + "\",\"customerId\":\"" + a.customerId() + "\",\"number\":\"" + a.number() + "\",\"type\":\"" + a.type()
                + "\",\"currency\":\"CLP\",\"balance\":" + new BigDecimal(a.balance()).toPlainString() + ",\"status\":\"" + a.status()
                + "\",\"openedAt\":\"2026-01-01T00:00:00Z\",\"closedAt\":null}";
    }

    private static String movements() {
        return "[{\"operation_id\":\"p1:CREDIT\",\"op_type\":\"CREDIT\",\"amount\":10000.00,\"balance_after\":10000.00,\"reference\":\"" + UUID.randomUUID()
                + "\",\"movement_status\":\"CONFIRMED\",\"description\":\"Depósito\",\"created_at\":\"2026-10-10T12:00:00+00:00\"},"
                + "{\"operation_id\":\"p2:DEBIT\",\"op_type\":\"DEBIT\",\"amount\":2500.50,\"balance_after\":7499.50,\"reference\":\"" + UUID.randomUUID()
                + "\",\"movement_status\":\"PENDING\",\"description\":\"Transferencia\",\"created_at\":\"2026-10-11T12:00:00+00:00\"}]";
    }

    private String payment(UUID id, String status) {
        return "{\"paymentId\":\"" + id + "\",\"type\":\"TRANSFER\",\"status\":\"" + status + "\",\"fromAccountId\":\"" + paymentFrom.getOrDefault(id, UUID.randomUUID())
                + "\",\"toAccountId\":\"" + UUID.randomUUID() + "\",\"amount\":2500.50,\"currency\":\"CLP\",\"degraded\":false}";
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }
}
