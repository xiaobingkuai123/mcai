package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.ApiAdapter;
import cn.xiaobingkuai123.mcai.adapter.ApiRequestOptions;
import cn.xiaobingkuai123.mcai.adapter.CacheUsageParser;
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
        plugin.schedulerAdapter.runTask(player, () -> {
            if (player.isOnline()) askPlayerOnEntity(player, question, mode);
        });
    }

    private void askPlayerOnEntity(Player player, String question, String mode) {
        UUID playerId = player.getUniqueId();
        final String playerName = player.getName();
        final Object requestToken = new Object();

        if (plugin.activePlayerRequests.putIfAbsent(playerId, requestToken) != null) {
            player.sendMessage(plugin.languageManager.get("stream-busy"));
            return;
        }

        boolean streaming = plugin.getConfig().getBoolean("ai.adapters.request.streaming", true)
                && plugin.getConfig().getBoolean("ai.personal.streaming.enabled", true);
        boolean isThinking = mode.endsWith("_think");

        if (!economyManager.canStartConversationRound(player)) {
            plugin.activePlayerRequests.remove(playerId, requestToken);
            return;
        }
        final int maxHist = economyManager.getConversationRoundLimit(player);
        final int modeCost = economyManager.getCostForMode(mode);
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
                    + playerName + " [" + mode + "]: " + redactLog(question));
        }

        final int modeCostFinal = modeCost;
        Runnable worker = () -> {
            try {
                ApiAdapter.AdapterResponse response = callApi(
                        playerId, question, mode, maxHist,
                        output == null ? null : output::accept,
                        () -> plugin.isEnabled() && plugin.activePlayerRequests.get(playerId) == requestToken);

                if (output != null && output.isClosed()) {
                    throw new CancellationException("Player stream closed");
                }

                String answer = response.content();
                String reasoning = response.reasoning();

                if (answer == null || answer.isBlank()) {
                    throw new IOException("AI returned an empty answer");
                }

                ConversationHistory memory = conversationMemory(playerId, maxHist);
                List<ApiAdapter.Message> completedRound = new ArrayList<>();
                completedRound.add(new ApiAdapter.Message("user", question));
                if ((isAnthropicMode(mode) || isGeminiMode(mode)) && !response.contentBlocks().isEmpty()) {
                    completedRound.addAll(response.contentBlocks());
                } else {
                    if (isAnthropicMode(mode) && response.reasoning() != null && response.reasoningSignature() != null) {
                        completedRound.add(new ApiAdapter.Message("assistant", response.reasoning(), "thinking", response.reasoningSignature()));
                    }
                    completedRound.add(new ApiAdapter.Message("assistant", answer));
                }
                memory.addRound(completedRound);
                String displayAnswer = answer;
                if (plugin.historyMode == ConversationHistory.Mode.ACCUMULATE) {
                    int roundLimit = maxHist;
                    int roundCount = Math.min(roundLimit, memory.roundCount());
                    displayAnswer += " (" + roundCount + "/" + roundLimit + ")";
                }
                if (plugin.historyPersistenceEnabled) {
                    try { plugin.historyPersistence.save(playerId, memory); }
                    catch (Exception e) { plugin.getLogger().warning("Unable to save conversation history"); }
                }

                if (plugin.logToConsole) {
                    if (displaysThinking(mode) && reasoning != null && !reasoning.isBlank()) {
                        plugin.getLogger().info("[" + plugin.languageManager.get("log-console-reasoning")
                                + "] -> " + playerName + ": " + redactLog(ChatColor.stripColor(reasoning)));
                    }
                    plugin.getLogger().info("[" + plugin.languageManager.get("log-response-prefix")
                            + "] -> " + playerName + ": " + redactLog(ChatColor.stripColor(answer)));
                }

                if (output != null) {
                    output.finish(displayAnswer, displaysThinking(mode) ? reasoning : null);
                } else {
                    final String finalReasoning = reasoning;
                    final String finalAnswer = displayAnswer;
                    plugin.schedulerAdapter.runTask(player, () -> {
                        if (!player.isOnline()) return;
                        if (isThinking && displaysThinking(mode) && finalReasoning != null && !finalReasoning.isBlank()) {
                            plugin.soundManager.playReasoningDone(player);
                            PlayerStreamOutput.sendText(plugin, player,
                                    "reasoning-summary-label", finalReasoning, ChatColor.GRAY);
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
                            "log-api-request-exception", "error", e.getClass().getSimpleName()));
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

    String redactLog(String text) {
        if (text == null) return "";
        String safe = text;
        for (String provider : List.of("deepseek", "openai", "anthropic", "gemini")) {
            String key = plugin.getConfig().getString("ai.adapters." + provider + ".api-key", "");
            if (key != null && !key.isBlank()) safe = safe.replace(key, "[REDACTED]");
        }
        return redactCredentials(safe);
    }

    static String redactCredentials(String text) {
        String quoted = text.replaceAll("(?i)(authorization|api[-_ ]?key|access[-_]?token|token)(\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")", "$1$2[REDACTED]$4");
        return quoted.replaceAll("(?i)(authorization|api[-_ ]?key|access[-_]?token|token)\\s*[:=]\\s*(?:bearer\\s+)?[^\\s,;\\\"]+", "$1=[REDACTED]")
                .replaceAll("(?i)bearer\\s+[^\\s,;\\\"]+", "Bearer [REDACTED]")
                .replaceAll("sk-(?:ant-)?[A-Za-z0-9_-]+|AIza[A-Za-z0-9_-]+", "[REDACTED]");
    }    private void logCacheUsage(ApiAdapter.CacheUsage usage) {
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
            ConversationHistory.Mode historyMode = plugin.historyMode;
            ConversationHistory memory;
            ConsoleRoundLimit.Reservation reservation;
            synchronized (plugin.consoleRoundLimit) {
                memory = conversationMemory(McAI.CONSOLE_UUID, plugin.consoleMaxHistory);
                reservation = plugin.consoleRoundLimit.reserve(
                        memory.roundCount(), plugin.consoleMaxHistory, historyMode);
            }
            if (reservation == null) {
                plugin.getLogger().warning(plugin.languageManager.get(
                        "console-round-limit-reached", "limit", String.valueOf(plugin.consoleMaxHistory)));
                return;
            }
            boolean completed = false;
            boolean reserved = reservation.counted();
            try {
                ApiAdapter.AdapterResponse response = callApi(McAI.CONSOLE_UUID, question, mode, plugin.consoleMaxHistory, null, plugin::isEnabled);
                String answer = response.content();
                if (answer == null || answer.isBlank()) {
                    plugin.getLogger().warning("AI returned an empty answer");
                    return;
                }
                List<ApiAdapter.Message> completedRound = new ArrayList<>();
                completedRound.add(new ApiAdapter.Message("user", question));
                if ((isAnthropicMode(mode) || isGeminiMode(mode)) && !response.contentBlocks().isEmpty()) {
                    completedRound.addAll(response.contentBlocks());
                } else {
                    if (isAnthropicMode(mode) && response.reasoning() != null && response.reasoningSignature() != null) {
                        completedRound.add(new ApiAdapter.Message("assistant", response.reasoning(), "thinking", response.reasoningSignature()));
                    }
                    completedRound.add(new ApiAdapter.Message("assistant", answer));
                }
                synchronized (plugin.consoleRoundLimit) {
                    if (!plugin.consoleRoundLimit.complete(reservation, memory, completedRound)) return;
                    memory = conversationMemory(McAI.CONSOLE_UUID, plugin.consoleMaxHistory);
                    completed = true;
                }
                if (plugin.historyPersistenceEnabled) {
                    try { plugin.historyPersistence.save(McAI.CONSOLE_UUID, memory); }
                    catch (Exception e) { plugin.getLogger().warning("Unable to save console conversation history"); }
                }
                if (plugin.logToConsole && displaysThinking(mode) && response.reasoning() != null && !response.reasoning().isEmpty() && isThinking) {
                    plugin.getLogger().info("[" + plugin.languageManager.get("log-console-reasoning")
                            + "] \n" + redactLog(ChatColor.stripColor(response.reasoning())));
                }
                if (plugin.logToConsole) plugin.getLogger().info("[" + plugin.languageManager.get("log-console-answer-prefix")
                        + "] -> " + plugin.languageManager.get("console-name") + ": "
                        + redactLog(ChatColor.stripColor(answer)));
            } catch (Exception e) {
                plugin.getLogger().warning(plugin.languageManager.get("log-api-console-error",
                        "error", e.getClass().getSimpleName()));
            } finally {
                plugin.consoleRoundLimit.finish(reservation, completed);
            }
        });
    }

    private McAI.PersonalMode personalMode(String mode) {
        boolean pro = mode.startsWith("pro");
        boolean thinking = mode.endsWith("_think");
        String key = pro ? (thinking ? "advanced-thinking" : "advanced")
                : (thinking ? "normal-thinking" : "normal");
        McAI.PersonalMode selected = plugin.personalModes.get(key);
        if (selected == null) throw new IllegalStateException("Missing personal mode: " + key);
        return selected;
    }

    private boolean isGeminiMode(String mode) { return personalMode(mode).adapter() instanceof cn.xiaobingkuai123.mcai.adapter.GeminiAdapter; }
    private boolean isAnthropicMode(String mode) { return "anthropic".equalsIgnoreCase(personalMode(mode).provider()); }

    private boolean displaysThinking(String mode) {
        return !"none".equals(personalMode(mode).display());
    }

    ConversationHistory conversationMemory(UUID userId, int maxRounds) {
        return plugin.conversationMemories.computeIfAbsent(userId, id -> {
            if (plugin.historyPersistenceEnabled && plugin.historyPersistence != null) {
                return plugin.historyPersistence.load(id, plugin.historyMode, Math.max(0, maxRounds));
            }
            return new ConversationHistory(plugin.historyMode, Math.max(0, maxRounds));
        });
    }

    private ApiAdapter.AdapterResponse callApi(UUID userId, String question,
            String mode, int effectiveHistory, Consumer<AiStreamClient.Delta> streamListener,
            java.util.function.BooleanSupplier active) throws Exception {
        boolean isThinking = mode.endsWith("_think");
        McAI.PersonalMode configured = personalMode(mode);
        ConversationHistory memory = conversationMemory(userId, effectiveHistory);
        ApiRequestProfile profile = new ApiRequestProfile(configured.provider(), configured.adapter(),
                configured.apiKey(), configured.baseUrl(), configured.model(), configured.maxTokens(),
                plugin.getConfig().getString("ai.personal.prompt.system", ""), configured.thinking(),
                configured.strengthType(), configured.thinkingBudget(), configured.strengthValue(),
                plugin.getConfig().getDouble("ai.adapters.request.temperature", 0.7),
                plugin.getConfig().getDouble("ai.adapters.request.top-p", 1.0),
                plugin.getConfig().getInt("ai.adapters.request.timeout-seconds", 120),
                plugin.getConfig().getInt("ai.adapters.request.max-retries", 1),
                plugin.getConfig().getBoolean("ai.adapters.request.streaming", true)
                        && plugin.getConfig().getBoolean("ai.personal.streaming.enabled", true),
                plugin.getConfig().getBoolean("ai.adapters.gemini.generate-content.cached-content.enabled", false),
                configured.thinking() && "summary".equals(configured.display()), configured.display());
        Consumer<AiStreamClient.Delta> filtered = streamListener == null ? null : delta -> {
            if (delta.type() == AiStreamClient.EventType.FINAL_ANSWER || displaysThinking(mode)
                    && ("summary".equals(configured.display()) && delta.type() == AiStreamClient.EventType.OPENAI_REASONING_SUMMARY
                    || "summary".equals(configured.display()) && delta.type() == AiStreamClient.EventType.GEMINI_THOUGHT_SUMMARY
                    || "reasoning".equals(configured.display()) && delta.type() == AiStreamClient.EventType.DEEPSEEK_REASONING
                    || "reasoning".equals(configured.display()) && delta.type() == AiStreamClient.EventType.ANTHROPIC_THINKING))
                streamListener.accept(delta);
        };
        return callApi(profile, memory, question, filtered, active);
    }

    /** Immutable session-specific settings; transport/retry/sampling settings remain shared. */
    record ApiRequestProfile(String providerName, ApiAdapter adapter, String apiKey, String baseUrl,
            String model, int maxTokens, String systemPrompt, boolean thinkingEnabled, String strengthType,
            int thinkingBudget, String reasoningEffort, double temperature, double topP,
            int timeoutSeconds, int maxRetries, boolean streamingEnabled,
            boolean geminiCacheEnabled, boolean includeThoughts, String display) {}

    ApiAdapter.AdapterResponse callApi(ApiRequestProfile profile, ConversationHistory memory,
            String question, Consumer<AiStreamClient.Delta> streamListener,
            java.util.function.BooleanSupplier active) throws Exception {
        ApiAdapter adapter = profile.adapter();
        String apiBaseUrl = profile.baseUrl();
        String apiKey = profile.apiKey();
        String model = profile.model();
        List<ApiAdapter.Message> apiMessages = new ArrayList<>();
        apiMessages.add(new ApiAdapter.Message("system", profile.systemPrompt()));

        apiMessages.addAll(memory.promptMessages());
        apiMessages.add(new ApiAdapter.Message("user", question));

        String cachedContent = null;
        if (profile.geminiCacheEnabled() && adapter instanceof cn.xiaobingkuai123.mcai.adapter.GeminiAdapter
                && plugin.geminiCachedContentManager != null && apiMessages.size() > 1) {
            cachedContent = plugin.geminiCachedContentManager.getOrCreate(apiBaseUrl, apiKey, model,
                    apiMessages.subList(0, apiMessages.size() - 1));
        }
        ApiRequestOptions options = new ApiRequestOptions(profile.temperature(), profile.topP(), profile.maxTokens(),
                profile.timeoutSeconds(), profile.maxRetries(),
                streamListener != null && profile.streamingEnabled(),
                profile.thinkingEnabled(), profile.thinkingBudget(), profile.reasoningEffort(), cachedContent, profile.includeThoughts());
        java.net.http.HttpClient client = plugin.getHttpClient();
        if (client == null) throw new CancellationException("Plugin is shutting down");
        var emitted = new java.util.concurrent.atomic.AtomicBoolean();
        Consumer<AiStreamClient.Delta> observed = streamListener == null ? null : delta -> { emitted.set(true); streamListener.accept(delta); };
        GeminiCachedContentManager.CachedOperation<ApiAdapter.AdapterResponse> operation = resource -> {
            ApiRequestOptions attempt = new ApiRequestOptions(options.temperature(), options.topP(), options.maxTokens(),
                    options.timeoutSeconds(), options.maxRetries(), options.streaming(), options.thinkingEnabled(),
                    options.thinkingBudget(), options.reasoningEffort(), resource, options.includeThoughts());
            HttpRequest request = adapter.buildRequest(apiBaseUrl, apiKey, model, apiMessages, attempt);
            return executeRequest(client, adapter, request, attempt, observed, active);
        };
        return cachedContent == null ? operation.run(null)
                : plugin.geminiCachedContentManager.withFallback(cachedContent, operation, emitted::get);
    }

    private ApiAdapter.AdapterResponse executeRequest(HttpClient client, ApiAdapter adapter, HttpRequest request,
            ApiRequestOptions options, Consumer<AiStreamClient.Delta> streamListener,
            java.util.function.BooleanSupplier active) throws Exception {
        ApiRequestRetry retry = new ApiRequestRetry(active);
        if (options.streaming()) {
            ApiAdapter.AdapterResponse streamed = retry.stream(
                    client, adapter, request, streamListener, options.maxRetries());
            logCacheUsage(streamed.cacheUsage());
            return streamed;
        }

        HttpResponse<String> httpResponse = retry.send(client, request, options.maxRetries());
        int status = httpResponse.statusCode();
        if (status >= 200 && status < 300) {
            String body = httpResponse.body();
            try {
                ApiAdapter.AdapterResponse response = adapter.parseResponse(body);
                ApiAdapter.CacheUsage cacheUsage = CacheUsageParser.parse(
                        new com.google.gson.JsonParser().parse(body).getAsJsonObject());
                logCacheUsage(cacheUsage);
                return new ApiAdapter.AdapterResponse(response.content(), response.reasoning(),
                        cacheUsage, response.reasoningSignature(), response.contentBlocks());
            } catch (com.google.gson.JsonParseException | IllegalStateException e) {
                String contentType = "unavailable";
                plugin.getLogger().warning("AI provider returned an invalid response (content-type="
                        + contentType.replaceAll("[^a-zA-Z0-9/;= ._-]", "?") + ", length=" + body.length() + ")");
                throw new IOException("AI provider returned an invalid response", e);
            }
        }
        throw new AiStreamClient.ApiHttpException(status);
    }

    private IOException providerError(int status) {
        String errorMessage;
        if (status == 401) {
            errorMessage = plugin.languageManager.get("api-401");
        } else if (status == 429) {
            errorMessage = plugin.languageManager.get("api-429");
        } else {
            errorMessage = plugin.languageManager.get("api-unknown",
                    "code", String.valueOf(status));
        }
        return new IOException(errorMessage);
    }
}
