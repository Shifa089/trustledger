package com.trustledger.core.audit.application;

import java.util.List;

public record AuditEventPage(List<AuditEventView> items, int page, int size, long totalElements, int totalPages) {

    public AuditEventPage {
        items = List.copyOf(items);
    }
}
