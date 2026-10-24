# McAI

McAI is an AI conversation plugin for Minecraft Paper and Folia servers.

Players can interact with multiple AI providers through commands, inventory GUIs, or Bedrock Edition forms. The plugin supports normal conversations, advanced conversations, reasoning modes, streaming responses, conversation history, point-based usage limits, and optional Vault economy integration.

## Features

- Supports Paper and Folia 1.21.
- Automatically detects and uses the appropriate Bukkit or Folia scheduler.
- Supports Chinese and English interfaces.
- Supports multiple AI providers:
  - DeepSeek
  - OpenAI Chat Completions
  - OpenAI Responses API
  - Anthropic Claude
  - Google Gemini
- Supports four conversation modes:
  - Normal
  - Normal Thinking
  - Pro
  - Pro Thinking
- Supports streaming responses with BossBar progress display.
- Optionally displays reasoning content after a thinking request completes.
- Stores configurable conversation history for players and the console.
- Provides an inventory GUI for Java Edition players.
- Provides Cumulus form interfaces for Bedrock Edition players when Floodgate is installed.
- Supports Vault economy integration for purchasing AI points.
- Supports point rewards and deductions through commands.
- Supports daily usage limits and permission-based quotas.
- Supports per-minute request rate limiting.
- Supports configurable question length limits.
- Supports configurable AI provider, model, token limit, timeout, and system prompt.
- Supports runtime configuration reload.
- Saves daily point usage data.
- Cleans up active requests when players disconnect or the plugin is disabled.

## Requirements

- Java 21 or later
- Paper or Folia 1.21
- At least one configured AI provider API key

### Optional Dependencies

- Vault and an economy plugin for point purchases
- Floodgate for Bedrock Edition form support

## Installation

1. Download the latest `mcai-<version>.jar` from the Releases page.
2. Put the file into the server's `plugins/` directory.
3. Start the server once.
4. Open `plugins/McAI/config.yml`.
5. Configure at least one AI provider API key.
6. Restart the server, or use `/ai reload`.

Never commit a configuration file containing real API keys to a public repository.

## Configuration

The complete default configuration is available at:

```text
src/main/resources/config.yml
```

The most important configuration sections are:

| Configuration Path | Description |
| --- | --- |
| `api.normal-provider` | Provider used by the normal modes |
| `api.pro-provider` | Provider used by the Pro modes |
| `api.<provider>.key` | API key for the selected provider |
| `api.<provider>.*-model` | Models used by each conversation mode |
| `api.timeout-seconds` | HTTP connection timeout |
| `chat.system-prompt` | System prompt sent to the AI provider |
| `chat.max-history` | Conversation history limit for regular players |
| `chat.admin-max-history` | Conversation history limit for administrators |
| `chat.streaming.*` | Streaming and BossBar settings |
| `points.*` | Point costs and purchase settings |
| `rate-limit.*` | Per-minute request rate limiting |
| `daily-limit.*` | Daily quota and question length settings |
| `sounds.*` | Sound feedback settings |

The default configuration contains placeholder API keys only. Replace them with valid keys before using the plugin.

## Supported Providers

### DeepSeek

DeepSeek uses the DeepSeek Chat API and supports both regular and reasoning models.

### OpenAI

OpenAI supports both:

- Chat Completions API
- Responses API

The OpenAI adapter may also work with compatible third-party Chat Completions services. Check the provider's API documentation when configuring the base URL and model names.

### Anthropic

Anthropic uses the Messages API and supports configurable Claude models.

### Google Gemini

Gemini uses the Google Generative Language API.

## Commands

Players can run `/ai` to open the main menu.

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

The following command forms are also supported:

```text
/ai chat
/ai chat think
/ai pro chat
/ai pro chat think
```

When no message is provided, the plugin opens an interactive chat input flow.

Console commands include:

```text
ai help
ai chat <message>
ai chat think <message>
ai pro chat <message>
ai pro chat think <message>
ai give <player> <amount>
ai take <player> <amount>
ai clear
ai reload
```

Players can enter `c`, `cancel`, or `取消` to cancel an interactive input flow.

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `mcai.use` | Everyone | Use the plugin and send AI requests |
| `mcai.admin` | Operators | Reload configuration and manage points |
| `mcai.group.vip` | Disabled | Use the VIP daily quota |
| `mcai.group.admin` | Operators | Use the administrator daily quota |

## Point System

Each conversation mode can have a different point cost:

| Mode | Configuration Key |
| --- | --- |
| Normal | `points.normal-cost` |
| Normal Thinking | `points.normal-think-cost` |
| Pro | `points.pro-cost` |
| Pro Thinking | `points.pro-think-cost` |

When Vault and an economy plugin are available, players can purchase points with:

```text
/ai buy [amount]
```

Administrators can manage player points with:

```text
/ai give <player> <amount>
/ai take <player> <amount>
```

## Rate Limits and Quotas

McAI supports multiple usage controls:

- Per-minute request limits
- Daily point limits
- Permission-based daily quotas
- Maximum question length
- Maximum daily purchase amount

These settings can be configured in:

```yaml
rate-limit:
daily-limit:
points:
```

Administrators with the `mcai.group.admin` permission can be configured with unlimited daily points by setting:

```yaml
max-points: -1
```

## Build

This is a standard Maven project using Java 21.

```bash
mvn clean verify
```

The build produces:

```text
target/mcai-1.0.0.jar
target/mcai-1.0.0-sources.jar
target/mcai-1.0.0-javadoc.jar
```

The Maven coordinates are:

```xml
<dependency>
    <groupId>cn.xiaobingkuai123</groupId>
    <artifactId>mcai</artifactId>
    <version>1.0.0</version>
</dependency>
```

The Paper API, Vault, Floodgate, and Cumulus dependencies are used with `provided` scope because they are supplied by the Minecraft server or optional plugins.

Gson is loaded by Paper through the `libraries` section in `plugin.yml`.

## Security Notes

- Do not commit real API keys.
- Do not share your server's configured `config.yml`.
- Review the privacy policy and billing terms of each AI provider.
- Configure request limits to prevent unexpected API usage.
- Use HTTPS API endpoints whenever possible.

## License

McAI is licensed under the GNU General Public License v3.0.

See [`LICENSE`](LICENSE) for the complete license text.
