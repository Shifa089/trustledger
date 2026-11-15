package com.trustledger.core.kyc.application;

import java.util.List;

/** A page of the compliance queue, oldest case first. An explicit DTO, not Spring Data's {@code Page}. */
public record KycQueuePage(List<KycQueueItem> items, int page, int size, long totalElements, int totalPages) {
}
