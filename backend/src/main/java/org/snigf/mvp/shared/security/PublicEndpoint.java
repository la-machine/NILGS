// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method that is deliberately reachable without a permission check
 * (login, registration, CSRF token). Every other controller method carries
 * {@code @PreAuthorize}; {@code EndpointAnnotationArchTest} fails the build otherwise, so
 * an endpoint can never be public by omission (Slice 1 design §6).
 *
 * <p>The URL rules in the security filter chain must open the same paths; this annotation
 * documents and checks the intent, it does not open anything by itself.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicEndpoint {

    /** Why the endpoint is public. */
    String value();
}
