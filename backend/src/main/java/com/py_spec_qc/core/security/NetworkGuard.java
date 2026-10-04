package com.py_spec_qc.core.security;

import java.net.URI;

/** 判断 LLM 端点是否属于内网/本机，用于「数据不出域」校验。 */
public final class NetworkGuard {
    private NetworkGuard() {
    }

    /** 无法解析或未配置时按「非外网」处理，避免误拦截空配置。 */
    public static boolean isExternal(String baseUrl) {
        String host = hostOf(baseUrl);
        if (host.isBlank()) {
            return false;
        }
        return !isPrivateHost(host);
    }

    public static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean isPrivateHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase();
        if (h.isEmpty()) {
            return false;
        }
        if (h.equals("localhost") || h.equals("::1") || h.equals("[::1]")) {
            return true;
        }
        if (h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home")) {
            return true;
        }
        if (h.contains(":")) {
            return h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80");
        }
        String[] parts = h.split("\\.");
        if (parts.length == 4 && isNumericParts(parts)) {
            int a = parseInt(parts[0]);
            int b = parseInt(parts[1]);
            if (a == 127 || a == 10) {
                return true;
            }
            if (a == 192 && b == 168) {
                return true;
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true;
            }
            if (a == 169 && b == 254) {
                return true;
            }
            if (a == 100 && b >= 64 && b <= 127) {
                return true;
            }
            return false;
        }
        // 无点号的主机名按内网短名处理
        return !h.contains(".");
    }

    private static boolean isNumericParts(String[] parts) {
        for (String p : parts) {
            if (parseInt(p) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
