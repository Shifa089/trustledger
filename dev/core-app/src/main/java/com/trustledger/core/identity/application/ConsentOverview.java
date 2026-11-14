package com.trustledger.core.identity.application;

import com.trustledger.core.identity.domain.Consent;
import com.trustledger.core.identity.domain.ConsentPurpose;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The user's current consent state.
 *
 * @param requiredGiven  TERMS and PRIVACY granted for their current policy versions
 * @param consents       latest decision per purpose (purposes never answered are absent)
 * @param policyVersions currently published versions, so a client knows what to show and send back
 */
public record ConsentOverview(boolean requiredGiven, List<Item> consents, Map<ConsentPurpose, String> policyVersions) {

    public record Item(ConsentPurpose purpose, boolean granted, String policyVersion, Instant capturedAt) {

        static Item of(Consent consent) {
            return new Item(consent.getPurpose(), consent.isGranted(), consent.getPolicyVersion(),
                    consent.getCapturedAt());
        }
    }

    static List<Item> items(List<Consent> latest) {
        return latest.stream()
                .sorted(Comparator.comparing(Consent::getPurpose))
                .map(Item::of)
                .toList();
    }
}
