# ADR 0018: `spring-boot-starter-aspectj` for the `@Audited` aspect

- Status: Accepted
- Date: 2026-11-22
- Spec: docs/SPEC.md FR-A-1, §8 M10 ("an `@Audited` annotation + aspect for compliance/admin endpoints");
  CLAUDE.md Stack (changed by this ADR); ADR 0008

## Context

M10 asks for an `@Audited` annotation and an aspect around compliance and admin endpoints that writes an audit
event per call. Spring's `@Aspect` support needs AspectJ's annotation and pointcut classes (`aspectjweaver`) on
the classpath, and Boot only switches on `@Aspect` auto-proxying when it finds them.

Today `aspectjweaver` is on core-app's classpath only by accident, as a transitive dependency of another starter.
Relying on that is fragile: a dependency upgrade could drop it, and the aspect would silently stop being applied,
so compliance actions would go unaudited without any test failing at compile time.

## Options

1. **`spring-boot-starter-aspectj`** (Boot 4's name for the former `spring-boot-starter-aop`). It declares
   `aspectjweaver` explicitly; the version comes from the Boot BOM. The aspect is a normal `@Aspect` class with
   `@Around("@annotation(audited)")`.
2. **Plain Spring AOP, no AspectJ:** a `MethodInterceptor` with an `AnnotationMatchingPointcut` in a
   `DefaultPointcutAdvisor` bean. No new dependency, but more wiring (advisor bean, ordering, parameter binding by
   hand), and it is less familiar to a reader than `@Aspect`.
3. **A Spring MVC `HandlerInterceptor`.** No proxies, but it can't see the controller's return value (needed for the
   reconciliation counts) or the exception in a clean way, and it ties auditing to the web layer's internals.

## Decision

Option 1. The dependency was already on the classpath; this makes it explicit and version-managed, and the aspect
reads like the textbook pattern. Only Spring AOP's proxy-based weaving is used (no load-time or compile-time
weaving), so behaviour is the same as any other Spring proxy: `@Audited` methods must be `public` and are only
intercepted when called through the bean (always the case for controller methods called by Spring MVC).

## Consequences

- CLAUDE.md Stack lists AspectJ (Spring AOP) for the audit aspect.
- `AuditedEndpointsTest` checks every `@Audited` method is `public` and correctly wired, and `AuditedAspectTest`
  runs the aspect through a real `AspectJProxyFactory`, so a missing dependency or a broken pointcut fails the build.
- The aspect is ordered `LOWEST_PRECEDENCE`, inside Spring Security's method interceptors: a call refused by
  `@PreAuthorize` never reaches it.
