package dev.javaharness.server.web;

import dev.javaharness.server.service.DesktopUpdateGate;
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
    private final DesktopUpdateGate updates;

    public LocalApiFilter(DesktopUpdateGate updates) {
        this.updates = updates;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 使用容器解析后的路径，避免编码或路径参数绕过更新门禁。
        String path = request.getServletPath();
        if (path.startsWith("/api/")) {
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
        boolean mutation = path.startsWith("/api/")
                && !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                && !Set.of("/api/desktop/update/prepare", "/api/desktop/update/release")
                        .contains(path);
        if (!mutation) {
            chain.doFilter(request, response);
            return;
        }
        if (!updates.enter()) {
            response.setStatus(503);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"UNAVAILABLE\",\"message\":\"正在准备更新，请稍后重试\"}");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            updates.leave();
        }
    }
}
