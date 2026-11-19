package com.trustledger.core.payments;

import com.trustledger.core.payments.web.Idempotent;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only {@link Idempotent} endpoint ({@code POST /api/v1/test/idempotency-probe}), so the interceptor can be
 * tested before the real {@code POST /transfers} exists (M6 part 2). It counts how often it really ran per
 * {@code ref}, can be held on a gate to simulate a slow request, and can fail on demand.
 * <p>
 * Lives in test sources only; component scanning picks it up in every IT context, which is harmless.
 */
@RestController
class IdempotencyProbeController {

    record ProbeRequest(String ref, String amount, String outcome) {
    }

    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> entered = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();

    @Idempotent
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping(path = "/test/idempotency-probe", version = "1")
    ResponseEntity<Map<String, Object>> probe(@RequestBody ProbeRequest request) throws InterruptedException {
        int call = calls.computeIfAbsent(request.ref(), ref -> new AtomicInteger()).incrementAndGet();
        CountDownLatch gate = gates.get(request.ref());
        if (gate != null) {
            entered.get(request.ref()).countDown();
            if (!gate.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("gate never opened");
            }
        }
        if ("REJECT".equals(request.outcome())) {
            throw new TrustLedgerException(ErrorCode.INSUFFICIENT_FUNDS, "Available balance is lower than the amount.");
        }
        if ("CRASH".equals(request.outcome())) {
            throw new IllegalStateException("simulated failure");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("ref", request.ref(), "call", call, "transferId", UUID.randomUUID().toString()));
    }

    int calls(String ref) {
        AtomicInteger count = calls.get(ref);
        return count == null ? 0 : count.get();
    }

    /** Requests for {@code ref} will wait in the handler until {@link #open} is called. */
    CountDownLatch closeGate(String ref) {
        CountDownLatch in = new CountDownLatch(1);
        entered.put(ref, in);
        gates.put(ref, new CountDownLatch(1));
        return in;
    }

    void open(String ref) {
        gates.remove(ref).countDown();
    }
}
