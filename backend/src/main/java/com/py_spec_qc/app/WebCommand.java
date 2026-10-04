package com.py_spec_qc.app;

import com.py_spec_qc.core.config.AppConfig;
import com.py_spec_qc.core.config.ConfigLoader;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "web", description = "Start a local web UI")
public final class WebCommand implements Runnable {
    @Option(names = "--host", required = false)
    private String host;

    @Option(names = "--port", required = false)
    private Integer port;

    @Override
    public void run() {
        AppConfig cfg = new ConfigLoader().load();
        String resolvedHost = (host == null || host.isBlank()) ? (cfg.serverHost == null || cfg.serverHost.isBlank() ? "127.0.0.1" : cfg.serverHost) : host;
        int resolvedPort = (port == null || port <= 0) ? (cfg.serverPort == null || cfg.serverPort <= 0 ? 8765 : cfg.serverPort) : port;

        boolean loopback = isLoopbackHost(resolvedHost);
        if (!loopback && !cfg.serverAllowRemote) {
            throw new IllegalStateException("拒绝启动：非本机地址 " + resolvedHost
                    + " 需要显式授权。请设置 server.allow_remote=true（或环境变量 SPEC_QC_ALLOW_REMOTE=1），"
                    + "并建议同时配置 server.allowed_ips（IP 白名单）与 server.auth_token（访问令牌）。默认仅监听 127.0.0.1。");
        }
        if (!loopback) {
            System.err.println("[安全告警] Web 服务将监听非本机地址 " + resolvedHost + "，局域网内可访问。");
            if (cfg.serverAllowedIps == null || cfg.serverAllowedIps.isEmpty()) {
                System.err.println("[安全告警] 未配置 server.allowed_ips（IP 白名单），建议限制来源 IP。");
            }
            if (cfg.serverAuthToken == null || cfg.serverAuthToken.isBlank()) {
                System.err.println("[安全告警] 未配置 server.auth_token（访问令牌），/api/** 将无访问控制。");
            }
        }

        if (!cfg.deepseekApiKeyFromEnv && cfg.deepseekApiKey != null && !cfg.deepseekApiKey.isBlank()) {
            System.err.println("[安全告警] LLM api_key 来自配置文件而非环境变量，存在明文泄露风险。建议改用环境变量 LLM_API_KEY / DEEPSEEK_API_KEY 注入。");
        }

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(WebApplication.class)
                .properties(
                        "server.address=" + resolvedHost,
                        "server.port=" + resolvedPort,
                        "spring.servlet.multipart.max-file-size=200MB",
                        "spring.servlet.multipart.max-request-size=200MB"
                )
                .run();
        String url = "http://" + resolvedHost + ":" + resolvedPort + "/";
        System.out.println(url);
        try {
            ctx.getBean(BlockingLifecycle.class).block();
        } finally {
            ctx.close();
        }
    }

    private static boolean isLoopbackHost(String h) {
        if (h == null) {
            return false;
        }
        String s = h.trim().toLowerCase();
        return s.equals("localhost") || s.equals("::1") || s.equals("0:0:0:0:0:0:0:1")
                || s.equals("127.0.0.1") || s.startsWith("127.");
    }
}
