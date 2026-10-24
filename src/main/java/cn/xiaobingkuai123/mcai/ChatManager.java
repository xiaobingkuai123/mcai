package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.ApiAdapter;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

public class ChatManager {
    private final McAI plugin;
    private final EconomyManager economyManager;

    static class ChatMessage {
        String role;
        String content;
        ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public ChatManager(McAI plugin, EconomyManager economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;
    }

    public void askPlayer(Player player, String question, String mode) {
        UUID playerId = player.getUniqueId();
        final Object requestToken = new Object();

        if (plugin.activePlayerRequests.putIfAbsent(playerId, requestToken) != null) {
            player.sendMessage(plugin.languageManager.get("stream-busy"));
            return;
        }

        boolean streaming = plugin.getConfig().getBoolean("chat.streaming.enabled", true);
        boolean isThinking = mode.endsWith("_think");

        final int maxHist = Math.max(0, plugin.getEffectiveMaxHistory(player));
        final int modeCost = economyManager.getCostForMode(mode);
        final int baseModeCost = economyManager.getCostForMode(mode.replace("_think", ""));
        final int thinkingPremium = Math.max(0, modeCost - baseModeCost);

        final EconomyManager.Charge charge;
        try {
            charge = economyManager.consumePoints(player, mode);
        } catch (RuntimeException e) {
            plugin.activePlayerRequests.remove(playerId, requestToken);
            throw e;
        }

        if (charge == null) {
            plugin.activePlayerRequests.remove(playerId, requestToken);
            return;
        }

        final LinkedList<ChatMessage> history = plugin.conversationHistory.computeIfAbsent(
                playerId, ignored -> new LinkedList<>());

        final PlayerStreamOutput output = streaming ? new PlayerStreamOutput(plugin, player) : null;
        if (output != null) {
            plugin.activeStreamOutputs.put(playerId, output);
            // 请求发起即显示 Boss 条，等待首字节期间也有反馈。
            output.start();
        }

        if (plugin.logToConsole) {
            plugin.getLogger().info("[" + plugin.languageManager.get("log-request-prefix") + "] "
                    + player.getName() + " [" + mode + "]: " + question);
        }

        final int modeCostFinal = modeCost;
        Runnable worker = () -> {
            try {
                ApiAdapter.AdapterResponse response = callApi(
                        playerId, question, mode, maxHist,
                        output == null ? null : output::accept);

                if (output != null && output.isClosed()) {
                    throw new CancellationException("Player stream closed");
                }

                String answer = response.content();
                String reasoning = response.reasoning();

                if (answer == null || answer.isBlank()) {
                    throw new IOException("AI returned an empty answer");
                }

                if (isThinking && (reasoning == null || reasoning.isBlank()) && thinkingPremium > 0) {
                    int refunded = economyManager.refundPoints(charge, thinkingPremium);
                    if (refunded > 0) {
                        plugin.schedulerAdapter.runTask(player, () -> {
                            if (player.isOnline()) {
                                player.sendMessage(plugin.languageManager.get(
                                        "refund-no-reasoning", "points", String.valueOf(refunded)));
                            }
                        });
                    }
                }

                if (plugin.conversationHistory.get(playerId) == history) {
                    synchronized (history) {
                        history.add(new ChatMessage("user", question));
                        history.add(new ChatMessage("assistant", answer));
                        long retainedMessages = (long) maxHist * 2;
                        while (history.size() > retainedMessages) history.removeFirst();
                    }
                }

                if (plugin.logToConsole) {
                    String displayText = (reasoning != null && !reasoning.isBlank())
                            ? reasoning + "\n" + answer : answer;
                    plugin.getLogger().info("[" + plugin.languageManager.get("log-response-prefix")
                            + "] → " + player.getName() + ": "
                            + ChatColor.stripColor(displayText));
                }

                if (output != null) {
                    output.finish(answer, reasoning);
                } else {
                    final String finalReasoning = reasoning;
                    final String finalAnswer = answer;
                    plugin.schedulerAdapter.runTask(player, () -> {
                        if (!player.isOnline()) return;
                        if (isThinking && finalReasoning != null && !finalReasoning.isBlank()) {
                            plugin.soundManager.playReasoningDone(player);
                            PlayerStreamOutput.sendText(plugin, player,
                                    "reasoning-label", finalReasoning, ChatColor.GRAY);
                            plugin.schedulerAdapter.runTaskLater(player, () -> {
                                if (!player.isOnline()) return;
                                plugin.soundManager.playAnswer(player);
                                PlayerStreamOutput.sendText(plugin, player,
                                        "answer-label", finalAnswer, ChatColor.WHITE);
                            }, Math.max(0, plugin.thinkDelayTicks));
                        } else {
                            plugin.soundManager.playAnswer(player);
                            PlayerStreamOutput.sendText(plugin, player,
                                    "answer-label", finalAnswer, ChatColor.WHITE);
                        }
                    });
                }
            } catch (Exception e) {
                economyManager.refundPoints(charge, modeCostFinal);

                if (!(e instanceof CancellationException)) {
                    plugin.getLogger().warning(plugin.languageManager.get(
                            "log-api-request-exception", "error", String.valueOf(e.getMessage())));
                    String message = playerErrorMessage(e, streaming);
                    if (output != null) {
                        output.fail(message);
                    } else {
                        plugin.schedulerAdapter.runTask(player, () -> {
                            if (player.isOnline()) player.sendMessage(message);
                        });
                    }
                }
            } finally {
                Runnable cleanup = () -> {
                    if (output != null) plugin.activeStreamOutputs.remove(playerId, output);
                    plugin.activePlayerRequests.remove(playerId, requestToken);
                };

                if (!plugin.isEnabled()) {
                    cleanup.run();
                } else {
                    try {
                        plugin.schedulerAdapter.runTask(player, cleanup, cleanup);
                    } catch (RuntimeException schedulingFailure) {
                        cleanup.run();
                    }
                }
            }
        };

        try {
            plugin.schedulerAdapter.runAsync(worker);
        } catch (RuntimeException e) {
            economyManager.refundPoints(charge, modeCost);
            if (output != null) {
                plugin.activeStreamOutputs.remove(playerId, output);
                output.closeNow();
            }
            plugin.activePlayerRequests.remove(playerId, requestToken);
            throw e;
        }
    }

    private String playerErrorMessage(Exception error, boolean streaming) {
        if (error instanceof HttpTimeoutException
                || error instanceof java.util.concurrent.TimeoutException) {
            return plugin.languageManager.get("stream-timeout");
        }
        if (error instanceof AiStreamClient.ApiHttpException) {
            int code = ((AiStreamClient.ApiHttpException) error).statusCode();
            if (code == 401) return plugin.languageManager.get("api-401");
            if (code == 429) return plugin.languageManager.get("api-429");
            return plugin.languageManager.get("api-unknown", "code", String.valueOf(code));
        }
        return plugin.languageManager.get(streaming ? "stream-failed" : "api-error");
    }

    public void askConsole(String question, boolean isPro, boolean isThinking) {
        String mode = isPro ? (isThinking ? "pro_think" : "pro") : (isThinking ? "normal_think" : "normal");
        plugin.schedulerAdapter.runAsync(() -> {
            try {
                ApiAdapter.AdapterResponse response = callApi(McAI.CONSOLE_UUID, question, mode, plugin.adminMaxHistory, null);
                String answer = response.content();
                if (answer == null || answer.isBlank()) {
                    plugin.getLogger().warning("[" + plugin.languageManager.get("log-console-answer-prefix")
                            + "] " + plugin.languageManager.get("console-name") + ": " + answer);
                    return;
                }
                LinkedList<ChatMessage> history = plugin.conversationHistory.computeIfAbsent(McAI.CONSOLE_UUID, k -> new LinkedList<>());
                synchronized (history) {
                    history.add(new ChatMessage("user", question));
                    history.add(new ChatMessage("assistant", answer));
                }
                if (response.reasoning() != null && !response.reasoning().isEmpty() && isThinking) {
                    plugin.getLogger().info("[" + plugin.languageManager.get("log-console-reasoning")
                            + "] \n" + ChatColor.stripColor(response.reasoning()));
                }
                plugin.getLogger().info("[" + plugin.languageManager.get("log-console-answer-prefix")
                        + "] → " + plugin.languageManager.get("console-name") + ": "
                        + ChatColor.stripColor(answer));
            } catch (Exception e) {
                plugin.getLogger().warning(plugin.languageManager.get("log-api-console-error",
                        "error", e.getMessage()));
            }
        });
    }

    private ApiAdapter.AdapterResponse callApi(UUID userId, String question,
            String mode, int effectiveHistory) throws Exception {
        return callApi(userId, question, mode, effectiveHistory, null);
    }

    private ApiAdapter.AdapterResponse callApi(UUID userId, String question,
            String mode, int effectiveHistory,
            Consumer<AiStreamClient.Delta> streamListener) throws Exception {
        boolean isThinking = mode.endsWith("_think");
        boolean isPro = mode.startsWith("pro");

        ApiAdapter adapter = isPro ? plugin.proApiAdapter : plugin.normalApiAdapter;
        String providerName = isPro ? plugin.proProviderName : plugin.normalProviderName;

        var apiSection = plugin.getConfig().getConfigurationSection("api." + providerName);
        if (apiSection == null) {
            throw new IllegalStateException(
                    plugin.languageManager.get("log-provider-section-missing", "provider", providerName));
        }

        String apiKey = apiSection.getString("key", "");
        String apiBaseUrl = apiSection.getString("base-url", "");
        String normalModel = apiSection.getString("normal-model", "");
        String normalThinkModel = apiSection.getString("normal-think-model", normalModel);
        String proModel = apiSection.getString("pro-model", normalModel);
        String proThinkModel = apiSection.getString("pro-think-model", proModel);
        int maxTokensFast = apiSection.getInt("max-tokens-fast", 4096);
        int maxTokensThink = apiSection.getInt("max-tokens-think", 32768);

        String model = isPro
                ? (isThinking ? proThinkModel : proModel)
                : (isThinking ? normalThinkModel : normalModel);
        int maxTokens = isThinking ? maxTokensThink : maxTokensFast;

        if (apiKey == null || apiKey.isBlank() || apiKey.contains("xxx")) {
            throw new IllegalStateException(
                    plugin.languageManager.get("no-api-key", "provider", providerName));
        }

        List<ApiAdapter.Message> apiMessages = new ArrayList<>();
        apiMessages.add(new ApiAdapter.Message("system", plugin.systemPrompt));

        LinkedList<ChatMessage> history = plugin.conversationHistory.get(userId);
        if (history != null) {
            synchronized (history) {
                int userMessageCount = 0;
                for (ChatMessage msg : history) if ("user".equals(msg.role)) userMessageCount++;
                int keepRounds = Math.min(effectiveHistory, userMessageCount);
                int skipRounds = userMessageCount - keepRounds;
                int skipped = 0;
                boolean skipNextAssistant = false;
                for (ChatMessage msg : history) {
                    if ("user".equals(msg.role)) {
                        if (skipped < skipRounds) { skipped++; skipNextAssistant = true; continue; }
                        skipNextAssistant = false;
                    } else if ("assistant".equals(msg.role)) {
                        if (skipNextAssistant) continue;
                    }
                    apiMessages.add(new ApiAdapter.Message(msg.role, msg.content));
                }
            }
        }
        apiMessages.add(new ApiAdapter.Message("user", question));

        HttpRequest request = adapter.buildRequest(apiBaseUrl, apiKey, model, maxTokens, apiMessages, isThinking);

        java.net.http.HttpClient client = plugin.getHttpClient();
        if (client == null) {
            throw new CancellationException("Plugin is shutting down");
        }

        if (streamListener != null) {
            return AiStreamClient.send(client, adapter, request, streamListener);
        }

        HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (httpResponse.statusCode() == 200) {
            return adapter.parseResponse(httpResponse.body());
        }
        String errorMessage;
        if (httpResponse.statusCode() == 401) {
            errorMessage = plugin.languageManager.get("api-401");
        } else if (httpResponse.statusCode() == 429) {
            errorMessage = plugin.languageManager.get("api-429");
        } else {
            errorMessage = plugin.languageManager.get("api-unknown",
                    "code", String.valueOf(httpResponse.statusCode()));
        }
        throw new java.io.IOException(errorMessage);
    }
}
