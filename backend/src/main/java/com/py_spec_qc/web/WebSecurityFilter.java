package com.py_spec_qc.web;

import com.py_spec_qc.core.config.AppConfig;
import com.py_spec_qc.core.config.ConfigLoader;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Web 访问控制：对 /api/** 施加 IP 白名单与可选访问令牌。
 * 本机（回环）请求始终放行；未配置白名单/令牌时不改变原有行为（保持兼容）。
 */
@Component
@Order(1)
public final class WebSecurityFilter implements Filter {
    private final List<String> allowedIps;
    private final String authToken;

    public WebSecurityFilter() {
        AppConfig cfg = new ConfigLoader().load();
        this.allowedIps = cfg == null ? List.of() : (cfg.serverAllowedIps == null ? List.of() : cfg.serverAllowedIps);
        this.authToken = cfg == null ? null : cfg.serverAuthToken;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse resp)) {
            chain.doFilter(request, response);
            return;
        }
        String path = req.getRequestURI();
        if (path == null || !path.startsWith("/api")) {
            chain.doFilter(request, response);
            return;
        }
        String remote = req.getRemoteAddr();
        if (isLoopback(remote)) {
            chain.doFilter(request, response);
            return;
        }
        if (!allowedIps.isEmpty() && !ipAllowed(remote, allowedIps)) {
            reject(resp, HttpServletResponse.SC_FORBIDDEN, "ip_not_allowed: " + remote);
            return;
        }
        if (authToken != null && !authToken.isBlank() && !authToken.equals(extractToken(req))) {
            reject(resp, HttpServletResponse.SC_UNAUTHORIZED, "invalid_or_missing_token");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse resp, int status, String reason) throws IOException {
        System.err.println("[安全拒绝] /api 访问被拦截: " + reason);
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getOutputStream().write(("{\"error\":\"" + reason + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static String extractToken(HttpServletRequest req) {
        String header = req.getHeader("X-Auth-Token");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        String q = req.getParameter("token");
        return q == null ? null : q.trim();
    }

    private static boolean isLoopback(String ip) {
        if (ip == null) {
            return false;
        }
        String s = ip.trim().toLowerCase();
        return s.equals("localhost") || s.equals("::1") || s.equals("0:0:0:0:0:0:0:1") || s.startsWith("127.");
    }

    private static boolean ipAllowed(String ip, List<String> patterns) {
        if (ip == null) {
            return false;
        }
        for (String raw : patterns) {
            if (matchPattern(ip, raw)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchPattern(String ip, String pattern) {
        if (pattern == null) {
            return false;
        }
        String p = pattern.trim();
        if (p.isEmpty()) {
            return false;
        }
        if (p.contains("/")) {
            return matchCidr(ip, p);
        }
        if (p.contains("*")) {
            String regex = "^" + java.util.regex.Pattern.quote(p).replace("*", "\\E.*\\Q") + "$";
            return ip.matches(regex);
        }
        return ip.equalsIgnoreCase(p);
    }

    private static boolean matchCidr(String ip, String cidr) {
        String[] parts = cidr.split("/", 2);
        if (parts.length != 2) {
            return false;
        }
        long ipVal = ipv4ToLong(ip);
        long netVal = ipv4ToLong(parts[0].trim());
        if (ipVal < 0 || netVal < 0) {
            return false;
        }
        int bits;
        try {
            bits = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (bits < 0 || bits > 32) {
            return false;
        }
        long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
        return (ipVal & mask) == (netVal & mask);
    }

    private static long ipv4ToLong(String ip) {
        if (ip == null) {
            return -1;
        }
        String[] parts = ip.trim().split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long v = 0;
        for (String part : parts) {
            int b;
            try {
                b = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
            if (b < 0 || b > 255) {
                return -1;
            }
            v = (v << 8) | b;
        }
        return v;
    }
}
