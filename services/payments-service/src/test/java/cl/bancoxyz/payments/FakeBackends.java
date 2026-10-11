package cl.bancoxyz.payments;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Un único servidor HTTP que simula auth-server (/oauth2/token), accounts-service (GET /accounts/{id},
 * POST /accounts/{id}/credit) y customers-service (GET /customers/{id}), con modos de fallo configurables.
 */
final class FakeBackends {

    record Acc(UUID id, UUID customerId, String status, BigDecimal balance) {
    }

    final MockWebServer server = new MockWebServer();
    final Map<UUID, Acc> accounts = new ConcurrentHashMap<>();
    final Map<UUID, String> customers = new ConcurrentHashMap<>(); // id → ACTIVE | INACTIVE
    final List<String> creditBodies = new CopyOnWriteArrayList<>();
    final List<String> creditAuth = new CopyOnWriteArrayList<>();
    final AtomicInteger accountCalls = new AtomicInteger();
    final AtomicInteger customerCalls = new AtomicInteger();
    final AtomicInteger creditCalls = new AtomicInteger();

    /** ok | error | slow */
    volatile String accountsMode = "ok";
    volatile String customersMode = "ok";
    /** ok | error (503) | rejected (422) | failFirst:N */
    volatile String creditMode = "ok";
    volatile int accountsDelayMs = 0;

    void start() throws IOException {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest r) {
                String path = r.getPath();
                if (path.startsWith("/oauth2/token")) {
                    return json(200, "{\"access_token\":\"internal-token-123\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"internal\"}");
                }
                if (path.startsWith("/customers/")) {
                    customerCalls.incrementAndGet();
                    UUID id = UUID.fromString(path.substring("/customers/".length()));
                    return switch (customersMode) {
                        case "error" -> new MockResponse().setResponseCode(503);
                        case "slow" -> json(200, "{}").setBodyDelay(5, TimeUnit.SECONDS);
                        default -> customers.containsKey(id)
                                ? json(200, "{\"id\":\"" + id + "\",\"status\":\"" + customers.get(id) + "\"}") : json(404, "{}");
                    };
                }
                if (path.matches("/accounts/[^/]+/credit")) {
                    int n = creditCalls.incrementAndGet();
                    creditBodies.add(r.getBody().readUtf8());
                    creditAuth.add(String.valueOf(r.getHeader("Authorization")));
                    if (creditMode.startsWith("failFirst:") && n <= Integer.parseInt(creditMode.substring("failFirst:".length()))) {
                        return new MockResponse().setResponseCode(503);
                    }
                    return switch (creditMode) {
                        case "error" -> new MockResponse().setResponseCode(503);
                        case "rejected" -> json(422, "{\"code\":\"ACCOUNT_CLOSED\"}");
                        default -> json(200, "{\"applied\":true}");
                    };
                }
                if (path.startsWith("/accounts/")) {
                    accountCalls.incrementAndGet();
                    UUID id = UUID.fromString(path.substring("/accounts/".length()));
                    MockResponse res = switch (accountsMode) {
                        case "error" -> new MockResponse().setResponseCode(503);
                        default -> {
                            Acc a = accounts.get(id);
                            yield a == null ? json(404, "{}") : json(200, "{\"id\":\"" + a.id() + "\",\"customerId\":\""
                                    + a.customerId() + "\",\"status\":\"" + a.status() + "\",\"balance\":" + a.balance().toPlainString() + "}");
                        }
                    };
                    return accountsDelayMs > 0 ? res.setBodyDelay(accountsDelayMs, TimeUnit.MILLISECONDS) : res;
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
    }

    void shutdown() throws IOException {
        server.shutdown();
    }

    String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    String tokenUrl() {
        return server.url("/oauth2/token").toString();
    }

    void reset() {
        accountsMode = "ok";
        customersMode = "ok";
        creditMode = "ok";
        accountsDelayMs = 0;
        accountCalls.set(0);
        customerCalls.set(0);
        creditCalls.set(0);
        creditBodies.clear();
        creditAuth.clear();
    }

    /** Registra un cliente y una cuenta abierta con el saldo dado; devuelve el id de la cuenta. */
    UUID account(String balance) {
        UUID customer = UUID.randomUUID();
        customers.put(customer, "ACTIVE");
        UUID acc = UUID.randomUUID();
        accounts.put(acc, new Acc(acc, customer, "OPEN", new BigDecimal(balance)));
        return acc;
    }

    UUID account(String balance, String status, String customerStatus) {
        UUID customer = UUID.randomUUID();
        customers.put(customer, customerStatus);
        UUID acc = UUID.randomUUID();
        accounts.put(acc, new Acc(acc, customer, status, new BigDecimal(balance)));
        return acc;
    }

    UUID customerOf(UUID account) {
        return accounts.get(account).customerId();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body);
    }
}
