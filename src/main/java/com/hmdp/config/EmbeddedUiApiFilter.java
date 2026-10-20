package com.hmdp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Serve the existing Chinese UI and /api on Tomcat NIO for local Windows use. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
@ConditionalOnProperty(name="hmdp.web.embedded-ui-enabled",havingValue="true")
public class EmbeddedUiApiFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(path.startsWith("/api/")) {
            request.getRequestDispatcher(path.substring(4)).forward(request,response);
        } else chain.doFilter(request,response);
    }
}
