package com.trustledger.core.payments.web;

import com.trustledger.core.payments.application.TopupQueryService;
import com.trustledger.core.payments.application.TopupResult;
import com.trustledger.core.payments.application.TopupService;
import com.trustledger.core.payments.domain.TopupStatus;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.validation.ValidAmount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Top-ups (SPEC §5, FR-P-4, CUSTOMER only). {@code POST} needs an {@code Idempotency-Key} header ({@link Idempotent})
 * and returns 201 with the gateway order ID and the checkout key ID; the browser then opens Razorpay Checkout. The
 * wallet is credited later, when the {@code payment.captured} webhook arrives (M7 part 3). {@code GET} shows a
 * top-up to its owner only; anyone else gets 404.
 */
@RestController
public class TopupController {

    /** {@code {"amount":"500.00"}}: INR, decimal string, at most 2 decimals. */
    public record TopupRequest(@NotBlank @ValidAmount String amount) {
    }

    /** Amounts as decimal strings (SPEC §6). */
    public record TopupResponse(UUID id, TopupStatus status, String amount, String currency, String gatewayOrderId,
            String gatewayPaymentId, String keyId, Instant createdAt, Instant updatedAt) {

        static TopupResponse of(TopupResult result) {
            return new TopupResponse(result.topupId(), result.status(), result.amount().toDecimalString(),
                    result.amount().currency().getCurrencyCode(), result.gatewayOrderId(),
                    result.gatewayPaymentId(), result.keyId(), result.createdAt(), result.updatedAt());
        }
    }

    private final Callers callers;
    private final TopupService topups;
    private final TopupQueryService queries;

    TopupController(Callers callers, TopupService topups, TopupQueryService queries) {
        this.callers = callers;
        this.topups = topups;
        this.queries = queries;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Idempotent
    @PostMapping(path = "/topups", version = "1")
    @ResponseStatus(HttpStatus.CREATED)
    TopupResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TopupRequest request) {
        return TopupResponse.of(topups.create(callers.userId(jwt), Money.parse(request.amount())));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping(path = "/topups/{id}", version = "1")
    TopupResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TopupResponse.of(queries.get(callers.userId(jwt), id));
    }
}
