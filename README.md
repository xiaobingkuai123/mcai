# McAI

McAI 是一个面向 Minecraft Paper/Folia 服务器的 AI 对话插件。玩家可以通过 GUI、聊天命令或 Bedrock 表单向多个 AI 服务商提问，并使用普通、高级和深度思考模式获取回答。

## 功能

- 支持 Paper 与 Folia 1.21，自动选择对应的调度器。
- 支持中文和英文界面，可在配置中切换。
- 内置 DeepSeek、OpenAI Chat Completions、OpenAI Responses、Anthropic Claude 和 Google Gemini 适配器。
- 普通、高级、普通思考和高级思考模式可分别配置服务商、模型、最大 token 数和积分消耗。
- 支持流式输出：请求期间显示 BossBar，并可显示思考过程和最终回答。
- 为玩家和控制台保存可配置长度的对话历史。
- 内置 Java GUI；安装 Floodgate 后为 Bedrock 玩家提供 Cumulus 表单界面。
- 可选 Vault 经济系统，支持购买、给予和扣除每日有效积分。
- 支持每日免费额度、权限组额度、问题长度限制、每分钟请求频率限制和每日购买上限。
- 支持配置热重载、声音反馈、每日积分数据持久化，以及玩家退出时清理进行中的请求。

## 环境要求

- Java 21 或更高版本
- Paper 或 Folia 1.21（或兼容的 1.21 服务端）
- 至少配置一个 AI 服务商的 API Key
- 可选：Vault 与经济插件，用于积分购买
- 可选：Floodgate，用于 Bedrock 表单支持

## 安装

1. 从 Releases 下载 `mcai-<version>.jar`，放入服务器的 `plugins/` 目录。
2. 启动服务器一次，让插件生成 `plugins/McAI/config.yml`。
3. 在配置文件中填写启用服务商的 API Key，并按需调整模型、费用和限制。
4. 重启服务器，或执行 `/ai reload`（需要 `mcai.admin`）重新加载配置。

API Key 只保存在服务器本地配置中。不要把填写过真实密钥的配置文件提交到公开仓库。

## 配置

完整默认配置位于 [`src/main/resources/config.yml`](src/main/resources/config.yml)。常用配置项如下：

| 配置路径 | 作用 |
| --- | --- |
| `api.normal-provider` / `api.pro-provider` | 普通和高级模式使用的服务商 |
| `api.<provider>.key` | 对应服务商的 API Key |
| `api.<provider>.*-model` | 各模式使用的模型 |
| `chat.system-prompt` | 发给模型的系统提示词 |
| `chat.max-history` / `admin-max-history` | 对话历史保留轮数 |
| `chat.streaming.*` | 流式 BossBar 与思考内容显示选项 |
| `points.*` | 模式积分消耗、购买价格和每日购买上限 |
| `rate-limit.*` / `daily-limit.*` | 请求频率和每日额度限制 |
| `sounds.*` | 游戏声音反馈 |

仓库中的默认配置只包含示例密钥，如 `sk-xxx` 和 `AIza...`，不能用于实际请求。

## 命令

玩家执行 `/ai` 可以打开主菜单，也可以直接使用：

```text
/ai help
/ai chat <消息>
/ai chat think <消息>
/ai pro chat <消息>
/ai pro chat think <消息>
/ai buy [数量]
/ai clear
/ai give <玩家> <数量>
/ai take <玩家> <数量>
/ai reload
```

控制台支持 `chat`、`pro chat`、`give`、`take`、`clear`、`reload` 和 `help` 子命令。玩家在交互式输入或购买数量时，可输入 `c`、`cancel` 或 `取消` 取消操作。

## 权限

| 权限 | 默认值 | 作用 |
| --- | --- | --- |
| `mcai.use` | 所有人 | 使用插件和 AI 对话 |
| `mcai.admin` | OP | 重载配置、管理积分 |
| `mcai.group.vip` | 否 | 使用 VIP 每日额度 |
| `mcai.group.admin` | OP | 使用管理员每日额度，可配置为不限 |

## 构建

项目使用标准 Maven 构建，源码级别为 Java 21：

```bash
mvn clean verify
```

构建产物包括：

- `target/mcai-1.0.0.jar`：服务器插件
- `target/mcai-1.0.0-sources.jar`：源码制品
- `target/mcai-1.0.0-javadoc.jar`：Javadoc 制品

Maven 坐标为：

```xml
<dependency>
    <groupId>cn.xiaobingkuai123</groupId>
    <artifactId>mcai</artifactId>
    <version>1.0.0</version>
</dependency>
```

Paper API、Vault、Floodgate 和 Cumulus 以 `provided` 方式参与编译，由服务器或对应插件提供；Gson 由 `plugin.yml` 的 `libraries` 配置加载。

## AI 服务商

- DeepSeek 使用 DeepSeek Chat API。
- OpenAI 支持 Chat Completions 和 Responses 两种 API 类型，也可用于兼容 OpenAI Chat Completions 格式的服务。
- Anthropic 使用 Messages API。
- Gemini 使用 Generative Language API。

网络请求在异步线程执行。请根据服务商的计费、速率限制和数据处理政策配置超时时间和使用限制。

## 许可证

本项目以 GNU General Public License v3.0 发布，详见 [`LICENSE`](LICENSE)。
