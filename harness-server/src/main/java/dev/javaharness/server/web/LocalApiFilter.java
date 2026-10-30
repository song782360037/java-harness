package dev.javaharness.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local-only API: reject foreign Hosts and browser origins; mutations also require JSON. */
@Component
public class LocalApiFilter extends OncePerRequestFilter {
    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "[::1]");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            boolean allowed = LOCAL.contains(request.getServerName());
            String origin = request.getHeader("Origin");
            if (origin != null) {
                try {
                    URI uri = URI.create(origin);
                    int port = uri.getPort() == -1 ? 80 : uri.getPort();
                    allowed &= "http".equals(uri.getScheme())
                            && LOCAL.contains(uri.getHost())
                            // 5173 is the Vite dev server in harness-web.
                            && (port == request.getLocalPort() || port == 5173);
                } catch (IllegalArgumentException error) {
                    allowed = false;
                }
            }
            if (!allowed) {
                response.sendError(403);
                return;
            }
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }
}
