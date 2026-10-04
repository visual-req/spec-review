[中文](installation.md) | [English](../en/installation.md) | [日本語](../ja/installation.md)

## 安装与环境准备

### 1) 运行环境（开箱即用 jar）

- Java：需要 JDK/JRE 17+
- 网络：可访问 DeepSeek API（或你配置的 base_url）

验证 Java：

```bash
java -version
```

### 2) 获取可执行包目录

仓库内已提供 `executable/` 作为开箱即用目录（含 `config.yaml`、`spec-qc-*.jar`、示例 `work/`）。

关键文件：

- `executable/config.yaml`：运行配置（DeepSeek/端口/work_dir）
- `executable/spec-qc-*.jar`：可执行 jar
- `executable/work/`：默认工作目录（input/output/quality/logs/revise/cache.yaml）

### 3) 配置 DeepSeek

编辑 `executable/config.yaml`：

```yaml
deepseek:
  base_url: "https://api.deepseek.com/v1"
  api_key: "YOUR_DEEPSEEK_API_KEY"
  model: "deepseek-chat"
  allow_external: true   # 指向公网模型必须显式允许，否则阻止发送需求（数据不出域）

scan:
  # allowed_roots: "work"   # 可选：限制可扫描/可浏览的根目录

server:
  host: "127.0.0.1"      # 默认仅本机可访问
  port: 8765
  allow_remote: false    # 监听非本机地址需显式开启
  # allowed_ips: "127.0.0.1, 192.168.1.*, 10.0.0.0/8"
  # auth_token: ""

work_dir: "work"
```

推荐用环境变量注入密钥：

```bash
export DEEPSEEK_API_KEY="..."
```

可选环境变量：

- `SPEC_QC_WORK_DIR`：覆盖工作目录（绝对路径更稳妥）
- `SPEC_QC_HOST` / `SPEC_QC_PORT`：覆盖 Web 监听地址与端口
- `SPEC_QC_INDUSTRY`：指定当前扫描行业（用于规则行业边界）
- `SPEC_QC_ALLOW_EXTERNAL_LLM`：是否允许把需求发送到外部模型（false 时公网地址将被阻止）
- `SPEC_QC_ALLOWED_ROOTS`：限制可扫描/可浏览的根目录（逗号分隔）
- `SPEC_QC_ALLOW_REMOTE`：是否允许监听非本机地址（默认 false）
- `SPEC_QC_ALLOWED_IPS`：`/api/**` 来源 IP 白名单（支持 `192.168.1.*`、`10.0.0.0/8`）
- `SPEC_QC_AUTH_TOKEN`：`/api/**` 访问令牌（请求头 `X-Auth-Token` 或 `Authorization: Bearer`）

### 4) 从源码构建（可选）

仅在你需要自定义后端/前端并重新打包时使用。

- Java 17 + Maven
- Node.js（用于构建前端资源）

在仓库根目录构建后端（会触发前端打包）：

```bash
mvn -f backend/pom.xml -DskipTests package
```

### 5) Docker 运行（可选）

使用仓库根目录的 `Dockerfile` 构建镜像（会在容器内完成前后端打包，并产出可运行 jar）：

```bash
docker build -t spec-qc .
```

Web 模式示例（推荐：把配置与工作目录放到仓库外部，避免把 token 提交到 git）：

```bash
docker run --rm \
  -p 8765:8765 \
  -e SPEC_QC_CONFIG="/data/config.yaml" \
  -e DEEPSEEK_API_KEY="..." \
  -v "/abs/path/to/spec-qc-data:/data" \
  spec-qc
```

其中 `/abs/path/to/spec-qc-data/` 目录建议包含：

- `config.yaml`（可写 `work_dir: "work"`，会相对本文件解析为 `/data/work`）
- `work/`（input/output/quality/logs/revise/cache.yaml）

CLI 扫描示例（覆盖镜像默认命令，直接运行 scan 子命令）：

```bash
docker run --rm \
  -e DEEPSEEK_API_KEY="..." \
  -v "/abs/path/to/req_dir:/data/req:ro" \
  -v "/abs/path/to/out_dir:/data/out" \
  -v "/abs/path/to/rules_dir:/data/rules:ro" \
  spec-qc scan -req /data/req --out /data/out --rules /data/rules
```
