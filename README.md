# McAI

[![Latest release](https://img.shields.io/github/v/release/xiaobingkuai123/mcai)](https://github.com/xiaobingkuai123/mcai/releases/latest)

**Latest version: [1.0.2](https://github.com/xiaobingkuai123/mcai/releases/tag/v1.0.2)**

McAI provides private and shared public AI conversations for Minecraft Paper and Folia servers, with commands, Java Edition menus, and Floodgate Bedrock Edition forms.

## What's new in 1.0.2

Version **1.0.1** is represented by commit [`2c342c4`](https://github.com/xiaobingkuai123/mcai/commit/2c342c4). The following changes describe **1.0.2 compared with that 1.0.1 baseline**, including changes made after that commit:

- Added public AI with a shared conversation, independent provider/model, prompt, point cost, history, administrator session decisions, and BossBar previews.
- Reorganized configuration into `ai.adapters`, `ai.personal`, and `ai.public`. Each private mode can select its own model and thinking settings. Failed configuration validation retains the previous valid settings.
- Fixed DeepSeek URL construction for both the official root and `/v1` base URLs while preserving proxy prefixes. The bundled DeepSeek mode now uses `deepseek-flash`.
- Added model capability validation for Anthropic extended/adaptive thinking and Sonnet 5.5 `between_tools`, display validation, sampling restrictions, and signature preservation.
- Added unified completion states: completed, incomplete, refused, blocked, paused, unsupported, and failed. Valid refusals can be displayed and saved; incomplete responses cannot become complete history rounds.
- Fixed final streaming Usage reception after Chat Completions finish reasons, Responses refusal events, Anthropic cumulative Usage, and Gemini prompt feedback/status handling.
- Added complete Token Usage reporting and corrected Anthropic input/cache accounting. Bilingual cache logs can be disabled with `ai.adapters.request.log-cache-usage`.
- Protected history commits and delivery against logout, clear, and reconnect races. Versioned persistence prevents stale snapshots from restoring deleted history.
- Improved retries, console round limits, Folia scheduling, shutdown cleanup, language fallback, configuration comments, and regression tests.

## Requirements

- Java 21 or later.
- Paper or Folia 1.21.
- An API key for each provider selected by your configuration.
- Optional: Vault plus an economy plugin for point purchases; Floodgate for Bedrock forms; PlaceholderAPI for public player context.

## Installation and upgrade

1. Download [mcai-1.0.2.jar](https://github.com/xiaobingkuai123/mcai/releases/download/v1.0.2/mcai-1.0.2.jar).
2. Stop the server and back up `plugins/McAI/`, including configuration, histories, and point data.
3. Replace the old plugin JAR. Keep only one McAI JAR in `plugins/`.
4. For a new installation, start the server to generate the configuration, then configure credentials and models in `plugins/McAI/config.yml`.
5. **Upgrading from 1.0.1 requires configuration migration.** Replace the old `api.*`, `chat.*`, and `public-ai.*` structure with `ai.adapters.*`, `ai.personal.*`, and `ai.public.*`. Follow the [default configuration](src/main/resources/config.yml) and copy your credentials, model IDs, and preferences into the new structure. Do not overwrite your only copy of a configuration containing API keys.
6. Start the server. Later configuration changes can use `/ai reload`; replacing the plugin JAR requires a restart.

History YAML retains its existing format. Always back up before upgrading. Do not publish real API keys.

## Configuration

See [config.yml](src/main/resources/config.yml) for annotated settings. Edit `plugins/McAI/config.yml` on your server.

| Path | Purpose |
| --- | --- |
| `language` | Loads `lang/<identifier>.yml`; bundled languages are `zh_CN` and `en_US`. Missing/invalid resources fall back to Chinese. |
| `ai.adapters.<provider>` | Credentials, Base URL, API type, and provider-specific options. |
| `ai.adapters.request` | Timeout, retries, global streaming switch, and sampling where supported. |
| `ai.adapters.request.log-cache-usage` | Bilingual cache-log switch, enabled by default. Does not disable caching or Token Usage logs. |
| `ai.personal.modes` | Independent settings for `normal`, `normal-thinking`, `advanced`, and `advanced-thinking`. |
| `ai.personal.prompt.system` | Private system prompt. |
| `ai.personal.history` | Private history mode and persistence. Player round limits come from permission-group `max-history`. |
| `ai.personal.console` | Console history limit and request/response logging. |
| `ai.personal.streaming` | Private previews, thinking display, and non-streaming answer delay. |
| `ai.public` | Public trigger, provider/model, cost, prompt, context, thinking, and shared history. |
| `points`, `rate-limit`, `daily-limit` | Private-mode charges, point purchases, request frequency, and daily quotas. |
| `permissions.groups` | Per-group daily points, question length, and private history limit. |
| `sounds` | Interaction sounds. |

### Providers and endpoints

| Provider | API type | Example Base URL | Request path |
| --- | --- | --- | --- |
| `openai` | `responses` | `https://api.openai.com` | `/v1/responses` |
| `openai` | `chat` | `https://api.openai.com` | `/v1/chat/completions` |
| `deepseek` | `chat` | `https://api.deepseek.com` | `/chat/completions` |
| `anthropic` | `messages` | `https://api.anthropic.com` | `/v1/messages` |
| `gemini` | `generate-content` | `https://generativelanguage.googleapis.com` | `/v1beta/models/<model>:generateContent` |

Do not include the complete chat request path in a Base URL. An explicit DeepSeek `/v1` or proxy prefix is preserved. A gateway must implement the selected protocol; changing the URL does not convert protocols. Gemini uses the Developer API and does not implement continuationToken auto-continuation from other Google products.

### Thinking and display

Each private mode and public AI has its own `thinking.enabled`, `thinking.strength`, `thinking.display`, and `output.max-tokens`.

- `effort` is used by OpenAI, DeepSeek, and supported Anthropic models, usually with `low`, `medium`, or `high`.
- `budget` supplies a token budget for traditional Anthropic and Gemini 2.5 models; `level` is used for Gemini 3 models.
- `none` hides thinking; `summary` displays official summaries; `reasoning` displays thinking text exposed by the protocol.
- DeepSeek accepts `reasoning` or `none`. Gemini and Anthropic accept `summary` or `none`. Anthropic raw chain-of-thought is unavailable, so `reasoning` is rejected.
- OpenAI summaries require Responses API, the summary feature switch, and an exact model match in `supported-models`.
- Traditional Anthropic models use budgets; supported 4.6 and newer models use adaptive thinking. Sonnet 5.5 low thinking uses `between_tools`. Models that cannot disable thinking use a supported low-thinking configuration. Unknown capabilities produce a configuration error.
- Display choices preserve opaque Anthropic signatures and Gemini Thought Signatures for history replay; signatures are never display text.

Token limits are not character limits. Thinking may consume the output budget. Truncated answers are not saved as completed rounds.

### Streaming, completion, and usage

Private streams use BossBar previews and optional thinking text; final answers are sent after protocol completion is checked. Public stream deltas are used only for BossBar previews, followed by a broadcast of the final answer. Public `audience: asker` restricts previews, not the final broadcast.

Chat Completions `[DONE]` indicates transport termination. Model finish reasons, terminal events, and Usage are handled separately. Tool-call loops are not implemented; tool-call results cannot become ordinary completed text rounds. Failed and incomplete requests follow the existing failure/refund path. Valid refusals do not trigger empty-answer refunds.

Token Usage logs distinguish missing values from reported zeros and include input, output, total, cache, reasoning, and completeness fields. Anthropic total input includes ordinary input, cache creation, and cache reads.

## Commands and permissions

```text
/ai
/ai help
/ai chat <question>
/ai chat think <question>
/ai pro chat <question>
/ai pro chat think <question>
/ai buy [amount]
/ai clear
/ai public chat <question>
/ai public clear
/ai public setname <name>
/ai give <player> <amount>
/ai take <player> <amount>
/ai reload
```

Players open the menu with `/ai`. Enter `c` or `cancel` to cancel interactive input. The console supports conversations, history clearing, public chat, and administration, but cannot purchase points.

| Permission | Default | Purpose |
| --- | --- | --- |
| `mcai.use` | Everyone | Plugin and private AI access. |
| `mcai.public.use` | Everyone | Public AI access, also requiring `mcai.use`. |
| `mcai.admin` | Operators | Reload, point administration, and public session management. |
| `mcai.group.vip` | Disabled | VIP configuration group. |
| `mcai.group.admin` | Operators | Administrator configuration group. |

## History and public sessions

Private histories are isolated by player; the console has a separate history. `sliding` drops the oldest rounds. `accumulate` prompts for a new conversation when the permission-group limit is reached. `max-history: 0` retains no private rounds. Displayed round-count suffixes are not model history.

Clearing private history invalidates that player's old requests and deletes existing files even when persistence is disabled. Stale saves cannot overwrite a clear or newer reconnect history. Logout uses coordinated synchronous persistence, so it may wait for one disk write.

Public AI is triggered by `@AI <question>` by default. `/ai public setname <name>` changes it. Public costs, prompts, and histories are separate from private sessions; the asker's logout does not cancel a public request. Context templates support `{player}` and optional PlaceholderAPI placeholders; context is not broadcast as the original question.

When public `accumulate` history reaches its limit, a new session starts automatically if no administrators are online. Otherwise administrators receive a decision menu. Acceptance resets the session; rejection or timeout locks it. The last invited administrator leaving can trigger a reset. `/ai public clear` clears and unlocks the public session; `/ai clear` clears personal history. Reloading does not deliberately unlock an existing lock.

Public history uses `history/public-ai.yml`. Disabling persistence or clearing public history deletes the old file without affecting private histories. The public lock is stored separately in `public-ai-state.yml`.

## Points and limits

Private modes use `points.*-cost`; public requests use `ai.public.cost-points`. Vault and an economy plugin enable point purchases. Administrators can use give/take to adjust points.

Permission groups are matched in file order; the last matching group wins, with `default` as fallback. `max-points: -1` means unlimited daily points. `max-question-length: 0` means unlimited question length. Purchase limits and request frequency are configured separately.

## Build and validation

Using Java 21, run:

```bash
mvn clean verify
```

Artifacts:

```text
target/mcai-1.0.2.jar
target/mcai-1.0.2-sources.jar
target/mcai-1.0.2-javadoc.jar
```

Paper API, Vault, Floodgate, and Cumulus are provided dependencies. Gson is loaded through `plugin.yml` libraries.

The current suite contains 116 passing tests covering request construction, SSE status and Usage, refusal/truncation, signature replay, persistence races, round limits, output scheduling, and language fallback. HTTP/SSE fixtures and scheduler tests do not replace real Paper/Folia server integration testing.

## License

[GNU General Public License v3.0](LICENSE).
