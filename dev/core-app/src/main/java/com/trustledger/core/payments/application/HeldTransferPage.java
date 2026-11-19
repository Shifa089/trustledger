package com.trustledger.core.payments.application;

import java.util.List;

/** A page of the transfer review queue, oldest first. An explicit DTO, not Spring Data's {@code Page}. */
public record HeldTransferPage(List<HeldTransferView> items, int page, int size, long totalElements,
        int totalPages) {
}
