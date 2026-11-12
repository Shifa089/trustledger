package com.trustledger.core.shared.crypto;

import java.util.Arrays;
import java.util.Base64;

/** Fixed fake keys for unit tests. */
final class TestKeys {

    static final byte[] KEY_A = filled(1);
    static final byte[] KEY_B = filled(2);
    static final String KEY_A_B64 = Base64.getEncoder().encodeToString(KEY_A);
    static final String KEY_B_B64 = Base64.getEncoder().encodeToString(KEY_B);

    private TestKeys() {
    }

    static byte[] filled(int value) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) value);
        return key;
    }
}
