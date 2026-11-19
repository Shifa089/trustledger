package com.trustledger.core.payments.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.trustledger.core.payments.domain.GatewayOrder;
import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.payments.domain.GatewayRejectedException;
import com.trustledger.core.payments.domain.GatewayUnavailableException;
import com.trustledger.core.payments.domain.PaymentGateway;
import com.trustledger.core.shared.Money;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link PaymentGateway} on the Razorpay Orders and Payments REST API (test mode only), with Spring's
 * {@link RestClient}. Vendor exceptions never leave this class.
 * <p>
 * Every call runs through Resilience4j, outermost first: <b>Retry</b> (GETs only) → <b>CircuitBreaker</b> →
 * <b>TimeLimiter</b> → HTTP. So each retry attempt is one call the breaker sees, an open circuit stops retries at
 * once, and the time limit applies per attempt. The breaker records only {@link GatewayUnavailableException}:
 * a 4xx means the gateway is up and our request is wrong, so it must not open the circuit.
 * <p>
 * The HTTP call runs on a virtual thread so the TimeLimiter can give up waiting and cancel it; the socket read
 * timeout is the same value, so the abandoned request also stops on its own.
 */
class RazorpayGatewayAdapter implements PaymentGateway, AutoCloseable {

    static final String BREAKER_NAME = "razorpay";
    private static final Logger log = LoggerFactory.getLogger(RazorpayGatewayAdapter.class);
    private static final int MAX_RECEIPT_LENGTH = 40;
    private static final int MAX_ERROR_BODY_BYTES = 8 * 1024;
    private static final JsonMapper ERROR_READER = JsonMapper.builder().build();

    private final RestClient http;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final Retry retry;
    private final ExecutorService executor;

    RazorpayGatewayAdapter(RestClient http, CircuitBreaker circuitBreaker, TimeLimiter timeLimiter, Retry retry,
            ExecutorService executor) {
        this.http = http;
        this.circuitBreaker = circuitBreaker;
        this.timeLimiter = timeLimiter;
        this.retry = retry;
        this.executor = executor;
    }

    /** Builds the client and the resilience decorators from configuration. */
    static RazorpayGatewayAdapter create(RazorpayProperties properties) {
        HttpClient jdkClient = HttpClient.newBuilder()
                // HTTP/1.1: on plain http (WireMock, local stubs) the JDK client otherwise attempts an h2c upgrade,
                // which servers may cancel for requests with a body (RST_STREAM). Low call volume, no h2 benefit.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkClient);
        requestFactory.setReadTimeout(properties.timeout());

        RestClient http = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeaders(h -> h.setBasicAuth(properties.keyId(), properties.keySecret()))
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();

        RazorpayProperties.CircuitBreaker cb = properties.circuitBreaker();
        CircuitBreaker circuitBreaker = CircuitBreaker.of(BREAKER_NAME, CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.slidingWindowSize())
                .minimumNumberOfCalls(cb.minimumNumberOfCalls())
                .failureRateThreshold(cb.failureRateThreshold())
                .waitDurationInOpenState(cb.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(cb.permittedCallsInHalfOpen())
                .recordExceptions(GatewayUnavailableException.class)
                .ignoreExceptions(GatewayRejectedException.class)
                .build());

        TimeLimiter timeLimiter = TimeLimiter.of(BREAKER_NAME, TimeLimiterConfig.custom()
                .timeoutDuration(properties.timeout())
                .cancelRunningFuture(true)
                .build());

        RazorpayProperties.Retry r = properties.retry();
        Retry retry = Retry.of(BREAKER_NAME, RetryConfig.custom()
                .maxAttempts(r.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(r.initialBackoff(), r.multiplier()))
                // Only "the gateway is down or slow". Not a 4xx, and not an open circuit (fail fast).
                .retryExceptions(GatewayUnavailableException.class)
                .ignoreExceptions(GatewayRejectedException.class, CallNotPermittedException.class)
                .build());

        return new RazorpayGatewayAdapter(http, circuitBreaker, timeLimiter, retry,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public GatewayOrder createOrder(Money amount, String receipt) {
        Objects.requireNonNull(amount, "amount");
        if (!Money.INR.equals(amount.currency()) || amount.isZero()) {
            throw new IllegalArgumentException("Order amount must be a positive INR amount");
        }
        if (receipt == null || receipt.isBlank() || receipt.length() > MAX_RECEIPT_LENGTH) {
            throw new IllegalArgumentException("Receipt must be 1 to " + MAX_RECEIPT_LENGTH + " characters");
        }
        CreateOrderRequest body = new CreateOrderRequest(amount.amountMinor(), amount.currency().getCurrencyCode(),
                receipt);
        return execute("createOrder", false, () -> toOrder(http.post()
                .uri("/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, RazorpayGatewayAdapter::throwForStatus)
                .body(OrderResponse.class)));
    }

    @Override
    public GatewayPayment fetchPayment(String paymentId) {
        PaymentGateway.requirePaymentId(paymentId);
        return execute("fetchPayment", true, () -> toPayment(http.get()
                .uri("/v1/payments/{id}", paymentId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, RazorpayGatewayAdapter::throwForStatus)
                .body(PaymentResponse.class)));
    }

    @Override
    public List<GatewayPayment> fetchOrderPayments(String orderId) {
        PaymentGateway.requireOrderId(orderId);
        return execute("fetchOrderPayments", true, () -> {
            PaymentCollection collection = http.get()
                    .uri("/v1/orders/{id}/payments", orderId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, RazorpayGatewayAdapter::throwForStatus)
                    .body(PaymentCollection.class);
            if (collection == null || collection.items() == null) {
                throw new IllegalStateException("Payment collection without items");
            }
            return collection.items().stream().map(RazorpayGatewayAdapter::toPayment).toList();
        });
    }

    /** For tests and, later, the health indicator and metrics (M12). */
    CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    @Override
    public void close() {
        executor.close();
    }

    // ---- resilience pipeline ----

    private <T> T execute(String operation, boolean idempotent, Supplier<T> call) {
        Callable<T> guarded = CircuitBreaker.decorateCallable(circuitBreaker, () -> timeLimited(call));
        if (idempotent) {
            guarded = Retry.decorateCallable(retry, guarded);
        }
        try {
            return guarded.call();
        } catch (CallNotPermittedException e) {
            log.warn("Razorpay {} not attempted: circuit breaker is open", operation);
            throw new GatewayUnavailableException(e);
        } catch (GatewayRejectedException e) {
            log.info("Razorpay {} rejected with HTTP {} ({})", operation, e.httpStatus(), e.gatewayErrorCode());
            throw e;
        } catch (GatewayUnavailableException e) {
            log.warn("Razorpay {} failed: {}", operation, describe(e.getCause()));
            throw e;
        } catch (Exception e) {
            log.warn("Razorpay {} failed: {}", operation, describe(e));
            throw new GatewayUnavailableException(e);
        }
    }

    /**
     * One attempt under the time limit. Anything other than a 4xx rejection (timeout, I/O error, 5xx, a body we
     * can't read) becomes {@link GatewayUnavailableException} here, so the breaker records it as a failure.
     */
    private <T> T timeLimited(Supplier<T> call) {
        try {
            return timeLimiter.executeFutureSupplier(() -> executor.submit(call::get));
        } catch (GatewayRejectedException | GatewayUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayUnavailableException(e);
        } catch (Exception e) { // TimeoutException from the limiter, or anything thrown by the call itself
            throw new GatewayUnavailableException(e);
        }
    }

    /** Class name and message of the cause; never a response body (could contain customer details). */
    private static String describe(Throwable cause) {
        if (cause == null) {
            return "server error";
        }
        return cause.getClass().getSimpleName()
                + (cause instanceof GatewayUnavailableException ? "" : ": " + cause.getMessage());
    }

    private static void throwForStatus(HttpRequest request, ClientHttpResponse response)
            throws IOException {
        int status = response.getStatusCode().value();
        if (response.getStatusCode().is4xxClientError()) {
            throw new GatewayRejectedException(status, readErrorCode(response));
        }
        throw new GatewayUnavailableException(new IllegalStateException("HTTP " + status));
    }

    /** Razorpay errors look like {@code {"error": {"code": "BAD_REQUEST_ERROR", "description": ...}}}. */
    private static String readErrorCode(ClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            byte[] bytes = in.readNBytes(MAX_ERROR_BODY_BYTES);
            JsonNode code = ERROR_READER.readTree(bytes).path("error").path("code");
            return code.isString() ? code.asString() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---- mapping ----

    private static GatewayOrder toOrder(OrderResponse order) {
        Objects.requireNonNull(order, "order response");
        return new GatewayOrder(order.id(), money(order.amount(), order.currency()), order.receipt(), order.status());
    }

    private static GatewayPayment toPayment(PaymentResponse payment) {
        Objects.requireNonNull(payment, "payment response");
        return new GatewayPayment(payment.id(), payment.orderId(), money(payment.amount(), payment.currency()),
                GatewayPaymentStatus.fromGateway(payment.status()), payment.errorCode());
    }

    /** Razorpay sends amounts as integers in the smallest unit (paise), never as decimals. */
    private static Money money(Long amountMinor, String currency) {
        Objects.requireNonNull(amountMinor, "amount");
        Objects.requireNonNull(currency, "currency");
        return Money.of(amountMinor, Currency.getInstance(currency));
    }

    // ---- wire format (Razorpay JSON) ----

    record CreateOrderRequest(long amount, String currency, String receipt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderResponse(String id, Long amount, String currency, String receipt, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentResponse(String id, @JsonProperty("order_id") String orderId, Long amount, String currency,
            String status, @JsonProperty("error_code") String errorCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentCollection(List<PaymentResponse> items) {
    }
}
