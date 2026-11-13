package com.trustledger.core.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> mdcInsideChain = new AtomicReference<>();
    private final FilterChain capturingChain = (req, res) -> mdcInsideChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void acceptsValidIncomingId() throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");

        filter.doFilter(request, response, capturingChain);

        assertThat(mdcInsideChain.get()).isEqualTo("abc-123");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123");
    }

    @Test
    void generatesIdWhenMissing() throws Exception {
        filter.doFilter(request, response, capturingChain);

        String generated = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(mdcInsideChain.get()).isEqualTo(generated);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc\r\nFAKE LOG LINE", "has space", "<script>",
            "0123456789012345678901234567890123456789012345678901234567890123456789"})
    void replacesInvalidIdWithGeneratedOne(String invalid) throws Exception {
        request.addHeader(CorrelationIdFilter.HEADER, invalid);

        filter.doFilter(request, response, capturingChain);

        String used = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(used).isNotEqualTo(invalid);
        assertThat(UUID.fromString(used)).isNotNull();
        assertThat(mdcInsideChain.get()).isEqualTo(used);
    }

    @Test
    void clearsMdcAfterRequest() throws Exception {
        filter.doFilter(request, response, capturingChain);

        assertThat(mdcInsideChain.get()).isNotNull();
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void clearsMdcEvenWhenChainThrows() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing)).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotBlank();
    }
}
