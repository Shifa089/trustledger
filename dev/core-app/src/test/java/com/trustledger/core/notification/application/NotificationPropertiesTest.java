package com.trustledger.core.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class NotificationPropertiesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void acceptsTheShippedValues() {
        NotificationProperties properties = new NotificationProperties(" no-reply@trustledger.local ", IST);

        assertThat(properties.from()).isEqualTo("no-reply@trustledger.local");
        assertThat(properties.zone()).isEqualTo(IST);
    }

    @Test
    void rejectsAMissingOrMalformedSender() {
        for (String from : new String[] {null, "", "  ", "no-reply", "@trustledger.local", "a b@x.test"}) {
            assertThatThrownBy(() -> new NotificationProperties(from, IST)).as(String.valueOf(from))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsAMissingZone() {
        assertThatThrownBy(() -> new NotificationProperties("no-reply@trustledger.local", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
