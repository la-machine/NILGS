// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.security;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.snigf.mvp.identity.application.SpecialGrantService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Settles the special-grant uses {@link Access} took for a request: when the response is a
 * success the use stays taken and is audited ({@code PERMISSION_GRANT_USED}); when the
 * action failed (an exception or a status of 400 or more) the use is given back and the
 * failed attempt audited. A grant limited to three uses therefore allows three successful
 * actions, never more, even under concurrent requests.
 */
@Configuration(proxyBeanMethods = false)
public class SpecialGrantUseInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final SpecialGrantService specialGrants;

    public SpecialGrantUseInterceptor(SpecialGrantService specialGrants) {
        this.specialGrants = specialGrants;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        List<Access.Reserved> reserved = (List<Access.Reserved>) request.getAttribute(Access.RESERVED);
        if (reserved == null) {
            return;
        }
        boolean failed = ex != null || response.getStatus() >= 400;
        for (Access.Reserved use : reserved) {
            if (failed) {
                specialGrants.release(use.grant().id(), use.grant().permission(), request.getMethod(), request.getRequestURI());
            } else {
                specialGrants.confirm(use.grant().id(), use.grant().permission(), request.getMethod(), request.getRequestURI());
            }
        }
        request.removeAttribute(Access.RESERVED);
    }
}
