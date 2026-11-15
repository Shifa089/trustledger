package com.trustledger.core.kyc.web;

import com.trustledger.core.kyc.application.FullKycCaseView;
import com.trustledger.core.kyc.application.FullKycService;
import com.trustledger.core.kyc.application.KycCaseView;
import com.trustledger.core.kyc.application.KycDocumentService;
import com.trustledger.core.kyc.application.KycDocumentView;
import com.trustledger.core.kyc.application.KycOverview;
import com.trustledger.core.kyc.application.KycQueryService;
import com.trustledger.core.kyc.application.MinKycCommand;
import com.trustledger.core.kyc.application.MinKycService;
import com.trustledger.core.kyc.domain.DocumentType;
import com.trustledger.core.kyc.domain.KycCase;
import com.trustledger.core.shared.masking.Masking;
import com.trustledger.core.shared.validation.ValidIndianMobile;
import com.trustledger.core.shared.validation.ValidPan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Customer KYC endpoints (SPEC §5, CUSTOMER only). The user always comes from the token, never from the
 * request; a case ID in the path is checked against that user (another user's case is a 404).
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

    /** {@code {"address":"12 Test Street, Bengaluru 560001"}}. {@code toString()} is masked. */
    public record FullKycRequest(@NotBlank @Size(max = KycCase.MAX_ADDRESS_LENGTH) String address) {

        @Override
        public String toString() {
            return "FullKycRequest[address=" + Masking.FULLY_MASKED + "]";
        }
    }

    private final Callers callers;
    private final MinKycService minKyc;
    private final FullKycService fullKyc;
    private final KycDocumentService documents;
    private final KycQueryService queries;

    public KycController(Callers callers, MinKycService minKyc, FullKycService fullKyc,
            KycDocumentService documents, KycQueryService queries) {
        this.callers = callers;
        this.minKyc = minKyc;
        this.fullKyc = fullKyc;
        this.documents = documents;
        this.queries = queries;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping(path = "/kyc/min", version = "1")
    @ResponseStatus(HttpStatus.CREATED)
    KycCaseView submitMin(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MinKycRequest request) {
        return minKyc.submit(new MinKycCommand(callers.userId(jwt), request.fullName(), request.dateOfBirth(),
                request.mobile(), request.pan()));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping(path = "/kyc/full", version = "1")
    @ResponseStatus(HttpStatus.CREATED)
    FullKycCaseView submitFull(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FullKycRequest request) {
        return fullKyc.submit(callers.userId(jwt), request.address());
    }

    /**
     * Multipart: part {@code file} (PDF, JPEG or PNG, at most 5 MB) and field {@code type}
     * ({@link DocumentType}). The file name and the part's Content-Type are ignored.
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping(path = "/kyc/cases/{id}/documents", version = "1", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    KycDocumentView uploadDocument(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam DocumentType type, @RequestPart("file") MultipartFile file) throws IOException {
        return documents.upload(callers.userId(jwt), id, type, file.getBytes());
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping(path = "/kyc/me", version = "1")
    KycOverview me(@AuthenticationPrincipal Jwt jwt) {
        return queries.overview(callers.userId(jwt));
    }
}
