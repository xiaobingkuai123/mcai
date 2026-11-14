# McAI

**Current release: 1.0.1**

McAI is an AI conversation plugin for Minecraft Paper and Folia servers. Players can interact with supported AI providers through commands, inventory GUIs, or Bedrock Edition forms.

## What's new in 1.0.1

Compared with `v1.0.0`, this release contains a substantial request, conversation, persistence, localization, and reliability update:

### Request and provider improvements

- Added shared request configuration under `api.request`: temperature, top-p, retry count, streaming, reasoning enablement, reasoning budget, and reasoning effort.
- Added per-provider output-token budgets for thinking and non-thinking requests. The plugin enforces only the required minimums; the selected service/model determines the supported maximum.
- Unified request options across the DeepSeek, OpenAI Chat Completions, OpenAI Responses, Anthropic, and Gemini adapters.
- Added configurable Anthropic API version handling and preserved Anthropic thinking blocks/signatures when replaying history.
- Improved streaming request handling, including provider-specific SSE parsing, timeout watchdogs, completion detection, HTTP error reporting, and invalid-response handling.
- Added prompt-cache usage reporting where providers expose cache statistics.
- Added optional Gemini Cached Content support, with automatic fallback to inline conversation history if the cache request is unavailable.

### Conversation and persistence improvements

- Reworked conversation history around complete user/assistant rounds instead of an unstructured message list.
- Added `sliding` and `accumulate` history modes, configurable round limits, persistence, and console history support.
- Added Java inventory and Bedrock form flows for starting a new conversation window when an accumulate-mode limit is reached.
- Added atomic per-player/console YAML history persistence and safer shutdown saving.
- Improved daily point-usage persistence with asynchronous saves, shutdown draining, and atomic file replacement.

### Localization, configuration, and quality

- Language files are selected by the configured `language` identifier and loaded from `lang/<identifier>.yml`, rather than by a hard-coded Chinese/English list.
- Invalid identifiers, missing files, empty files, and invalid YAML are handled with warnings and fallback to `zh_CN`.
- Added clear comments for new configuration options, defaults, and token-budget minimums.
- Added automated coverage for adapter request bodies, conversation history, language parsing/fallback validation, and token-budget behavior.
- Updated the bundled default model/configuration examples and bumped the plugin/Maven version to `1.0.1`.

## Features

- Supports Paper and Folia 1.21 and detects the appropriate scheduler automatically.
- Loads the selected language from `lang/<language>.yml`; the default is `zh_CN`. Add another YAML message file using the existing language-file format to add a language without changing code.
- Supports DeepSeek, OpenAI Chat Completions, OpenAI Responses API, Anthropic Claude, and Google Gemini.
- Provides Normal, Normal Thinking, Pro, and Pro Thinking conversation modes.
- Supports streaming responses with BossBar progress display and optional display of reasoning content after a thinking request completes.
- Stores configurable conversation history for players and the console, with optional persistence.
- Provides an inventory GUI for Java Edition and Cumulus forms for Bedrock Edition players when Floodgate is installed.
- Supports optional Vault economy integration, point rewards/deductions, daily quotas, request rate limiting, and question-length limits.
- Supports runtime configuration reload and cleans up active requests when players disconnect or the plugin is disabled.

## Requirements

- Java 21 or later
- Paper or Folia 1.21
- At least one configured AI provider API key

Optional integrations:
- Vault and an economy plugin for point purchases
- Floodgate for Bedrock Edition form support

## Installation and upgrade

1. Download `mcai-1.0.1.jar` from the [Releases page](https://github.com/xiaobingkuai123/mcai/releases).
2. Stop the server and back up `plugins/McAI/` (especially `config.yml` and data files).
3. Replace the old McAI JAR in `plugins/` with `mcai-1.0.1.jar`.
4. Start the server. For a fresh install, edit `plugins/McAI/config.yml` and configure at least one provider's API key, base URL, API type, and model names.
5. For an existing installation, retain your current configuration and copy any desired new settings from the default configuration shipped in this repository. In particular, `api.request.*` settings can be left at their code defaults if absent. Do not overwrite a file containing API keys with the example configuration.
6. Restart after configuration changes, or run `/ai reload` in-game/console.

Never commit or share a configuration file containing real API keys.

## Configuration

The complete default configuration is in [`src/main/resources/config.yml`](src/main/resources/config.yml). On a running server, edit `plugins/McAI/config.yml`.

| Configuration path | Purpose / default |
| --- | --- |
| `language` | Language resource filename without `.yml`; default `zh_CN`. Missing/invalid files fall back to `zh_CN`. |
| `api.normal-provider`, `api.pro-provider` | Provider used by Normal and Pro modes. |
| `api.<provider>.key`, `base-url`, `api-type` | Provider credentials and API endpoint settings. |
| `api.<provider>.*-model` | Model IDs used for each mode. Check the configured endpoint's documentation. |
| `api.<provider>.max-tokens-fast` | Maximum output tokens for non-thinking requests; default `4096`. |
| `api.<provider>.max-tokens-think` | Maximum output tokens for thinking requests; default `64000` for DeepSeek and `32768` for the other bundled providers. |
| `api.request.temperature`, `top-p` | Shared sampling settings; adapters omit unsupported provider fields. |
| `api.request.max-retries`, `streaming` | Shared retry and streaming settings. |
| `api.request.thinking.enabled` | Whether provider adapters that support it enable reasoning for thinking modes; default `true`. |
| `api.request.thinking.budget` | Shared reasoning budget; default `4096`, minimum `1024`. Provider/model limits still apply. |
| `api.request.reasoning-effort` | Reasoning effort sent where supported; default `medium`. |
| `api.timeout-seconds` | HTTP request timeout; default `120`. |
| `chat.system-prompt`, `chat.console-max-history` | System prompt and console history limit (default: 30 rounds). Player history limits use `permissions.groups.*.max-history`. |
| `chat.history.*`, `chat.streaming.*` | History mode/persistence and stream/BossBar settings. In `accumulate` mode, the round limit comes from the matching permission group. |
| `points.*`, `rate-limit.*`, `daily-limit.*` | Point costs, request limits, and the daily-limit toggle. |
| `permissions.groups.*` | Unified permission groups for daily points, question length, and per-group maximum history. |
| `sounds.*` | Sound feedback options. |

Token values are passed to the selected adapter. The plugin applies a minimum of 1 to output-token settings and 1024 to the shared reasoning budget; it does not impose a maximum. Provider/model limits and request-shape constraints still apply, so configure values accepted by the endpoint you use.

### Languages

Set `language` to a filename identifier (letters, numbers, `_`, and `-`), without the `.yml` suffix. For example, `language: "en_US"` selects `plugins/McAI/lang/en_US.yml`. The bundled resources are `zh_CN.yml` and `en_US.yml`. To use a custom language, provide `plugins/McAI/lang/<identifier>.yml` in the same YAML string-message format. If the selected file is absent, invalid, or empty, McAI logs a warning and uses `zh_CN` instead.

## Supported providers

- **DeepSeek** — DeepSeek Chat-compatible API, including reasoning models.
- **OpenAI** — Chat Completions and Responses API. Set `api.openai.api-type` to the endpoint type appropriate for the selected model. Compatible third-party Chat Completions services may also work.
- **Anthropic** — Messages API.
- **Google Gemini** — Generative Language API, with optional cached content.

Use the provider's current API documentation for valid base URLs, model names, token limits, and feature support. The default model IDs in `config.yml` are examples and may need to be changed for your endpoint/account.

## Commands

Players can run `/ai` to open the main menu. Chat may be entered directly or through an interactive prompt:

```text
/ai
/ai help
/ai chat <message>
/ai chat think <message>
/ai pro chat <message>
/ai pro chat think <message>
/ai buy [amount]
/ai clear
/ai give <player> <amount>
/ai take <player> <amount>
/ai reload
```

Console commands include `ai help`, `ai chat <message>`, `ai chat think <message>`, `ai pro chat <message>`, `ai pro chat think <message>`, `ai give <player> <amount>`, `ai take <player> <amount>`, `ai clear`, and `ai reload`. Console cannot use the player-only purchase command. During interactive input, players can enter `c`, `cancel`, or `取消` to cancel.

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `mcai.use` | Everyone | Use the plugin and send AI requests |
| `mcai.admin` | Operators | Reload configuration and manage points |
| `mcai.group.vip` | Disabled | Use the VIP daily quota |
| `mcai.group.admin` | Operators | Use the administrator daily quota |

## Points, limits, and quotas

Each mode's point cost is set by `points.normal-cost`, `points.normal-think-cost`, `points.pro-cost`, and `points.pro-think-cost`. If Vault and an economy plugin are available, players may purchase points with `/ai buy [amount]`. Administrators can use `/ai give <player> <amount>` and `/ai take <player> <amount>`.

Per-minute limits and daily purchase limits are configured under `rate-limit` and `points`. The `daily-limit.enabled` toggle controls point-quota enforcement. Permission-specific quotas, question lengths, and maximum history lengths are grouped under `permissions.groups`:

```yaml
permissions:
  groups:
    default:
      max-points: 10
      max-question-length: 200
      max-history: 5
    vip:
      permission: "mcai.group.vip"
      max-points: 50
      max-question-length: 500
      max-history: 10
    admin:
      permission: "mcai.group.admin"
      max-points: -1
      max-question-length: 0
      max-history: 20
```

`default` is the fallback group. Permission groups are evaluated in file order and the last matching group wins. A group with `max-points: -1` has unlimited daily points; `max-question-length: 0` disables the question-length limit. `max-history` sets the maximum rounds retained for that group in both history modes. In `sliding` mode, older rounds are discarded when the limit is reached. In `accumulate` mode, reaching the limit blocks further requests and prompts the player to start a new conversation. Confirming clears the history; closing or cancelling the prompt does not bypass the limit. The `(current/limit)` suffix shown after each accumulate-mode answer is display-only and is not sent back to the AI or stored in history.

## Build

This is a Maven project requiring Java 21. From the project root:

```bash
mvn clean verify
```

The build creates:

```text
target/mcai-1.0.1.jar
target/mcai-1.0.1-sources.jar
target/mcai-1.0.1-javadoc.jar
```

Maven coordinates:

```xml
<dependency>
    <groupId>cn.xiaobingkuai123</groupId>
    <artifactId>mcai</artifactId>
    <version>1.0.1</version>
</dependency>
```

Paper API, Vault, Floodgate, and Cumulus are `provided` dependencies supplied by the server or optional plugins. Paper loads Gson using the `libraries` section in `plugin.yml`.

## Security

- Do not commit or share real API keys.
- Review the privacy policy and billing terms of each provider.
- Configure request limits to control API usage.
- Use HTTPS API endpoints whenever possible.

## License

McAI is licensed under the GNU General Public License v3.0. See [`LICENSE`](LICENSE).

## 公共 AI（Public AI）

玩家可在聊天开头输入 `@AI 问题`，或使用 `/ai public chat <问题>`。
两个入口共享一个公共会话、限流和扣费流程。正在购买或输入私人问题时，原输入优先。
公共提问只展示原问题；玩家上下文不会直接广播。

- 使用权限：`mcai.use` 与 `mcai.public.use`（后者默认 true）。
- 管理权限：`mcai.admin`。`/ai public clear` 清空公共历史并解除锁定；
  `/ai public setname <名称>` 修改并保存触发名称（可输入 @ 前缀，最多 24 个 Unicode code points）。
- `/ai clear` 仍只清空个人历史。Java 主菜单提供个人/公共历史选择和二次确认，公共选项仅管理员可操作。
- `/ai reload` 重载公共配置和 adapter，不主动解除 LOCKED。

`config.yml` 的 `public-ai` 独立控制 provider、model、system-prompt、cost-points、thinking、streaming 和 history。
凭据和 API 类型仍读取 `api.<provider>`，不重复存储 key。费用允许 0，不允许负数。
`thinking.enabled=false` 不启用供应商 thinking；不支持的能力由现有 adapter 过滤。
只公开 Anthropic summarized thinking 和允许列表中 OpenAI Responses 的 reasoning summary；
DeepSeek reasoning_content、Gemini thought 和 Chat Completions 原始 reasoning 不公开。

`streaming.enabled=true` 时按 `buffer-code-points` 与 `flush-interval-ms` 缓冲输出。
`audience=all` 向全服实时发送；`audience=asker` 只向提问者实时发送，其他玩家在完成后收到完整结果。
关闭 streaming 后，全服只收到最终结果。提问者离线不取消公共请求。

历史 `mode=sliding` 按 `max-rounds` 滑动；`accumulate` 在成功完成最后一轮后检查上限：
无在线管理员自动开始新会话；有管理员则打开决策 GUI，第一个有效决策生效。
接受开始新会话；拒绝或 60 秒真实时间超时进入 LOCKED；PENDING 最后一名受邀管理员退出自动开始新会话。
LOCKED 不因管理员退出解锁，只能明确清空公共历史。

公共历史持久化由 `public-ai.history.persistence.enabled` 独立控制，文件为 `history/public-ai.yml`。
锁定单独保存为 `public-ai-state.yml`，即使历史持久化关闭也保留，均使用临时文件和原子替换。
重启时只加载，不打开管理员 GUI；下一次 submit 才检查 capped。

`player-context-template` 内置支持 `{player}`。安装可选 PlaceholderAPI 后反射解析 `%placeholder%`；
没有安装时仍可使用，未解析占位符会移除，上下文清理控制字符并限制 500 code points。

Paper 全局操作使用主线程；Folia 使用 GlobalRegionScheduler 协调在线玩家快照，
权限、PAPI、GUI 和消息在各玩家 EntityScheduler 执行，HTTP 与公共 YAML 写入在后台执行。
本次开发环境未运行真实 Paper/Folia 服务器；真实跨区域移动、GUI、PAPI、供应商流式联网仍需服务器集成验证。
