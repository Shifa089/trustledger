package com.trustledger.core.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    @Test
    void sortsKeysAtEveryDepthWithoutWhitespace() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("z", 1);
        inner.put("a", true);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("b", inner);
        outer.put("a", List.of("x", 2L));

        assertThat(CanonicalJson.write(outer)).isEqualTo("{\"a\":[\"x\",2],\"b\":{\"a\":true,\"z\":1}}");
    }

    @Test
    void insertionOrderDoesNotMatter() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("status", "FROZEN");
        one.put("freezeSource", "AML");
        Map<String, Object> two = new HashMap<>();
        two.put("freezeSource", "AML");
        two.put("status", "FROZEN");

        assertThat(CanonicalJson.write(one)).isEqualTo(CanonicalJson.write(two));
    }

    @Test
    void writesNullsExplicitly() {
        Map<String, Object> map = new HashMap<>();
        map.put("ip", null);

        assertThat(CanonicalJson.write(map)).isEqualTo("{\"ip\":null}");
        assertThat(CanonicalJson.write(null)).isEqualTo("null");
        assertThat(CanonicalJson.write(Arrays.asList(1, null))).isEqualTo("[1,null]");
    }

    @Test
    void integersOfEveryWidthLookTheSame() {
        // jsonb hands back an Integer where a Long was written; the canonical form must not differ.
        assertThat(CanonicalJson.write(Map.of("n", 7))).isEqualTo(CanonicalJson.write(Map.of("n", 7L)));
        assertThat(CanonicalJson.write(Long.MAX_VALUE)).isEqualTo("9223372036854775807");
    }

    @Test
    void escapesQuotesBackslashesAndControlCharacters() {
        assertThat(CanonicalJson.write("a\"b\\c\nd\te\u0001")).isEqualTo("\"a\\\"b\\\\c\\nd\\te\\u0001\"");
    }

    @Test
    void keepsNonAsciiAsIs() {
        assertThat(CanonicalJson.write("₹ नमस्ते")).isEqualTo("\"₹ नमस्ते\"");
    }

    @Test
    void refusesFractionalNumbers() {
        assertThatThrownBy(() -> CanonicalJson.write(Map.of("amount", 1.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CanonicalJson.write(new BigDecimal("1.50")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesNonStringKeys() {
        assertThatThrownBy(() -> CanonicalJson.write(Map.of(1, "x"))).isInstanceOf(IllegalArgumentException.class);
    }
}
