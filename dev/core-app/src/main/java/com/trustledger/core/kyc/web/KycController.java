package com.trustledger.core.kyc.web;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.kyc.application.KycCaseView;
import com.trustledger.core.kyc.application.KycOverview;
import com.trustledger.core.kyc.application.KycQueryService;
import com.trustledger.core.kyc.application.MinKycCommand;
import com.trustledger.core.kyc.application.MinKycService;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.shared.masking.Masking;
import com.trustledger.core.shared.validation.ValidIndianMobile;
import com.trustledger.core.shared.validation.ValidPan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer KYC endpoints (SPEC §5, CUSTOMER only). The user always comes from the token, never from the
 * request, so there is no ID to tamper with (no BOLA).
 */
@RestController
public class KycController {

    /**
     * {@code {"fullName":"Test Customer","dateOfBirth":"1995-04-15","mobile":"9876543210","pan":"ABCPE1234F"}}.
     * PAN in upper case; mobile as 10 digits. {@code toString()} is masked.
     */
    public record MinKycRequest(
            @NotBlank @Size(max = KycCase.MAX_NAME_LENGTH) String fullName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotBlank @ValidIndianMobile String mobile,
            @NotBlank @ValidPan String pan) {

        @Override
        public String toString() {
            return "MinKycRequest[fullName=" + Masking.name(fullName) + ", dateOfBirth=****, mobile="
                    + Masking.phone(mobile) + ", pan=" + Masking.pan(pan) + "]";
        }
    }

    private final IdentityApi identity;
    private final MinKycService minKyc;
    private final KycQueryService queries;

    public KycController(IdentityApi identity, MinKycService minKyc, KycQueryService queries) {
        this.identity = identity;
        this.minKyc = minKyc;
        this.queries = queries;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping(path = "/kyc/min", version = "1")
    @ResponseStatus(HttpStatus.CREATED)
    KycCaseView submitMin(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MinKycRequest request) {
        return minKyc.submit(new MinKycCommand(userId(jwt), request.fullName(), request.dateOfBirth(),
                request.mobile(), request.pan()));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping(path = "/kyc/me", version = "1")
    KycOverview me(@AuthenticationPrincipal Jwt jwt) {
        return queries.overview(userId(jwt));
    }

    /** The JIT filter has provisioned every authenticated caller before this runs. */
    private UUID userId(Jwt jwt) {
        return identity.findUserId(jwt.getSubject())
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "User profile not found."));
    }
}
