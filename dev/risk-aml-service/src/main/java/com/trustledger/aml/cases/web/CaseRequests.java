package com.trustledger.aml.cases.web;

import com.trustledger.aml.cases.domain.AmlCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request bodies of the case workflow (FR-AML-4). Notes may quote personal data: never logged or echoed. */
final class CaseRequests {

    private CaseRequests() {
    }

    /** {@code assigneeId} empty = assign to the caller. */
    record AssignCaseRequest(UUID assigneeId) {
    }

    /** The only way to close a case is as a false positive; a real finding is escalated instead. */
    enum CloseResolution {
        FALSE_POSITIVE
    }

    record CloseCaseRequest(
            @NotNull CloseResolution resolution,
            @NotBlank @Size(max = AmlCase.MAX_NOTES_LENGTH) String notes) {
    }

    record EscalateCaseRequest(@NotBlank @Size(max = AmlCase.MAX_NOTES_LENGTH) String notes) {
    }
}
