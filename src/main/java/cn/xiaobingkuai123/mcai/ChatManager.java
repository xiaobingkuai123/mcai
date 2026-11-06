package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.ApiAdapter;
import cn.xiaobingkuai123.mcai.adapter.ApiRequestOptions;
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

        boolean streaming = plugin.apiStreaming && plugin.getConfig().getBoolean("chat.streaming.enabled", true);
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

        final PlayerStreamOutput output = streaming ? new PlayerStreamOutput(plugin, player) : null;
        if (output != null) {
            plugin.activeStreamOutputs.put(playerId, output);
            // Show the BossBar immediately while waiting for the first stream delta.
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

                ConversationHistory memory = conversationMemory(playerId, maxHist);
                List<ApiAdapter.Message> completedRound = new ArrayList<>();
                completedRound.add(new ApiAdapter.Message("user", question));
                if (isAnthropicMode(mode) && response.reasoning() != null && !response.reasoning().isBlank()) {
                    completedRound.add(new ApiAdapter.Message("assistant", response.reasoning(), "thinking", response.reasoningSignature()));
                }
                completedRound.add(new ApiAdapter.Message("assistant", answer));
                memory.addRound(completedRound);
                if (plugin.historyPersistenceEnabled) {
                    try { plugin.historyPersistence.save(playerId, memory); }
                    catch (Exception e) { plugin.getLogger().warning("Unable to save conversation history"); }
                }

                if (plugin.logToConsole) {
                    String displayText = (reasoning != null && !reasoning.isBlank())
                            ? reasoning + "\n" + answer : answer;
                    plugin.getLogger().info("[" + plugin.languageManager.get("log-response-prefix")
                            + "] -> " + player.getName() + ": "
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
                if (plugin.historyMode == ConversationHistory.Mode.ACCUMULATE && memory.capped()) {
                    plugin.schedulerAdapter.runTask(player, () -> {
                        if (player.isOnline()) plugin.openNextRoundPrompt(player);
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

    private static ApiAdapter.CacheUsage extractCacheUsage(String body) {
        try {
            com.google.gson.JsonObject root = new com.google.gson.JsonParser().parse(body).getAsJsonObject();
            com.google.gson.JsonObject usage = usageObject(root);
            if (usage == null) return null;
            com.google.gson.JsonObject details = usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").isJsonObject() ? usage.getAsJsonObject("prompt_tokens_details") : null;
            if (details == null && usage.has("input_tokens_details") && usage.get("input_tokens_details").isJsonObject()) details = usage.getAsJsonObject("input_tokens_details");
            if (details != null && details.has("cached_tokens")) {
                long hit = details.get("cached_tokens").getAsLong();
                return new ApiAdapter.CacheUsage("prompt_tokens_details.cached_tokens", hit, token(usage, "prompt_tokens") - hit);
            }
            if (usage.has("prompt_cache_hit_tokens")) return new ApiAdapter.CacheUsage("prompt_cache_hit_tokens", usage.get("prompt_cache_hit_tokens").getAsLong(), token(usage, "prompt_cache_miss_tokens"));
            if (usage.has("cache_read_input_tokens")) return new ApiAdapter.CacheUsage("cache_read_input_tokens", usage.get("cache_read_input_tokens").getAsLong(), token(usage, "cache_creation_input_tokens"));
            if (usage.has("cachedContentTokenCount")) return new ApiAdapter.CacheUsage("cachedContentTokenCount", usage.get("cachedContentTokenCount").getAsLong(), token(usage, "promptTokenCount") - usage.get("cachedContentTokenCount").getAsLong());
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static com.google.gson.JsonObject usageObject(com.google.gson.JsonObject root) {
        com.google.gson.JsonObject usage = root.has("usage") && root.get("usage").isJsonObject() ? root.getAsJsonObject("usage") : null;
        if (usage == null && root.has("usageMetadata") && root.get("usageMetadata").isJsonObject()) usage = root.getAsJsonObject("usageMetadata");
        if (usage == null && root.has("response") && root.get("response").isJsonObject()) {
            com.google.gson.JsonObject response = root.getAsJsonObject("response");
            usage = response.has("usage") && response.get("usage").isJsonObject() ? response.getAsJsonObject("usage") : null;
            if (usage == null && response.has("usageMetadata") && response.get("usageMetadata").isJsonObject()) usage = response.getAsJsonObject("usageMetadata");
        }
        return usage;
    }

    private static long token(com.google.gson.JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsLong() : 0;
    }

    private void logCacheUsage(ApiAdapter.CacheUsage usage) {
        if (usage == null) {
            plugin.getLogger().info("AI prompt cache: not-reported (field=none)");
            return;
        }
        String state = usage.hitTokens() > 0 ? "hit" : "miss";
        plugin.getLogger().info("AI prompt cache: " + state + " (field=" + usage.field()
                + ", hit=" + usage.hitTokens() + ", miss=" + usage.missTokens() + ")");
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
                ConversationHistory memory = conversationMemory(McAI.CONSOLE_UUID, plugin.adminMaxHistory);
                List<ApiAdapter.Message> completedRound = new ArrayList<>();
                completedRound.add(new ApiAdapter.Message("user", question));
                if (isAnthropicMode(mode) && response.reasoning() != null && !response.reasoning().isBlank()) {
                    completedRound.add(new ApiAdapter.Message("assistant", response.reasoning(), "thinking", response.reasoningSignature()));
                }
                completedRound.add(new ApiAdapter.Message("assistant", answer));
                memory.addRound(completedRound);
                if (plugin.historyPersistenceEnabled) {
                    try { plugin.historyPersistence.save(McAI.CONSOLE_UUID, memory); }
                    catch (Exception e) { plugin.getLogger().warning("Unable to save console conversation history"); }
                }
                if (response.reasoning() != null && !response.reasoning().isEmpty() && isThinking) {
                    plugin.getLogger().info("[" + plugin.languageManager.get("log-console-reasoning")
                            + "] \n" + ChatColor.stripColor(response.reasoning()));
                }
                plugin.getLogger().info("[" + plugin.languageManager.get("log-console-answer-prefix")
                        + "] -> " + plugin.languageManager.get("console-name") + ": "
                        + ChatColor.stripColor(answer));
            } catch (Exception e) {
                plugin.getLogger().warning(plugin.languageManager.get("log-api-console-error",
                        "error", e.getMessage()));
            }
        });
    }

    private boolean isAnthropicMode(String mode) {
        String provider = mode.startsWith("pro") ? plugin.proProviderName : plugin.normalProviderName;
        return "anthropic".equalsIgnoreCase(provider);
    }

    private ConversationHistory conversationMemory(UUID userId, int maxRounds) {
        return plugin.conversationMemories.computeIfAbsent(userId, id -> {
            if (plugin.historyPersistenceEnabled && plugin.historyPersistence != null) {
                return plugin.historyPersistence.load(id, plugin.historyMode, Math.max(0, maxRounds));
            }
            return new ConversationHistory(plugin.historyMode, Math.max(0, maxRounds));
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
        ConversationHistory memory = conversationMemory(userId, effectiveHistory);
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
        int maxTokensFast = TokenBudget.outputTokens(
                apiSection.getInt("max-tokens-fast", 4096));
        int maxTokensThink = TokenBudget.outputTokens(
                apiSection.getInt("max-tokens-think", 32768));

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

        apiMessages.addAll(memory.promptMessages());
        apiMessages.add(new ApiAdapter.Message("user", question));

        String cachedContent = null;
        if (plugin.geminiCachedContentEnabled && adapter instanceof cn.xiaobingkuai123.mcai.adapter.GeminiAdapter
                && plugin.geminiCachedContentManager != null && apiMessages.size() > 1) {
            cachedContent = plugin.geminiCachedContentManager.getOrCreate(apiBaseUrl, apiKey, model,
                    apiMessages.subList(0, apiMessages.size() - 1));
        }
        ApiRequestOptions options = new ApiRequestOptions(plugin.apiTemperature, plugin.apiTopP, maxTokens,
                plugin.apiTimeoutSeconds, plugin.apiMaxRetries,
                streamListener != null && plugin.apiStreaming && plugin.getConfig().getBoolean("chat.streaming.enabled", true),
                isThinking && plugin.thinkingEnabled, plugin.thinkingBudget, plugin.reasoningEffort, cachedContent);
        HttpRequest request = adapter.buildRequest(apiBaseUrl, apiKey, model, apiMessages, options);

        java.net.http.HttpClient client = plugin.getHttpClient();
        if (client == null) {
            throw new CancellationException("Plugin is shutting down");
        }

        if (options.streaming()) {
            ApiAdapter.AdapterResponse streamed = AiStreamClient.send(client, adapter, request, streamListener == null ? delta -> {} : streamListener);
            logCacheUsage(streamed.cacheUsage());
            return streamed;
        }

        HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = httpResponse.statusCode();
        if (status >= 200 && status < 300) {
            String body = httpResponse.body();
            try {
                ApiAdapter.AdapterResponse response = adapter.parseResponse(body);
                ApiAdapter.CacheUsage cacheUsage = extractCacheUsage(body);
                logCacheUsage(cacheUsage);
                return new ApiAdapter.AdapterResponse(response.content(), response.reasoning(), cacheUsage);
            } catch (com.google.gson.JsonParseException | IllegalStateException e) {
                String contentType = httpResponse.headers().firstValue("Content-Type").orElse("unknown");
                plugin.getLogger().warning("AI provider returned an invalid response (content-type="
                        + contentType + ", length=" + body.length() + ")");
                throw new IOException("AI provider returned an invalid response", e);
            }
        }
        String errorMessage;
        if (status == 401) {
            errorMessage = plugin.languageManager.get("api-401");
        } else if (status == 429) {
            errorMessage = plugin.languageManager.get("api-429");
        } else {
            errorMessage = plugin.languageManager.get("api-unknown",
                    "code", String.valueOf(status));
        }
        throw new java.io.IOException(errorMessage);
    }
}
