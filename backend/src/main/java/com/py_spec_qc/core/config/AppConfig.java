package com.py_spec_qc.core.config;

import java.nio.file.Path;
import java.util.List;

public final class AppConfig {
    public String deepseekBaseUrl;
    public String deepseekModel;
    public String deepseekApiKey;
    public boolean deepseekApiKeyFromEnv;
    public boolean deepseekAllowExternal = true;
    public Integer deepseekTimeoutSeconds;
    public Integer scanRuleChunkSize;
    public List<Path> scanAllowedRoots;
    public Path workDir;
    public Path configPath;
    public String serverHost;
    public Integer serverPort;
    public boolean serverAllowRemote;
    public List<String> serverAllowedIps;
    public String serverAuthToken;
}
