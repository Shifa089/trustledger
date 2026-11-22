package com.trustledger.core.privacy.web;

import com.trustledger.core.privacy.application.DataExportService;
import com.trustledger.core.privacy.application.DataRequestQueryService;
import com.trustledger.core.privacy.application.DataRequestView;
import com.trustledger.core.privacy.application.ErasureService;
import java.net.URI;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-PR-2 for CUSTOMERs: {@code POST /me/data-export}, {@code POST /me/erasure-request} and
 * {@code GET /me/data-requests}. Always the caller's own data: the user comes from the token, never from the
 * request, so there is no ID to tamper with (no BOLA).
 */
@RestController
public class PrivacyController {

    /** {@code {"items":[{"id":..,"type":"EXPORT","status":"COMPLETED",..,"download":{"url":..,"expiresAt":..}}]}} */
    public record DataRequestsResponse(List<DataRequestView> items) {
    }

    private final Callers callers;
    private final DataExportService exports;
    private final DataRequestQueryService queries;
    private final ErasureService erasures;

    PrivacyController(Callers callers, DataExportService exports, DataRequestQueryService queries,
            ErasureService erasures) {
        this.callers = callers;
        this.exports = exports;
        this.queries = queries;
        this.erasures = erasures;
    }

    /**
     * 202 Accepted: the export is prepared in the background. The {@code Location} header points at the list where
     * its status, and once COMPLETED its download link, appear. 409 REQUEST_IN_PROGRESS while one is still PENDING.
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping(path = "/me/data-export", version = "1")
    ResponseEntity<DataRequestView> requestExport(@AuthenticationPrincipal Jwt jwt) {
        DataRequestView request = exports.requestExport(callers.userId(jwt));
        return ResponseEntity.accepted().location(URI.create("/api/v1/me/data-requests")).body(request);
    }

    /**
     * 200 OK with the COMPLETED erasure request: erasure runs synchronously, so when this returns the personal data is
     * gone, the wallet is closed and the ledger, transfers and audit trail are untouched. 409 AML_CASE_OPEN while an
     * AML case on the wallet is open; 409 WALLET_NOT_EMPTY while money is on the wallet or on its way; 409
     * WALLET_FROZEN for a frozen wallet; 409 INVALID_STATE_TRANSITION once erased. Nothing changes on a refusal.
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping(path = "/me/erasure-request", version = "1")
    ResponseEntity<DataRequestView> requestErasure(@AuthenticationPrincipal Jwt jwt) {
        DataRequestView erasure = erasures.requestErasure(callers.userId(jwt));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(erasure);
    }

    /** {@code no-store}: the response carries download links that open a file of personal data. */
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping(path = "/me/data-requests", version = "1")
    ResponseEntity<DataRequestsResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new DataRequestsResponse(queries.list(callers.userId(jwt))));
    }
}
