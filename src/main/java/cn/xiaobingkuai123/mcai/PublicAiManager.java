package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.ApiAdapter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.lang.reflect.Method;

/** Owns the single public conversation. State locks never encompass external I/O. */
public final class PublicAiManager {
    private enum DecisionState { NONE, CHECKING_ADMINS, PENDING, LOCKED }
    private final McAI plugin;
    private final Object stateLock = new Object();
    private ConversationHistory history;
    private volatile Settings settings;
    private volatile DecisionState decisionState = DecisionState.NONE;
    private volatile Object activeRequestToken;
    private long sessionId;
    private long minimumDeliverySession;
    private record PendingCharge(EconomyManager.Charge charge, int cost) {}
    private final java.util.Map<Object, PendingCharge> pendingRefunds = new java.util.HashMap<>();
    private long decisionId;
    private final Set<UUID> decisionAdmins = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;
    private volatile boolean initialized;
    private volatile boolean loading;
    private final File stateFile;
    private volatile Method placeholderMethod;
    private volatile boolean placeholderAvailable;
    private final CountDownLatch loadComplete = new CountDownLatch(1);
    private final ExecutorService persistence = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcai-public-save"); t.setDaemon(true); return t;
    });
    private final Object persistenceLock = new Object();
    private final ScheduledExecutorService decisionTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcai-public-decision"); t.setDaemon(true); return t;
    });
    private ScheduledFuture<?> decisionTimeoutTask;

    record Settings(boolean enabled, String trigger, int cost, String contextTemplate,
            ConversationHistory.Mode mode, int maxRounds, boolean persistent,
            String audience, int bufferPoints, long flushMillis, ChatManager.ApiRequestProfile profile, String display) {
        static Settings read(McAI plugin) {
            ConfigurationSection c = plugin.getConfig();
            ConfigurationSection publicAi = required(c, "ai.public");
            String provider = requiredText(publicAi, "provider");
            ConfigurationSection adapter = required(c, "ai.adapters." + provider);
            boolean thinking = publicAi.getBoolean("thinking.enabled", false);
            String strengthType = publicAi.getString("thinking.strength.type", "effort");
            String strengthValue = publicAi.getString("thinking.strength.value", "medium");
            int budget = "budget".equalsIgnoreCase(strengthType)
                    ? TokenBudget.thinkingTokens(publicAi.getInt("thinking.strength.value", 4096)) : 4096;
            String effort = "budget".equalsIgnoreCase(strengthType) ? "medium" : strengthValue;
            String display = publicAi.getString("thinking.display", "none").toLowerCase(java.util.Locale.ROOT);
            ChatManager.ApiRequestProfile profile = new ChatManager.ApiRequestProfile(provider,
                    plugin.createAdapter(provider), requiredText(adapter, "api-key"), requiredText(adapter, "base-url"),
                    requiredText(publicAi, "model"), TokenBudget.outputTokens(publicAi.getInt("output.max-tokens", thinking ? 32768 : 4096)),
                    publicAi.getString("prompt.system", ""), thinking, strengthType, thinking ? budget : 0,
                    thinking ? effort : strengthValue, c.getDouble("ai.adapters.request.temperature", 0.7),
                    c.getDouble("ai.adapters.request.top-p", 1.0), c.getInt("ai.adapters.request.timeout-seconds", 120),
                    c.getInt("ai.adapters.request.max-retries", 1),
                    c.getBoolean("ai.adapters.request.streaming", true) && publicAi.getBoolean("streaming.enabled", true),
                    c.getBoolean("ai.adapters.gemini.generate-content.cached-content.enabled", false),
                    thinking && "summary".equals(display), display);
            return new Settings(publicAi.getBoolean("enabled", true), publicAi.getString("trigger", "AI"),
                    Math.max(0, publicAi.getInt("cost-points", 1)), publicAi.getString("context.player-template", "{player}"),
                    "accumulate".equalsIgnoreCase(publicAi.getString("history.mode", "sliding"))
                            ? ConversationHistory.Mode.ACCUMULATE : ConversationHistory.Mode.SLIDING,
                    Math.max(1, publicAi.getInt("history.max-rounds", 20)),
                    publicAi.getBoolean("history.persistence.enabled", false),
                    "asker".equalsIgnoreCase(publicAi.getString("streaming.audience", "all")) ? "asker" : "all",
                    Math.max(1, publicAi.getInt("streaming.buffer-code-points", 96)),
                    Math.max(1, publicAi.getLong("streaming.flush-interval-ms", 500)), profile,
                    display);
        }
        private static ConfigurationSection required(ConfigurationSection root, String path) {
            ConfigurationSection section = root.getConfigurationSection(path);
            if (section == null) throw new IllegalArgumentException("Missing configuration section: " + path);
            return section;
        }
        private static String requiredText(ConfigurationSection section, String path) {
            String value = section.getString(path, "");
            if (value == null || value.isBlank())
                throw new IllegalArgumentException("Missing configuration value: " + section.getCurrentPath() + "." + path);
            return value;
        }
    }

    PublicAiManager(McAI plugin) {
        this.plugin = plugin;
        settings = Settings.read(plugin);
        stateFile = new File(plugin.getDataFolder(), "public-ai-state.yml");
        history = new ConversationHistory(settings.mode(), settings.maxRounds());
        cachePlaceholderMethod();
        loading = true;
        try {
            plugin.schedulerAdapter.runAsync(() -> {
                try {
                    ConversationHistory loaded = settings.persistent()
                            ? plugin.historyPersistence.loadPublic(settings.mode(), settings.maxRounds())
                            : new ConversationHistory(settings.mode(), settings.maxRounds());
                    boolean locked = stateFile.isFile()
                            && YamlConfiguration.loadConfiguration(stateFile).getBoolean("locked", false);
                    synchronized (stateLock) {
                        if (!stopped) {
                            history = loaded;
                            if (locked) decisionState = DecisionState.LOCKED;
                            initialized = true;
                        }
                        loading = false;
                    }
                } finally {
                    loadComplete.countDown();
                }
            });
        } catch (RuntimeException error) {
            loading = false;
            loadComplete.countDown();
            throw error;
        }
    }

    private void cachePlaceholderMethod() {
        try {
            if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                placeholderMethod = papi.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
                placeholderAvailable = true;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            placeholderMethod = null;
            placeholderAvailable = false;
        }
    }

    private boolean active(Object token, long session) {
        synchronized (stateLock) {
            return !stopped && activeRequestToken == token && sessionId == session;
        }
    }

    String parseTrigger(String message) {
        return parseTrigger(settings.trigger(), message);
    }

    static String parseTrigger(String trigger, String message) {
        if (trigger == null || trigger.isBlank()) return null;
        String prefix = "@" + trigger;
        if (!message.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
        if (message.length() > prefix.length() && !Character.isWhitespace(message.codePointAt(prefix.length()))) return null;
        return message.substring(prefix.length()).trim();
    }

    void setTrigger(String name) {
        if (name.startsWith("@")) name = name.substring(1);
        if (name.isBlank() || name.codePointCount(0, name.length()) > 24
                || name.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("Invalid public trigger");
        }
        plugin.getConfig().set("ai.public.trigger", name);
        plugin.saveConfig();
        reloadConfiguration();
    }

    static String sanitizeContext(String text) {
        if (text == null) return "";
        text = text.replaceAll("%[^%\\s]+%", "").replaceAll("[\\p{Cc}\\p{Cf}]", "");
        return text.codePoints().limit(500)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    static String resolveConsoleContext(String template, String localizedName) {
        return sanitizeContext(template.replace("{player}", localizedName));
    }

    String resolvePlayerContext(org.bukkit.entity.Player player) {
        String text = settings.contextTemplate().replace("{player}", player.getName());
        try {
            if (placeholderAvailable && placeholderMethod != null) {
                text = (String) placeholderMethod.invoke(null, player, text);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return sanitizeContext(text);
    }

    void submit(org.bukkit.entity.Player player, String question) {
        plugin.schedulerAdapter.runTask(player, () -> submitOnEntity(player, question), null);
    }

    private String checkRequestState() {
        if (stopped) return "public-ai-disabled";
        return switch (decisionState) {
            case LOCKED -> "public-ai-locked";
            case PENDING, CHECKING_ADMINS -> "public-ai-pending";
            default -> activeRequestToken != null ? "public-ai-busy" : null;
        };
    }

    void submit(org.bukkit.command.ConsoleCommandSender console, String question) {
        plugin.schedulerAdapter.runGlobalTask(() -> submitOnEntity(console, question));
    }

    private void submitOnEntity(org.bukkit.command.CommandSender sender, String question) {
        org.bukkit.entity.Player player = sender instanceof org.bukkit.entity.Player p ? p : null;
        Settings current = settings;
        if (!initialized || !current.enabled() || stopped) { tell(sender, "public-ai-disabled"); return; }
        if (player != null && (!player.hasPermission("mcai.use") || !player.hasPermission("mcai.public.use"))) {
            tell(sender, "no-permission"); return;
        }
        String rejection;
        synchronized (stateLock) { rejection = checkRequestState(); }
        if (rejection != null) { tell(sender, rejection); return; }
        if (checkHistoryLimit()) { tell(sender, "public-ai-history-full"); handleHistoryLimit(); return; }
        if (question.isBlank()) { tell(sender, "public-ai-usage"); return; }
        int limit = player == null ? 0 : plugin.economyManager.getGroupConfig(player).maxQuestionLength;
        if (limit > 0 && question.length() > limit) {
            sender.sendMessage(plugin.languageManager.get("question-too-long", "limit", String.valueOf(limit))); return;
        }
        var profile = current.profile();
        if (plugin.getConfig().getConfigurationSection("ai.adapters." + profile.providerName()) == null
                || !Set.of("openai", "anthropic", "deepseek", "gemini").contains(profile.providerName().toLowerCase(java.util.Locale.ROOT))
                || profile.apiKey().isBlank() || profile.apiKey().contains("xxx") || profile.model().isBlank()) {
            tell(sender, "public-ai-provider-invalid"); return;
        }
        Object token = new Object();
        long session;
        synchronized (stateLock) {
            rejection = checkRequestState();
            if (rejection == null && current != settings) rejection = "public-ai-busy";
            if (rejection == null && history.capped() && current.mode() == ConversationHistory.Mode.ACCUMULATE) rejection = "public-ai-history-full";
            if (rejection == null) activeRequestToken = token;
            session = sessionId;
        }
        if (rejection != null) { tell(sender, rejection); return; }
        EconomyManager.Charge charge = null;
        try {
            if (player != null && !plugin.economyManager.checkRateLimit(player)) { tell(sender, "rate-limited"); return; }
            if (player != null) {
                charge = plugin.economyManager.consumePoints(player, current.cost());
                if (charge == null) { tell(sender, "public-ai-insufficient-points"); return; }
            }
            String name = player == null ? plugin.languageManager.get("console-name") : player.getName();
            String context = player == null ? resolveConsoleContext(current.contextTemplate(), name) : resolvePlayerContext(player);
            String prompt = plugin.languageManager.get("public-ai-context", "player", name, "context", context, "question", question);
            EconomyManager.Charge paid = charge;
            startRequest(token, session, current, player == null ? null : player.getUniqueId(), name, question, prompt, paid,
                    sender instanceof org.bukkit.command.ConsoleCommandSender console ? console : null);
            charge = null;
        } finally {
            if (charge != null) plugin.economyManager.refundPoints(charge, current.cost());
            // A successfully launched worker owns the token until completion.
            synchronized (stateLock) {
                if (activeRequestToken == token && !launchedTokens.contains(token)) activeRequestToken = null;
            }
        }
    }

    private final Set<Object> launchedTokens = ConcurrentHashMap.newKeySet();

    private void startRequest(Object token, long session, Settings current, UUID asker, String name,
            String question, String prompt, EconomyManager.Charge charge,
            org.bukkit.command.ConsoleCommandSender console) {
        ConversationHistory memory;
        synchronized (stateLock) {
            if (!active(token, session)) throw new CancellationException("Stale public request");
            memory = history; launchedTokens.add(token);
            if (charge != null) pendingRefunds.put(token, new PendingCharge(charge, current.cost()));
        }
        java.util.function.BooleanSupplier valid = () -> deliveryValid(session);
        dispatch(plugin.languageManager.get("public-ai-question", "player", name, "question", question), null, null, valid);
        PublicStream stream = current.profile().streamingEnabled() ? new PublicStream(current, token, session, asker) : null;
        try {
            plugin.schedulerAdapter.runAsync(() -> {
                boolean success = false;
                try {
                    ApiAdapter.AdapterResponse response = plugin.chatManager.callApi(current.profile(), memory, prompt,
                            stream == null ? null : stream::accept, () -> active(token, session));
                    if (response.content() == null || response.content().isBlank()) throw new IOException("Empty public answer");
                    completeRequest(token, session, current, prompt, response, stream, console);
                    success = true;
                } catch (Exception error) {
                    if (stream != null) stream.abort();
                    if (active(token, session)) broadcastToAll(plugin.languageManager.get("public-ai-request-failed"), null);
                } finally {
                    if (!success) refundIfPending(token);
                    synchronized (stateLock) {
                        if (activeRequestToken == token) activeRequestToken = null;
                    }
                    launchedTokens.remove(token);
                }
            });
        } catch (RuntimeException failure) {
            launchedTokens.remove(token);
            refundIfPending(token);
            synchronized (stateLock) {
                if (activeRequestToken == token) activeRequestToken = null;
            }
            throw failure;
        }
    }

    private void completeRequest(Object token, long session, Settings current, String prompt,
            ApiAdapter.AdapterResponse response, PublicStream stream, org.bukkit.command.ConsoleCommandSender console) {
        synchronized (stateLock) {
            if (!active(token, session)) throw new CancellationException("Stale public session");
            java.util.ArrayList<ApiAdapter.Message> round = new java.util.ArrayList<>();
            round.add(new ApiAdapter.Message("user", prompt));
            if ((current.profile().adapter() instanceof cn.xiaobingkuai123.mcai.adapter.AnthropicAdapter
                    || current.profile().adapter() instanceof cn.xiaobingkuai123.mcai.adapter.GeminiAdapter)
                    && !response.contentBlocks().isEmpty()) {
                round.addAll(response.contentBlocks());
            } else round.add(new ApiAdapter.Message("assistant", response.content()));
            history.addRound(round);
            pendingRefunds.remove(token);
        }
        persistSnapshot();
        String summary = publicSummary(current, response.reasoning());
        if (stream != null) stream.finish();
        deliverResult(plugin.schedulerAdapter, () -> deliveryValid(session), console,
                summary == null || summary.isBlank() || "none".equals(current.display()) ? null : plugin.languageManager.get("public-ai-summary", "text", summary),
                plugin.languageManager.get("public-ai-answer", "text", response.content()),
                message -> dispatch(message, null, null, () -> deliveryValid(session)));
        if (checkHistoryLimit()) handleHistoryLimit();
    }

    /** Summary permission is distinct from permission to display a provider's raw reasoning. */
    static boolean thinkingEventAllowed(ChatManager.ApiRequestProfile profile, AiStreamClient.EventType type) {
        if (!profile.thinkingEnabled()) return false;
        return switch (type) {
            case OPENAI_REASONING_SUMMARY -> "summary".equals(profile.display()) && profile.adapter() instanceof cn.xiaobingkuai123.mcai.adapter.OpenAIResponsesAdapter;
            case DEEPSEEK_REASONING -> "reasoning".equals(profile.display()) && profile.adapter() instanceof cn.xiaobingkuai123.mcai.adapter.DeepSeekAdapter;
            case GEMINI_THOUGHT_SUMMARY -> "summary".equals(profile.display()) && profile.includeThoughts() && profile.adapter() instanceof cn.xiaobingkuai123.mcai.adapter.GeminiAdapter;
            case ANTHROPIC_THINKING -> !"none".equals(profile.display()) && profile.adapter() instanceof cn.xiaobingkuai123.mcai.adapter.AnthropicAdapter;
            default -> false;
        };
    }

    static void acceptPublicDelta(ChatManager.ApiRequestProfile profile,
            PublicBossBars bars, AiStreamClient.Delta delta) {
        if (delta.type() == AiStreamClient.EventType.FINAL_ANSWER
                || thinkingEventAllowed(profile, delta.type())) bars.accept(delta);
    }

    static void deliverResult(SchedulerAdapter scheduler, java.util.function.BooleanSupplier valid,
            org.bukkit.command.ConsoleCommandSender console, String summary, String answer,
            java.util.function.Consumer<String> broadcast) {
        if (!valid.getAsBoolean()) return;
        if (summary != null && !summary.isBlank()) broadcast.accept(summary);
        broadcast.accept(answer);
        // Direct response to the initiating console is independent of player chat logging.
        if (console != null) scheduler.runGlobalTask(() -> {
            if (valid.getAsBoolean()) console.sendMessage(answer);
        });
    }

    private String publicSummary(Settings current, String reasoning) {
        return current.profile().thinkingEnabled() && !"none".equals(current.display()) ? reasoning : null;
    }

    private boolean checkHistoryLimit() {
        synchronized (stateLock) { return settings.mode() == ConversationHistory.Mode.ACCUMULATE && history.capped(); }
    }

    private void handleHistoryLimit() {
        plugin.schedulerAdapter.runGlobalTask(() -> {
            long decision;
            synchronized (stateLock) {
                if (stopped || decisionState != DecisionState.NONE || !checkHistoryLimit()) return;
                decisionState = DecisionState.CHECKING_ADMINS;
                decision = ++decisionId;
                decisionAdmins.clear();
            }
            var players = new java.util.ArrayList<>(org.bukkit.Bukkit.getOnlinePlayers());
            var admins = new ConcurrentHashMap<UUID, org.bukkit.entity.Player>();
            var remaining = new java.util.concurrent.atomic.AtomicInteger(players.size() + 1);
            Runnable done = () -> {
                if (remaining.decrementAndGet() == 0 && !stopped) {
                    plugin.schedulerAdapter.runGlobalTask(() -> openAdminDecision(decision, admins));
                }
            };
            for (var candidate : players) {
                Runnable completed = FoliaScheduler.once(done);
                try {
                    plugin.schedulerAdapter.runTask(candidate, () -> {
                        try {
                            if (candidate.isOnline() && candidate.hasPermission("mcai.admin")) admins.put(candidate.getUniqueId(), candidate);
                        } finally { completed.run(); }
                    }, completed);
                } catch (RuntimeException e) { completed.run(); }
            }
            done.run();
        });
    }

    private void openAdminDecision(long decision, java.util.Map<UUID, org.bukkit.entity.Player> admins) {
        boolean reset;
        synchronized (stateLock) {
            if (stopped || decisionId != decision || decisionState != DecisionState.CHECKING_ADMINS) return;
            decisionAdmins.addAll(admins.keySet());
            reset = decisionAdmins.isEmpty();
            decisionState = DecisionState.PENDING;
        }
        if (reset) { resetSession(decision); return; }
        admins.forEach((id, admin) -> {
            try {
                plugin.schedulerAdapter.runTask(admin, () -> {
                    if (!admin.isOnline() || !admin.hasPermission("mcai.admin")) { onPlayerQuit(id); return; }
                    synchronized (stateLock) {
                        if (stopped || decisionId != decision || decisionState != DecisionState.PENDING) return;
                    }
                    GUIListener.openConfirmation(plugin, admin, ClearConfirmHolder.Action.PUBLIC_RESET_DECISION, decision);
                }, () -> onPlayerQuit(id));
            } catch (RuntimeException e) { onPlayerQuit(id); }
        });
        synchronized (stateLock) {
            if (decisionState == DecisionState.PENDING && decisionId == decision) {
                decisionTimeoutTask = decisionTimer.schedule(
                        () -> plugin.schedulerAdapter.runGlobalTask(() -> onDecisionTimeout(decision)),
                        60, TimeUnit.SECONDS);
            }
        }
    }

    void resolveAdminDecision(org.bukkit.entity.Player player, long decision, boolean accept) {
        // Called by the GUI on this player's entity scheduler; revalidate permission here too.
        if (!player.hasPermission("mcai.admin")) return;
        plugin.schedulerAdapter.runGlobalTask(() -> {
            if (accept) resetSession(decision);
            else lockSession(decision, "public-ai-session-locked");
        });
    }

    private void cancelDecisionTimeoutLocked() {
        if (decisionTimeoutTask != null) {
            decisionTimeoutTask.cancel(false);
            decisionTimeoutTask = null;
        }
    }

    private void resetSession(long decision) {
        synchronized (stateLock) {
            if (stopped || decisionId != decision || decisionState != DecisionState.PENDING) return;
            cancelDecisionTimeoutLocked();
            history.reset(); ++sessionId; ++decisionId;
            activeRequestToken = null; decisionAdmins.clear(); decisionState = DecisionState.NONE;
        }
        persistSnapshot();
        broadcastToAll(plugin.languageManager.get("public-ai-session-reset"), null);
    }

    private void lockSession(long decision, String key) {
        synchronized (stateLock) {
            if (stopped || decisionId != decision || decisionState != DecisionState.PENDING) return;
            cancelDecisionTimeoutLocked();
            decisionState = DecisionState.LOCKED; ++decisionId; decisionAdmins.clear();
        }
        persistSnapshot();
        broadcastToAll(plugin.languageManager.get(key), null);
    }

    void onDecisionTimeout(long decision) { lockSession(decision, "public-ai-decision-timeout"); }

    private void tell(org.bukkit.command.CommandSender sender, String key) { sender.sendMessage(plugin.languageManager.get(key)); }

    private boolean deliveryValid(long session) {
        synchronized (stateLock) { return !stopped && session == sessionId; }
    }

    private record Delivery(String message, UUID only, UUID excluded, java.util.function.BooleanSupplier valid) {}
    private final java.util.Queue<Delivery> deliveries = new ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean dispatchScheduled = new java.util.concurrent.atomic.AtomicBoolean();

    private void broadcastToAll(String message, UUID excludedPlayer) {
        dispatch(message, null, excludedPlayer, () -> !stopped);
    }

    private void dispatch(String message, UUID only, UUID excluded, java.util.function.BooleanSupplier valid) {
        if (stopped) return;
        deliveries.offer(new Delivery(message, only, excluded, valid));
        scheduleDispatch();
    }

    private void scheduleDispatch() {
        if (stopped || !dispatchScheduled.compareAndSet(false, true)) return;
        plugin.schedulerAdapter.runGlobalTask(() -> {
            if (stopped) { deliveries.clear(); dispatchScheduled.set(false); return; }
            var batch = new java.util.ArrayList<Delivery>();
            Delivery next;
            while ((next = deliveries.poll()) != null) batch.add(next);
            var online = new java.util.ArrayList<>(org.bukkit.Bukkit.getOnlinePlayers());
            var remaining = new java.util.concurrent.atomic.AtomicInteger(online.size() + 1);
            Runnable done = () -> {
                if (remaining.decrementAndGet() == 0) {
                    dispatchScheduled.set(false);
                    if (!deliveries.isEmpty()) scheduleDispatch();
                }
            };
            for (var recipient : online) {
                Runnable completed = FoliaScheduler.once(done);
                try {
                    plugin.schedulerAdapter.runTask(recipient, () -> {
                        try {
                            if (stopped || !recipient.isOnline()) return;
                            UUID id = recipient.getUniqueId();
                            for (Delivery delivery : batch) {
                                if (delivery.valid().getAsBoolean() && (delivery.only() == null || delivery.only().equals(id))
                                        && !id.equals(delivery.excluded())) recipient.sendMessage(delivery.message());
                            }
                        } finally { completed.run(); }
                    }, completed);
                } catch (RuntimeException e) { completed.run(); }
            }
            done.run();
        });
    }

    static List<String> splitCodePoints(String text, int maxCodePoints) {
        if (text == null || text.isEmpty()) return List.of();
        int limit = Math.max(1, maxCodePoints);
        List<String> chunks = new java.util.ArrayList<>();
        for (int offset = 0; offset < text.length();) {
            int end = text.offsetByCodePoints(offset,
                    Math.min(limit, text.codePointCount(offset, text.length())));
            chunks.add(text.substring(offset, end));
            offset = end;
        }
        return List.copyOf(chunks);
    }

    /** Separate Adventure bars: never registered in the private activeStreamOutputs map. */
    static final class PublicBossBars {
        private final SchedulerAdapter scheduler;
        private final java.util.function.BooleanSupplier valid;
        private final java.util.function.Function<String, String> labels;
        private final UUID only;
        private final java.util.Map<org.bukkit.entity.Player, net.kyori.adventure.bossbar.BossBar> bars = new ConcurrentHashMap<>();
        private volatile String preview = "";
        private volatile boolean answering;
        private volatile boolean closed;

        PublicBossBars(SchedulerAdapter scheduler, java.util.function.BooleanSupplier valid,
                java.util.function.Function<String, String> labels, UUID only) {
            this.scheduler = scheduler; this.valid = valid; this.labels = labels; this.only = only;
        }
        synchronized void accept(AiStreamClient.Delta delta) {
            if (closed || !valid.getAsBoolean()) return;
            if (delta.content() != null && !delta.content().isEmpty()) {
                if (!answering) preview = "";
                answering = true;
                append(delta.content());
            } else if (!answering && delta.reasoning() != null) append(delta.reasoning());
        }
        private void append(String text) {
            String value = preview + org.bukkit.ChatColor.stripColor(text).replaceAll("[\\p{Cc}\\p{Cf}]", " ");
            int count = value.codePointCount(0, value.length());
            preview = count > 48 ? value.substring(value.offsetByCodePoints(0, count - 48)) : value;
        }
        // Called only by the global coordinator; all Player operations are dispatched individually.
        void refresh(List<org.bukkit.entity.Player> online) {
            if (closed || !valid.getAsBoolean()) return;
            for (var player : online) {
                try {
                    scheduler.runTask(player, () -> {
                        if (closed || !valid.getAsBoolean() || !player.isOnline()
                                || (only != null && !only.equals(player.getUniqueId()))) return;
                        try {
                            boolean answer = answering;
                            String title = org.bukkit.ChatColor.stripColor(labels.apply(answer ? "stream-answer-title" : "stream-thinking-title"));
                            var name = net.kyori.adventure.text.Component.text(title + (preview.isEmpty() ? "" : " · " + preview));
                            var bar = bars.get(player);
                            var color = answer ? net.kyori.adventure.bossbar.BossBar.Color.BLUE : net.kyori.adventure.bossbar.BossBar.Color.PURPLE;
                            if (bar == null) {
                                bar = net.kyori.adventure.bossbar.BossBar.bossBar(name, 1, color,
                                        net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
                                bars.put(player, bar);
                                player.showBossBar(bar);
                            } else { bar.name(name); bar.color(color); }
                        } catch (RuntimeException ignored) { remove(player); }
                    }, () -> bars.remove(player));
                } catch (RuntimeException ignored) { bars.remove(player); }
            }
        }
        void remove(org.bukkit.entity.Player player) {
            var bar = bars.remove(player);
            if (bar != null) try { player.hideBossBar(bar); } catch (RuntimeException ignored) { }
        }
        void close() {
            closed = true;
            bars.forEach((player, bar) -> {
                try { scheduler.runTask(player, () -> remove(player), () -> bars.remove(player)); }
                catch (RuntimeException ignored) { bars.remove(player); }
            });
        }
    }

    private final Set<PublicStream> publicStreams = ConcurrentHashMap.newKeySet();
    private final class PublicStream {
        private final Object token;
        private final long session;
        private final Settings current;
        private final PublicBossBars bars;
        private volatile boolean closed;
        private ScheduledFuture<?> refreshTask;
        PublicStream(Settings current, Object token, long session, UUID asker) {
            this.current = current; this.token = token; this.session = session;
            bars = new PublicBossBars(plugin.schedulerAdapter, () -> !closed && active(token, session),
                    plugin.languageManager::get, "asker".equals(current.audience()) ? asker : null);
            publicStreams.add(this);
            refreshTask = decisionTimer.scheduleWithFixedDelay(() -> {
                if (closed || stopped) return;
                try {
                    plugin.schedulerAdapter.runGlobalTask(() -> {
                        if (!closed && active(token, session) && !("asker".equals(current.audience()) && asker == null))
                            bars.refresh(new java.util.ArrayList<>(org.bukkit.Bukkit.getOnlinePlayers()));
                    });
                } catch (RuntimeException ignored) { abort(); }
            }, 0, Math.min(200, current.flushMillis()), TimeUnit.MILLISECONDS);
        }
        void accept(AiStreamClient.Delta delta) {
            if (!closed && active(token, session)) acceptPublicDelta(current.profile(), bars, delta);
        }
        void finish() { abort(); }
        void abort() {
            closed = true;
            if (refreshTask != null) refreshTask.cancel(false);
            bars.close();
            publicStreams.remove(this);
        }
    }

    void reloadConfiguration() {
        Settings next = Settings.read(plugin);
        List<PendingCharge> refunds;
        synchronized (stateLock) {
            ++sessionId;
            activeRequestToken = null;
            history.reconfigure(next.mode(), next.maxRounds());
            settings = next;
            refunds = invalidateRequests();
        }
        refundAll(refunds);
        publicStreams.forEach(PublicStream::abort);
        persistSnapshot();
    }

    private void persistSnapshot() {
        synchronized (stateLock) {
            List<ConversationHistory.Message> snapshot = history.persistedMessages();
            boolean saveHistory = settings.persistent();
            boolean locked = decisionState == DecisionState.LOCKED;
            persistence.execute(() -> {
                synchronized (persistenceLock) {
                    try {
                        if (saveHistory) plugin.historyPersistence.savePublicMessages(snapshot);
                        saveLocked(locked);
                    } catch (IOException error) {
                        plugin.getLogger().warning(plugin.languageManager.get("public-ai-save-failed"));
                    }
                }
            });
        }
    }

    private void saveLocked(boolean locked) throws IOException {
        if (!locked) { Files.deleteIfExists(stateFile.toPath()); return; }
        Files.createDirectories(stateFile.toPath().getParent());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", 1); yaml.set("locked", true);
        Path temporary = Path.of(stateFile + ".tmp");
        yaml.save(temporary.toFile());
        try { Files.move(temporary, stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void refundIfPending(Object token) {
        PendingCharge pending;
        synchronized (stateLock) {
            pending = pendingRefunds.remove(token);
        }
        if (pending != null) plugin.economyManager.refundPoints(pending.charge(), pending.cost());
    }

    private List<PendingCharge> invalidateRequests() {
        minimumDeliverySession = sessionId;
        List<PendingCharge> refunds = List.copyOf(pendingRefunds.values());
        pendingRefunds.clear();
        return refunds;
    }

    private void refundAll(List<PendingCharge> refunds) {
        for (PendingCharge pending : refunds) {
            plugin.economyManager.refundPoints(pending.charge(), pending.cost());
        }
    }

    void clearHistory() {
        List<PendingCharge> refunds;
        synchronized (stateLock) {
            history.reset(); ++sessionId; ++decisionId;
            cancelDecisionTimeoutLocked();
            activeRequestToken = null; decisionState = DecisionState.NONE; decisionAdmins.clear();
            refunds = invalidateRequests();
        }
        refundAll(refunds);
        publicStreams.forEach(PublicStream::abort);
        persistSnapshot();
    }

    void onViewerQuit(org.bukkit.entity.Player player) {
        // PlayerQuitEvent runs on this player's owning thread; never cancel the shared request.
        publicStreams.forEach(stream -> stream.bars.remove(player));
    }

    void onPlayerQuit(UUID playerId) {
        if (stopped) return;
        plugin.schedulerAdapter.runGlobalTask(() -> {
            long decision;
            synchronized (stateLock) {
                if (stopped || decisionState != DecisionState.PENDING || !decisionAdmins.remove(playerId)
                        || !decisionAdmins.isEmpty()) return;
                decision = decisionId;
            }
            resetSession(decision);
        });
    }

    void shutdown() {
        try {
            loadComplete.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<PendingCharge> refunds;
        synchronized (stateLock) {
            if (stopped) return;
            stopped = true; ++sessionId; activeRequestToken = null;
            refunds = invalidateRequests();
        }
        refundAll(refunds);
        publicStreams.forEach(PublicStream::abort);
        synchronized (stateLock) { cancelDecisionTimeoutLocked(); }
        deliveries.clear();
        persistSnapshot();
        decisionTimer.shutdownNow();
        persistence.shutdown();
        try {
            if (!persistence.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().warning(plugin.languageManager.get("public-ai-save-failed"));
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
