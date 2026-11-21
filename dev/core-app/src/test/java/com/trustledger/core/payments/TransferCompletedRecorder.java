package com.trustledger.core.payments;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Test-only module listener for {@link TransferCompleted}, so {@code EventPublicationRegistryIT} can watch the outbox
 * before the Kafka externalizer (M8 part B) exists. It records every delivery and can be told to fail for transfers
 * from chosen sender wallets (a transfer's own ID isn't known before it is made).
 * <p>
 * Lives in test sources only; component scanning picks it up in every IT context. That is harmless: it never fails
 * unless a test asks it to, and it only adds a completed {@code event_publication} row per transfer, as the Kafka
 * externalizer will in production.
 */
@Component
class TransferCompletedRecorder {

    private final Map<UUID, List<TransferCompleted>> received = new ConcurrentHashMap<>();
    private final Set<UUID> failingSenders = ConcurrentHashMap.newKeySet();

    @ApplicationModuleListener
    public void on(TransferCompleted event) {
        received.computeIfAbsent(event.transferId(), id -> new CopyOnWriteArrayList<>()).add(event);
        if (failingSenders.contains(event.senderWalletId())) {
            throw new IllegalStateException("Simulated listener failure for transfer " + event.transferId());
        }
    }

    /** Every delivery for the transfer, in order (a resubmission adds one). */
    List<TransferCompleted> receivedFor(UUID transferId) {
        return received.getOrDefault(transferId, List.of());
    }

    void failForSender(UUID senderWalletId) {
        failingSenders.add(senderWalletId);
    }

    void recoverSender(UUID senderWalletId) {
        failingSenders.remove(senderWalletId);
    }
}
