package com.trustledger.core.audit.infrastructure;

import com.trustledger.core.audit.application.AuditedActionRecorder;
import com.trustledger.core.audit.domain.AuditAction;
import com.trustledger.core.audit.domain.AuditEntityType;
import com.trustledger.core.shared.Ids;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.shared.audit.AuditDetails;
import com.trustledger.core.shared.audit.Audited;
import com.trustledger.core.shared.web.CorrelationIdFilter;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Writes one audit event per call of an {@link Audited} endpoint (FR-A-1, M10, ADR 0008 "Day 20").
 * <ol>
 *   <li>Snapshot of the entity before the call (unless {@link Audited#captureBefore()} is off).</li>
 *   <li>The call. It commits (or rolls back) its own business transaction.</li>
 *   <li>Success: a SUCCEEDED event with the snapshot after. A {@link TrustLedgerException} (a business rule said
 *       no, for example the maker trying to approve): a REJECTED event with the error code, then the exception
 *       goes on to the error handler. Anything else is a 500, already logged, and not audited.</li>
 * </ol>
 * The event is written after the business commit, in its own transaction. If writing it fails, the change has
 * already committed and can't be undone, so the response still goes out and the failure is logged at ERROR with
 * the action, entity ID and correlation ID (no personal data) for follow-up. The atomic domain-event rows (Day 19)
 * still cover freeze, unfreeze and both approvals.
 * <p>
 * Order {@link Ordered#LOWEST_PRECEDENCE}: inside Spring Security's {@code @PreAuthorize} interceptor, so a call
 * that method security refuses never gets here (it is in the security log instead).
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class AuditedAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditedAspect.class);

    private final AuditedActionRecorder recorder;
    private final Clock clock;

    AuditedAspect(AuditedActionRecorder recorder, Clock clock) {
        this.recorder = recorder;
        this.clock = clock;
    }

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint call, Audited audited) throws Throwable {
        AuditAction action = AuditAction.valueOf(audited.action());
        AuditEntityType entityType = AuditEntityType.valueOf(audited.entityType());
        Method method = ((MethodSignature) call.getSignature()).getMethod();
        Object[] args = call.getArgs();
        UUID entityId = audited.entityIdParam().isEmpty() ? Ids.newV7(clock)
                : entityId(method, args, audited.entityIdParam());

        Map<String, Object> details = new LinkedHashMap<>();
        for (Object arg : args) {
            addDetails(details, arg);
        }
        Map<String, Object> before = audited.captureBefore() ? snapshotBefore(action, entityType, entityId) : null;

        Object result;
        try {
            result = call.proceed();
        } catch (TrustLedgerException e) {
            record(action, entityId, "REJECTED",
                    () -> recorder.rejected(action, entityType, entityId, before, details, e.errorCode().name()));
            throw e;
        }
        addDetails(details, result instanceof HttpEntity<?> entity ? entity.getBody() : result);
        record(action, entityId, "SUCCEEDED",
                () -> recorder.succeeded(action, entityType, entityId, before, details));
        return result;
    }

    /**
     * The {@code UUID} argument whose parameter (or {@code @PathVariable}) has the given name. A mismatch is a
     * programming error, caught by {@code AuditedEndpointsTest}; failing the call is better than a wrong entity.
     */
    static UUID entityId(Method method, Object[] args, String name) {
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (name.equals(parameterName(parameters[i]))) {
                if (args[i] instanceof UUID id) {
                    return id;
                }
                throw new IllegalStateException("@Audited entity ID parameter '" + name + "' of "
                        + method.getName() + " is not a non-null UUID");
            }
        }
        throw new IllegalStateException("@Audited method " + method.getName() + " has no parameter '" + name + "'");
    }

    static String parameterName(Parameter parameter) {
        PathVariable path = parameter.getAnnotation(PathVariable.class);
        if (path != null && !path.name().isEmpty()) {
            return path.name();
        }
        if (path != null && !path.value().isEmpty()) {
            return path.value();
        }
        return parameter.isNamePresent() ? parameter.getName() : null;
    }

    private static void addDetails(Map<String, Object> details, Object value) {
        if (value instanceof AuditDetails source) {
            details.putAll(source.auditDetails());
        }
    }

    /** A failed snapshot must not block an officer's action: the event is still written, without {@code before}. */
    private Map<String, Object> snapshotBefore(AuditAction action, AuditEntityType entityType, UUID entityId) {
        try {
            return recorder.snapshot(entityType, entityId);
        } catch (RuntimeException e) {
            log.warn("Audit snapshot before {} of {} {} failed: {}", action, entityType, entityId, e.toString());
            return null;
        }
    }

    private static void record(AuditAction action, UUID entityId, String outcome, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            log.error("AUDIT WRITE FAILED: {} {} on entity {} (correlation {}) has no audit event", outcome,
                    action, entityId, MDC.get(CorrelationIdFilter.MDC_KEY), e);
        }
    }
}
