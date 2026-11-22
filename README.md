# McAI

**Current release: 1.0.2**

McAI 为 Minecraft Paper 和 Folia 服务器提供私人 AI 与公共 AI 对话。玩家可以使用命令、Java 版菜单和 Floodgate 基岩版表单发起请求。

## 1.0.2 更新

相较于提交 [`2c342c4`](https://github.com/xiaobingkuai123/mcai/commit/2c342c4)，本版本包含以下更新：

- 新增公共 AI：`@AI 问题` 和 `/ai public chat <问题>` 共用公共会话，具有独立模型、提示词、费用、历史及管理员会话决策。
- 配置统一为 `ai.adapters`、`ai.personal`、`ai.public`，四种私人模式分别选择厂商、模型、思考强度和展示方式。配置校验失败保留上一次有效配置。
- 修复 DeepSeek URL 拼接，支持官方裸域名与 `/v1` 两种写法，保留代理路径前缀；默认 DeepSeek 模型更新为 `deepseek-flash`。
- Anthropic 按模型能力选择传统 extended thinking、adaptive 或 Sonnet 5.5 的 `between_tools`，校验展示方式并限制采样参数，保留 Thinking 签名。
- 统一完成、截断、拒绝、拦截、暂停、未支持及失败状态。拒绝可显示并保存；截断或其他非完整状态不会作为完整回答提交历史。
- 修复 OpenAI Chat Completions 在 finish reason 后遗漏 Usage 的问题，支持 Responses 拒绝事件、Anthropic 累计 Usage 和 Gemini promptFeedback。
- 新增完整 Token Usage 统计与日志，修正 Anthropic 输入及缓存计算。缓存日志中英双语显示，可通过 `ai.adapters.request.log-cache-usage` 关闭。
- 加强退出、清空历史和重新连接时的请求失效保护；版本化快照协调保存与删除，防止旧任务恢复已清空的历史。
- 改善请求重试、控制台轮数限制、Folia 调度、关闭清理和语言回退；补充协议、持久化及输出回归测试。

## 环境要求

- Java 21 或更高版本。
- Paper 或 Folia 1.21。
- 至少一个可用的厂商 API Key。
- 可选：Vault 与经济插件用于购买点数；Floodgate 用于基岩版表单；PlaceholderAPI 用于公共玩家上下文。

## 安装与升级

1. 从 [Releases](https://github.com/xiaobingkuai123/mcai/releases) 下载 `mcai-1.0.2.jar`。
2. 停止服务器，备份 `plugins/McAI/`，尤其是配置、历史和点数数据。
3. 替换 `plugins/` 内旧 JAR，保留一个 McAI 插件 JAR。
4. 首次安装启动后，编辑 `plugins/McAI/config.yml`，填写连接凭据和所选模式的模型。
5. **从旧配置结构升级时需要迁移**：旧 `api.*`、`chat.*`、`public-ai.*` 已改为 `ai.adapters.*`、`ai.personal.*`、`ai.public.*`。请参照[默认配置](src/main/resources/config.yml)建立新配置，再复制自己的密钥、模型和偏好；不要直接沿用旧结构。
6. 启动服务器。后续配置修改可使用 `/ai reload`；升级插件 JAR 需要重启。

不要公开包含真实密钥的配置文件。历史 YAML 保留现有版本格式，升级前仍应备份。

## 配置

完整说明见 [`src/main/resources/config.yml`](src/main/resources/config.yml)。运行时修改 `plugins/McAI/config.yml`。

| 路径 | 用途 |
| --- | --- |
| `language` | 选择 `lang/<identifier>.yml`，默认 `zh_CN`，也内置 `en_US`；无效或缺失资源回退至中文。 |
| `ai.adapters.<provider>` | API Key、Base URL、协议及厂商选项。 |
| `ai.adapters.request` | 超时、重试、流式总开关及支持时发送的采样参数。 |
| `ai.adapters.request.log-cache-usage` | 缓存统计日志开关，默认开启；不影响缓存本身或 Token Usage 日志。 |
| `ai.personal.modes` | `normal`、`normal-thinking`、`advanced`、`advanced-thinking` 的独立模型与思考配置。 |
| `ai.personal.prompt.system` | 私人系统提示词。 |
| `ai.personal.history` | 私人历史模式与持久化；玩家实际轮数由权限组 `max-history` 决定。 |
| `ai.personal.console` | 控制台历史上限与请求/回答日志。 |
| `ai.personal.streaming` | 私人 BossBar、思考展示与非流式回答延迟。 |
| `ai.public` | 公共触发词、连接选择、模型、费用、提示词、上下文、思考及共享历史。 |
| `points`、`rate-limit`、`daily-limit` | 私人模式费用、购买价格、请求频率及每日额度设置。 |
| `permissions.groups` | 权限组每日点数、问题长度与私人历史轮数。 |
| `sounds` | 交互音效。 |

### 厂商与地址

| provider | api-type | Base URL 示例 | 请求接口 |
| --- | --- | --- | --- |
| `openai` | `responses` | `https://api.openai.com` | `/v1/responses` |
| `openai` | `chat` | `https://api.openai.com` | `/v1/chat/completions` |
| `deepseek` | `chat` | `https://api.deepseek.com` | `/chat/completions` |
| `anthropic` | `messages` | `https://api.anthropic.com` | `/v1/messages` |
| `gemini` | `generate-content` | `https://generativelanguage.googleapis.com` | `/v1beta/models/<model>:generateContent` |

Base URL 不填写完整聊天请求路径。DeepSeek 显式配置 `/v1` 时保留该前缀；代理自定义路径同样保留。兼容网关需要支持所选协议，修改地址不会自动转换协议。Gemini 使用 Developer API，不实现其他 Google 产品的 continuationToken 自动续写。

### 思考与展示

每个私人模式和公共 AI 使用各自的 `thinking.enabled`、`thinking.strength`、`thinking.display`，以及 `output.max-tokens`。

- `effort`：OpenAI、DeepSeek 与支持 effort 的 Anthropic 型号；常用 `low`、`medium`、`high`。
- `budget`：传统 Anthropic 和 Gemini 2.5 的 Token 预算；`level` 用于 Gemini 3 型号。
- `none` 隐藏思考展示；`summary` 显示官方摘要；`reasoning` 显示协议提供的思考文本。
- DeepSeek 使用 `reasoning` 或 `none`；Gemini、Anthropic 使用 `summary` 或 `none`。Anthropic 不提供原始思维链，`reasoning` 配置会被拒绝。
- OpenAI 官方摘要仅适用于 Responses API，并要求开启 `responses.reasoning-summary.enabled`、模型精确匹配 `supported-models`。
- Anthropic 传统型号使用预算；4.6 及支持的更新型号使用 adaptive。Sonnet 5.5 的低思考配置使用 `between_tools`；不可关闭思考的型号使用能力允许的低思考配置。未知型号能力会触发明确配置错误。
- 思考展示不改变历史签名。Anthropic signature 和 Gemini thoughtSignature 作为原始元数据保存及回放，不作为展示文本。

Token 上限不是字符数，思考可能占用输出预算。应按所选接口支持的值配置；截断回答不会被当作完整轮次保存。

### 流式状态与统计

私人流式请求使用 BossBar 预览，可按配置显示思考文本；最终回答在协议确认后发送。公共流式增量只用于 BossBar，最终回答统一广播，`audience: asker` 仅限制预览对象。

SSE `[DONE]` 只代表 Chat Completions 传输结束。插件分别检查模型结束原因、协议终止事件和 Usage，不将任意结束原因视为成功。工具调用循环尚未实现，相关状态不会提交普通文本轮次。请求失败或非完整响应走原失败退款流程；有效拒绝不按空回答退款。

Token Usage 日志区分未报告与零，包含输入、输出、总量、缓存、推理及统计完整性。Anthropic 总输入为普通输入、缓存创建和缓存读取之和。厂商未报告的数据不会伪装为零。

## 命令与权限

```text
/ai
/ai help
/ai chat <问题>
/ai chat think <问题>
/ai pro chat <问题>
/ai pro chat think <问题>
/ai buy [数量]
/ai clear
/ai public chat <问题>
/ai public clear
/ai public setname <名称>
/ai give <玩家> <数量>
/ai take <玩家> <数量>
/ai reload
```

玩家使用 `/ai` 打开菜单；交互输入时可用 `c`、`cancel` 或 `取消` 取消。控制台支持聊天、历史清除、公共聊天及管理命令，不能购买点数。

| 权限 | 默认 | 用途 |
| --- | --- | --- |
| `mcai.use` | 所有人 | 使用插件与私人 AI |
| `mcai.public.use` | 所有人 | 使用公共 AI，同时需要 `mcai.use` |
| `mcai.admin` | OP | 重载、管理点数、清除和管理公共会话 |
| `mcai.group.vip` | 关闭 | 匹配 VIP 配置组 |
| `mcai.group.admin` | OP | 匹配管理员配置组 |

## 历史与公共会话

私人历史按玩家隔离，控制台使用独立历史。`sliding` 丢弃最旧轮次；`accumulate` 达到权限组上限后提示开启新会话。`max-history: 0` 不保留私人轮次。界面的轮数后缀不进入模型历史。

清除私人历史会使该玩家旧请求失效，即使关闭持久化，也会删除已有文件。旧保存快照不能覆盖删除或重新连接后的新历史。退出保存使用协调的同步提交，因此退出事件可能等待一次磁盘写入。

公共 AI 默认由 `@AI 问题` 触发，可使用 `/ai public setname <名称>` 修改。其费用、提示词和历史独立于私人会话，提问者离线不取消公共请求。上下文模板支持 `{player}`，安装 PlaceholderAPI 后可解析占位符；上下文不会作为原问题直接广播。

公共 `accumulate` 达到上限时，无在线管理员则开始新会话；有管理员则弹出决策菜单。接受会开始新会话，拒绝或超时会锁定；最后一名受邀管理员退出时可自动重置。`/ai public clear` 清除公共历史并解锁，`/ai clear` 只清除自己的历史。重载不会主动解除已有锁定。

公共历史为 `history/public-ai.yml`，关闭持久化或清除时删除旧文件，不影响私人历史。公共锁定状态独立保存在 `public-ai-state.yml`。

## 点数与限制

私人四种模式分别使用 `points.*-cost`，公共请求使用 `ai.public.cost-points`。Vault 与经济插件可用于购买点数，管理员可通过 give/take 调整。

权限组按文件顺序匹配，最后匹配的组生效；`default` 为回退组。`max-points: -1` 表示不限每日点数，`max-question-length: 0` 表示不限问题长度。购买上限与请求频率分别由 `points.max-buy-per-day` 和 `rate-limit` 设置。

## 构建与验证

使用 Java 21，在项目根目录执行：

```bash
mvn clean verify
```

产物：

```text
target/mcai-1.0.2.jar
target/mcai-1.0.2-sources.jar
target/mcai-1.0.2-javadoc.jar
```

Paper API、Vault、Floodgate 和 Cumulus 为 provided 依赖；Gson 通过 `plugin.yml` 的 libraries 加载。

回归测试覆盖请求构造、SSE 状态与 Usage、拒绝和截断、签名回放、历史竞争、权限轮数、输出调度及语言回退。HTTP/SSE 测试与调度器测试不替代真实 Paper/Folia 服务器集成验证。

## 许可证

[GNU General Public License v3.0](LICENSE)。
