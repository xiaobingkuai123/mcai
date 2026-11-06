# McAI

**Current release: 1.0.1**

McAI is an AI conversation plugin for Minecraft Paper and Folia servers. Players can interact with supported AI providers through commands, inventory GUIs, or Bedrock Edition forms.

## What's new in 1.0.1

Compared with the earlier `mcai-main.zip` version, 1.0.1 includes:

- Configurable shared request options under `api.request` (temperature, top-p, retries, streaming, reasoning toggle/budget, and reasoning effort).
- Configurable output-token budgets for each provider's thinking and non-thinking requests. The plugin enforces only the required minimums; the service/model determines the supported maximum.
- Language selection by `language` identifier, loaded from `plugins/McAI/lang/<identifier>.yml`; a missing or invalid selected file falls back to `zh_CN`.
- Expanded conversation history controls, persistence, streaming options, Gemini cached content, and documented configuration defaults.

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
| `chat.system-prompt`, `chat.max-history`, `chat.admin-max-history` | Prompt and history limits. |
| `chat.history.*`, `chat.streaming.*` | History mode/persistence and stream/BossBar settings. |
| `points.*`, `rate-limit.*`, `daily-limit.*` | Point costs, request limits, daily quotas, and question limits. |
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

Per-minute limits, daily point quotas, maximum question length, and daily purchase limits are configured under `rate-limit`, `daily-limit`, and `points`. A group with `max-points: -1` has unlimited daily points.

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
