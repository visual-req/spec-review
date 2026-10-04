<h3 align="center">spec-review</h3>
<p align="center">SpecQC：基于规则 + LLM 的需求质量检查工具，批量扫描 <code>.docx</code> 输出问题清单，并提供本地 Web UI 做复核与导出。</p>
<p align="center">
  <a href="https://github.com/visual-req/spec-review/releases"><img src="https://img.shields.io/github/v/release/visual-req/spec-review" alt="Release"></a>
  <a href="https://github.com/visual-req/spec-review"><img src="https://img.shields.io/github/stars/visual-req/spec-review?style=flat-square" alt="Stars"></a>
  <a href="https://github.com/visual-req/spec-review/issues"><img src="https://img.shields.io/github/issues/visual-req/spec-review?style=flat-square" alt="Issues"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue?style=flat-square" alt="License"></a>
</p>
<p align="center">
  <a href="README_en.md">English</a> · <a href="README.md">中文</a> · <a href="README_ja.md">日本語</a>
  <br/>
  <a href="docs/zh/getting-started.md">快速开始</a> · <a href="docs/zh/manual.md">使用手册</a> · <a href="docs/zh/rules.md">规则</a> · <a href="docs/zh/troubleshooting.md">排障</a>
</p>
<hr />

Version: 0.1.0 · License: MIT ([LICENSE](LICENSE))

## 为什么可靠

- 规则驱动：输出口径由规则文件约束，避免纯“自由发挥”
- 可追溯：每条问题保留证据片段与定位信息，便于快速复核
- 可回归：规则/提示词调整后可用历史样本文档回归扫描，持续降低误报与漏报

## 目录结构

- backend：后端（Java / Spring Boot / Picocli），负责扫描、规则加载、写 Excel、Web API
- frontend：前端（Vue），编译产物打进后端 jar
- executable：开箱即用目录（config.yaml、spec-qc-*.jar、work 示例目录、Windows 启动脚本）
- work：默认工作目录（input/output/quality/logs/revise/cache.yaml）
- docs：更详细的说明与扩展规则指南

## 快速开始（Web UI）

1) 配置大模型访问（以 DeepSeek 为例）

编辑 `executable/config.yaml`：

```yaml
deepseek:
  base_url: "https://api.deepseek.com/v1"
  api_key: "YOUR_DEEPSEEK_API_KEY"
  model: "deepseek-chat"
  # 指向公网模型时必须显式允许，否则会阻止发送需求文档（数据不出域）
  allow_external: true

scan:
  # 可选：限制可扫描/可浏览的根目录（逗号分隔）；未配置时对工作区外目录仅告警不阻断
  # allowed_roots: "work"

server:
  # 默认仅本机可访问；如需局域网访问请显式改为 0.0.0.0 并设置 allow_remote: true
  host: "127.0.0.1"
  port: 8765
  allow_remote: false
  # allowed_ips: "127.0.0.1, 192.168.1.*, 10.0.0.0/8"
  # auth_token: ""

work_dir: "work"
```

也可用环境变量覆盖：

```bash
export DEEPSEEK_API_KEY="..."
export DEEPSEEK_BASE_URL="https://api.deepseek.com/v1"
export DEEPSEEK_MODEL="deepseek-chat"
export SPEC_QC_WORK_DIR="/abs/path/to/work"
export SPEC_QC_ALLOW_EXTERNAL_LLM="false"   # 禁止把需求发送到外部模型
export SPEC_QC_ALLOWED_ROOTS="/abs/req,/abs/rules"  # 限制可扫描目录
export SPEC_QC_ALLOW_REMOTE="0"             # 非回环监听需显式开启
export SPEC_QC_ALLOWED_IPS="192.168.1.*"    # 仅这些来源 IP 可访问 /api
export SPEC_QC_AUTH_TOKEN="..."             # 访问令牌（Header: X-Auth-Token / Bearer）
```

2) 启动

macOS / Linux：

```bash
cd executable
java -jar spec-qc-0.1.0.jar web
```

Windows：

```bat
cd executable
start.bat
```

浏览器访问：

- http://localhost:8765/

## 命令行扫描（CLI）

```bash
cd executable
java -jar spec-qc-0.1.0.jar scan -req /path/to/req_dir
```

可选参数：

- `--out /path/to/out_dir`：输出目录（默认会落到可写 work/output）
- `--rules /path/to/rules_dir`：自定义规则目录（目录下可放多个 .md 规则文件）

## 规则与行业边界

- 通用规则：work/quality/quality_standard.md
- 行业规则示例（银行）：work/quality/banking_quality_standard.md
- 每个 quality 文件开头需要声明“适用行业”，扫描时会避免跨行业误扫（可用环境变量 `SPEC_QC_INDUSTRY` 指定行业）

如何新增规则与文件格式见：

- `docs/zh/rules.md`

## 日志与排障

- 扫描全流程日志：work/logs/spec-qc.log（包含扫描中途失败原因、文件名、规则段、异常堆栈）
- 模型调用日志：work/logs/large-model.log（不记录 api_key；默认不记录敏感内容）

常见问题与解决方式见：

- `docs/zh/troubleshooting.md`

更多结构说明见：

- `docs/zh/structure.md`
- 图示：`docs/zh/work-principle.md` / `docs/zh/scan-process.md` / `docs/zh/quality-tuning.md`

## 安全提示

- 不要把真实 `deepseek.api_key` 或 `DEEPSEEK_API_KEY` 提交到仓库；推荐用环境变量注入（环境变量优先于配置文件）
- 本地私有配置请使用 `config.local.yaml`（已在 .gitignore 中排除），避免把密钥带入版本库
- 数据出域：`allow_external=false` 时，若 `base_url` 为公网地址将直接阻止发送需求文档；指向公网模型时会记录 `data_boundary_warning` 日志
- 敏感信息预检：送审前会对需求文本中的密码/令牌/密钥等做脱敏（记录 `redacted_secrets` 日志）
- 服务默认仅监听 `127.0.0.1`；监听非本机地址需显式 `server.allow_remote=true`，并建议同时配置 `server.allowed_ips` 与 `server.auth_token`
- 扫描目录边界：配置 `scan.allowed_roots` 后，扫描/浏览将被限制在这些根目录内；未配置时对工作区外目录仅记录告警
- 规则行业边界：存在行业专属规则但未声明 `SPEC_QC_INDUSTRY` 时会明确报错，避免跨行业误扫
