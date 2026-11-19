package com.trustledger.core.payments.web;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.payments.application.IdempotencyClaim;
import com.trustledger.core.payments.application.IdempotencyService;
import com.trustledger.core.payments.application.RequestHasher;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

/**
 * Applies {@link Idempotent} (SPEC §4 "Transfer flow", steps 1 and 8).
 * <p>
 * {@link #preHandle}: validates the key, hashes the canonical body and claims the key. A replay is written here
 * and the controller is skipped. {@link #afterCompletion} runs after the controller's transaction has committed
 * (or rolled back): a 2xx response is stored for replay, anything else releases the key.
 * <p>
 * <b>Known gap (M6 part 2):</b> if the process dies after the business commit but before the response is stored,
 * the key stays IN_PROGRESS (409) until it expires. It never runs twice, which is the property that matters for
 * money.
 */
class IdempotencyInterceptor implements HandlerInterceptor {

    static final String HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String CLAIM_ATTRIBUTE = IdempotencyInterceptor.class.getName() + ".claim";

    private final IdempotencyService idempotency;
    private final RequestHasher hasher;
    private final IdentityApi identity;

    IdempotencyInterceptor(IdempotencyService idempotency, RequestHasher hasher, IdentityApi identity) {
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.identity = identity;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(Idempotent.class)) {
            return true;
        }
        UUID key = parseKey(request.getHeader(HEADER));
        CachedBodyRequest body = WebUtils.getNativeRequest(request, CachedBodyRequest.class);
        if (body == null || WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class) == null) {
            throw new IllegalStateException("IdempotencyBodyFilter must wrap requests to @Idempotent endpoints");
        }
        if (body.exceedsLimit()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "The request body is too large.");
        }
        String hash = hasher.hash(body.body());
        UUID userId = currentUserId();

        IdempotencyClaim claim = idempotency.claim(userId, key, hash);
        if (claim instanceof IdempotencyClaim.Replay replay) {
            writeReplay(response, replay);
            return false;
        }
        request.setAttribute(CLAIM_ATTRIBUTE, new ClaimedKey(userId, key));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        if (!(request.getAttribute(CLAIM_ATTRIBUTE) instanceof ClaimedKey claimed)) {
            return;
        }
        int status = response.getStatus();
        try {
            if (ex == null && status >= 200 && status <= 299) {
                ContentCachingResponseWrapper cached =
                        WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
                byte[] bytes = cached == null ? new byte[0] : cached.getContentAsByteArray();
                String body = bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
                if (!idempotency.complete(claimed.userId(), claimed.key(), status, body)) {
                    log.warn("Idempotency key was no longer IN_PROGRESS when its response was stored");
                }
            } else {
                idempotency.release(claimed.userId(), claimed.key());
            }
        } catch (RuntimeException e) {
            // The response has gone out; leaving the key IN_PROGRESS is the safe side (no second execution).
            log.error("Could not record the outcome of an idempotent request (status {})", status, e);
        }
    }

    private static UUID parseKey(String header) {
        if (header == null || header.isBlank()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "The Idempotency-Key header is required.");
        }
        String text = header.strip();
        if (!UUID_TEXT.matcher(text).matches()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "The Idempotency-Key must be a UUID.");
        }
        return UUID.fromString(text.toLowerCase(Locale.ROOT));
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new TrustLedgerException(ErrorCode.UNAUTHENTICATED, "Authentication required.");
        }
        return identity.findUserId(token.getToken().getSubject())
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "User profile not found."));
    }

    private static void writeReplay(HttpServletResponse response, IdempotencyClaim.Replay replay) throws IOException {
        response.setStatus(replay.status());
        response.setHeader(REPLAYED_HEADER, "true");
        if (replay.body() != null) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(replay.body().getBytes(StandardCharsets.UTF_8));
        }
    }

    private record ClaimedKey(UUID userId, UUID key) {
    }
}
