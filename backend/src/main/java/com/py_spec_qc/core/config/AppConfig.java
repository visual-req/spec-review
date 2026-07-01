package com.py_spec_qc.core.config;

import java.nio.file.Path;

public final class AppConfig {
    public String deepseekBaseUrl;
    public String deepseekModel;
    public String deepseekApiKey;
    public Integer deepseekTimeoutSeconds;
    public Integer scanRuleChunkSize;
    public Path workDir;
    public Path configPath;
    public String serverHost;
    public Integer serverPort;
}
