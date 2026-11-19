package com.trustledger.core.payments.web;

import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferQueryService;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferResult;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.payments.domain.Transfer;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.masking.Masking;
import com.trustledger.core.shared.validation.ValidAmount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * P2P transfers (SPEC §5, CUSTOMER only). {@code POST} needs an {@code Idempotency-Key} header
 * ({@link Idempotent}); the sender is always the caller's own wallet. {@code GET} shows a transfer to its sender
 * or receiver only; anyone else gets 404.
 * <p>
 * Both a completed and a held transfer are 201: the transfer exists either way, and {@code status} says which.
 * There is no {@code Location} header, because a replayed response stores only status and body.
 */
@RestController
public class TransferController {

    /**
     * {@code {"recipientMobile":"9876543210","amount":"250.00","note":"Dinner"}}, or {@code recipientWalletId}
     * instead of the mobile. Exactly one of the two (checked by {@code RecipientResolver}). {@code toString()}
     * is masked.
     */
    public record TransferRequest(
            @Size(max = 20) String recipientMobile,
            UUID recipientWalletId,
            @NotBlank @ValidAmount String amount,
            @Size(max = Transfer.MAX_NOTE_LENGTH) String note) {

        @Override
        public String toString() {
            return "TransferRequest[recipientMobile=" + Masking.phone(recipientMobile) + ", recipientWalletId="
                    + recipientWalletId + ", amount=" + amount + ", note=" + Masking.FULLY_MASKED + "]";
        }
    }

    /** Amounts as decimal strings (SPEC §6). */
    public record TransferResponse(UUID id, TransferStatus status, UUID senderWalletId, UUID receiverWalletId,
            String amount, String currency, String note, Instant createdAt) {

        static TransferResponse of(TransferResult result) {
            return new TransferResponse(result.transferId(), result.status(), result.senderWalletId(),
                    result.receiverWalletId(), result.amount().toDecimalString(),
                    result.amount().currency().getCurrencyCode(), result.note(), result.createdAt());
        }
    }

    private final Callers callers;
    private final TransferService transfers;
    private final TransferQueryService queries;

    public TransferController(Callers callers, TransferService transfers, TransferQueryService queries) {
        this.callers = callers;
        this.transfers = transfers;
        this.queries = queries;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Idempotent
    @PostMapping(path = "/transfers", version = "1")
    @ResponseStatus(HttpStatus.CREATED)
    TransferResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TransferRequest request) {
        TransferCommand command = new TransferCommand(callers.userId(jwt),
                new TransferRecipient(request.recipientMobile(), request.recipientWalletId()),
                Money.parse(request.amount()), request.note());
        return TransferResponse.of(transfers.transfer(command));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping(path = "/transfers/{id}", version = "1")
    TransferResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TransferResponse.of(queries.get(callers.userId(jwt), id));
    }
}
