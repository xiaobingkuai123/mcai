package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.*;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.floodgate.api.FloodgateApi;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class McAI extends JavaPlugin {
    private static McAI instance;
    private volatile org.bukkit.configuration.file.FileConfiguration acceptedConfiguration;
    private final ThreadLocal<org.bukkit.configuration.file.FileConfiguration> candidateConfiguration = new ThreadLocal<>();
    @Override public org.bukkit.configuration.file.FileConfiguration getConfig() {
        var candidate = candidateConfiguration.get();
        return candidate != null ? candidate : acceptedConfiguration != null ? acceptedConfiguration : super.getConfig();
    }
    private volatile java.net.http.HttpClient httpClient;
    private Economy economy;
    private FloodgateApi floodgateApi;
    private boolean floodgateEnabled;

    LanguageManager languageManager;
    SoundManager soundManager;
    SchedulerAdapter schedulerAdapter;
    final AtomicBoolean isReloading = new AtomicBoolean(false);

    volatile int apiTimeoutSeconds;
    volatile int thinkDelayTicks;
    volatile int consoleMaxHistory;
    volatile boolean logToConsole;
    volatile ConversationHistory.Mode historyMode;
    volatile boolean historyPersistenceEnabled;
    final Map<UUID, ConversationHistory> conversationMemories = new ConcurrentHashMap<>();
    HistoryPersistence historyPersistence;
    GeminiCachedContentManager geminiCachedContentManager;
    volatile int normalCostPoints;
    volatile int normalThinkCostPoints;
    volatile int proCostPoints;
    volatile int proThinkCostPoints;
    volatile double costPerPoint;
    volatile int maxBuyPerDay;
    volatile boolean rateLimitEnabled;
    volatile int ratePerMinute;
    volatile boolean dailyLimitEnabled;
    volatile boolean economyEnabled;
    volatile Map<String, PersonalMode> personalModes = Map.of();
    private final Map<UUID, Object> playerHistoryLocks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> playerHistoryGenerations = new ConcurrentHashMap<>();
    Object playerHistoryLock(UUID id) { return playerHistoryLocks.computeIfAbsent(id, ignored -> new Object()); }
    long playerHistoryGeneration(UUID id) { return playerHistoryGenerations.getOrDefault(id, 0L); }
    void invalidatePlayerRequests(UUID id) { activePlayerRequests.remove(id); playerHistoryGenerations.merge(id, 1L, Long::sum); }

    record PersonalMode(String provider, ApiAdapter adapter, String apiKey, String baseUrl,
            String model, int maxTokens, boolean thinking, String strengthType, int thinkingBudget,
            String strengthValue, String display) {}


    CommandHandler commandHandler;
    ChatManager chatManager;
    PublicAiManager publicAiManager;
    EconomyManager economyManager;

    final RequestRateLimiter rateLimiter = new RequestRateLimiter();

    final Map<UUID, String> waitingInputType = new ConcurrentHashMap<>();
    final Map<UUID, String> selectedChatMode = new ConcurrentHashMap<>();
    final Map<UUID, Boolean> isProMode = new ConcurrentHashMap<>();

    // 每个请求一个 token，防止旧 worker 的 finally 误删新请求的占位。
    final Map<UUID, Object> activePlayerRequests = new ConcurrentHashMap<>();
    final Map<UUID, PlayerStreamOutput> activeStreamOutputs = new ConcurrentHashMap<>();
    final ConsoleRoundLimit consoleRoundLimit = new ConsoleRoundLimit();

    static final UUID CONSOLE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        languageManager = new LanguageManager(this);
        soundManager = new SoundManager(this);
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            schedulerAdapter = new FoliaScheduler(this);
            getLogger().info(languageManager.get("log-folia-detected"));
        } catch (ClassNotFoundException e) {
            schedulerAdapter = new BukkitScheduler(this);
        }
        if (!initializeEconomy()) {
            getLogger().warning(languageManager.get("log-vault-not-found"));
        }
        refreshConfigurationCache();
        historyPersistence = new HistoryPersistence(getDataFolder());
        httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(apiTimeoutSeconds))
                .build();
        geminiCachedContentManager = new GeminiCachedContentManager(httpClient, apiTimeoutSeconds);
        if (Bukkit.getPluginManager().getPlugin("floodgate") != null) {
            floodgateApi = FloodgateApi.getInstance();
            floodgateEnabled = true;
        }
        economyManager = new EconomyManager(this);
        chatManager = new ChatManager(this, economyManager);
        publicAiManager = new PublicAiManager(this);
        commandHandler = new CommandHandler(this, chatManager, economyManager);
        PluginCommand aiCommand = getCommand("ai");
        if (aiCommand != null) {
            aiCommand.setExecutor(commandHandler);
            aiCommand.setTabCompleter(commandHandler);
        }
        getServer().getPluginManager().registerEvents(new GUIListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatInputListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(this), this);
        economyManager.loadDailyUsageData();
    }

    @Override
    public void onDisable() {
        if (publicAiManager != null) publicAiManager.shutdown();
        // Folia 热禁用时无法跨区域清除在线玩家的 BossBar。
        // 接受限制：BossBar 保留到玩家断开连接。
        for (PlayerStreamOutput output : activeStreamOutputs.values()) {
            output.closeOnDisable();
        }
        activeStreamOutputs.clear();
        activePlayerRequests.clear();

        if (economyManager != null) economyManager.closeAndSave();
        if (historyPersistenceEnabled && historyPersistence != null) {
            for (Map.Entry<UUID, ConversationHistory> entry : conversationMemories.entrySet()) {
                try { historyPersistence.save(entry.getKey(), entry.getValue()); }
                catch (Exception e) { getLogger().warning("Unable to save history for " + entry.getKey()); }
            }
        }
        conversationMemories.clear();
        rateLimiter.close();
        waitingInputType.clear();
        selectedChatMode.clear();
        isProMode.clear();
        if (geminiCachedContentManager != null) geminiCachedContentManager.close();
        httpClient = null;
    }

    public static McAI getInstance() { return instance; }
    public Economy getEconomy() { return economy; }
    public FloodgateApi getFloodgateApi() { return floodgateApi; }
    public boolean isFloodgateEnabled() { return floodgateEnabled; }
    public java.net.http.HttpClient getHttpClient() { return httpClient; }

    boolean initializeEconomy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider == null) { economy = null; return false; }
        economy = provider.getProvider();
        return economy != null;
    }

    void refreshConfigurationCache() {
        super.reloadConfig();
        applyConfigurationCandidate(super.getConfig());
    }

    void applyConfigurationCandidate(org.bukkit.configuration.file.FileConfiguration candidate) {
        candidateConfiguration.set(candidate);
        Map<String, PersonalMode> nextModes = new LinkedHashMap<>();
        try {
            requireSection("ai"); requireSection("ai.adapters"); requireSection("ai.adapters.request"); requireSection("ai.personal");
            for (String mode : List.of("normal", "normal-thinking", "advanced", "advanced-thinking")) {
                PersonalMode next = readPersonalMode(requireSection("ai.personal.modes." + mode));
                validateDisplay(next); validateRequest(next.adapter(), next.apiKey(), next.baseUrl(), next.model(), next.maxTokens(), next.thinking(), next.thinkingBudget(), "budget".equals(next.strengthType()) ? "medium" : next.strengthValue(), next.display());
                nextModes.put(mode, next);
            }
            PublicAiManager.Settings.read(this);
            validateDisplayModes(nextModes);
        } catch (RuntimeException failure) {
            candidateConfiguration.remove();
            throw failure;
        }
        acceptedConfiguration = candidateConfiguration.get();
        candidateConfiguration.remove();
        requireSection("ai");
        requireSection("ai.adapters");
        ConfigurationSection request = requireSection("ai.adapters.request");
        apiTimeoutSeconds = Math.max(1, request.getInt("timeout-seconds", 120));

        var personal = requireSection("ai.personal");
        consoleMaxHistory = Math.max(0, personal.getInt("console.max-history", 30));
        logToConsole = personal.getBoolean("console.log-to-console", false);
        String historyModeName = personal.getString("history.mode", "sliding");
        historyMode = "accumulate".equalsIgnoreCase(historyModeName) ? ConversationHistory.Mode.ACCUMULATE : ConversationHistory.Mode.SLIDING;
        historyPersistenceEnabled = personal.getBoolean("history.persistence.enabled", true);
        thinkDelayTicks = Math.max(0, personal.getInt("streaming.answer-delay-ticks", 20));

        personalModes = Map.copyOf(nextModes);

        normalCostPoints = getConfig().getInt("points.normal-cost", 1);
        normalThinkCostPoints = getConfig().getInt("points.normal-think-cost", 2);
        proCostPoints = getConfig().getInt("points.pro-cost", 3);
        proThinkCostPoints = getConfig().getInt("points.pro-think-cost", 6);
        costPerPoint = getConfig().getDouble("points.cost-per-point", 100.0);
        maxBuyPerDay = getConfig().getInt("points.max-buy-per-day", 20);
        rateLimitEnabled = getConfig().getBoolean("rate-limit.enabled", true);
        ratePerMinute = getConfig().getInt("rate-limit.per-minute", 3);
        dailyLimitEnabled = getConfig().getBoolean("daily-limit.enabled", true);
        economyEnabled = (economy != null);

        if (soundManager != null) soundManager.reload();
    }

    private ConfigurationSection requireSection(String path) {
        var section = getConfig().getConfigurationSection(path);
        if (section == null) throw new IllegalArgumentException("Missing configuration section: " + path);
        return section;
    }

    private PersonalMode readPersonalMode(ConfigurationSection section) {
        String provider = requireText(section, "provider");
        var adapterSection = requireSection("ai.adapters." + provider);
        boolean thinking = section.getBoolean("thinking.enabled", false);
        String type = section.getString("thinking.strength.type", "effort");
        String value = section.getString("thinking.strength.value", "medium");
        int budget = "budget".equalsIgnoreCase(type) ? TokenBudget.thinkingTokens(section.getInt("thinking.strength.value", 4096)) : 4096;
        if (thinking && !(type.equalsIgnoreCase("effort") || type.equalsIgnoreCase("budget") || type.equalsIgnoreCase("level")))
            throw new IllegalArgumentException("Unsupported thinking strength type at " + section.getCurrentPath());
        return new PersonalMode(provider, createAdapter(provider), requireText(adapterSection, "api-key"),
                requireText(adapterSection, "base-url"), requireText(section, "model"),
                TokenBudget.outputTokens(section.getInt("output.max-tokens", thinking ? 32768 : 4096)),
                thinking, type.toLowerCase(Locale.ROOT), thinking ? budget : 0, value,
                section.getString("thinking.display", "none").toLowerCase(Locale.ROOT));
    }

    private String requireText(ConfigurationSection section, String path) {
        String value = section.getString(path, "");
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing configuration value: " + section.getCurrentPath() + "." + path);
        return value;
    }

    private void validateDisplayModes(Map<String, PersonalMode> modes) {
        modes.values().forEach(this::validateDisplay);
        var publicSection = requireSection("ai.public");
        String provider = requireText(publicSection, "provider");
        requireSection("ai.adapters." + provider);
        String display = publicSection.getString("thinking.display", "none").toLowerCase(Locale.ROOT);
        if (!Set.of("none", "summary", "reasoning").contains(display)) throw new IllegalArgumentException("Invalid thinking.display: " + display);
        if ("summary".equals(display) && "deepseek".equalsIgnoreCase(provider)) throw new IllegalArgumentException("DeepSeek does not provide reasoning summaries; use display: reasoning or none");
        if ("reasoning".equals(display) && "gemini".equalsIgnoreCase(provider)) throw new IllegalArgumentException("Gemini provides thought summaries, not raw reasoning; use display: summary or none");
        validateStrength(provider, publicSection.getString("thinking.strength.type", "effort"), publicSection.getBoolean("thinking.enabled", false));
    }

    private void validateStrength(String provider, String type, boolean thinking) {
        if (!thinking) return;
        boolean valid = switch (provider.toLowerCase(Locale.ROOT)) {
            case "openai" -> "effort".equalsIgnoreCase(type);
            case "deepseek" -> "effort".equalsIgnoreCase(type);
            case "gemini" -> Set.of("budget", "level").contains(type.toLowerCase(Locale.ROOT));
            case "anthropic" -> Set.of("budget", "effort").contains(type.toLowerCase(Locale.ROOT));
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Thinking strength type '" + type + "' is not supported by " + provider);
    }

    private void validateDisplay(PersonalMode mode) {
        if (!Set.of("none", "summary", "reasoning").contains(mode.display())) throw new IllegalArgumentException("Invalid thinking.display: " + mode.display());
        if ("summary".equals(mode.display()) && "deepseek".equalsIgnoreCase(mode.provider())) throw new IllegalArgumentException("DeepSeek does not provide reasoning summaries");
        if ("reasoning".equals(mode.display()) && "anthropic".equalsIgnoreCase(mode.provider())) throw new IllegalArgumentException("Anthropic does not expose raw reasoning");
        if ("reasoning".equals(mode.display()) && "gemini".equalsIgnoreCase(mode.provider())) throw new IllegalArgumentException("Gemini provides thought summaries, not raw reasoning");
        validateStrength(mode.provider(), mode.strengthType(), mode.thinking());
    }


    static void validateRequest(ApiAdapter adapter, String key, String base, String model, int max, boolean thinking, int budget, String effort, String display) {
        java.net.URI uri = java.net.URI.create(base);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Invalid AI base URL");
        if (key.isBlank() || model.isBlank() || max <= 0) throw new IllegalArgumentException("Invalid AI request configuration");
        try { adapter.buildRequest(base,key,model,List.of(),new ApiRequestOptions(.7,1,max,120,0,false,thinking,budget,effort,null,!"none".equals(display),display)); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid AI request configuration",e); }
    }

    ApiAdapter createAdapter(String providerName) {
        var section = getConfig().getConfigurationSection("ai.adapters." + providerName);
        String apiType = section != null ? section.getString("api-type", "") : "";
        String lower = providerName.toLowerCase();
        if ("openai".equals(lower)) {
            if ("responses".equalsIgnoreCase(apiType)) {
                boolean enabled = section != null && section.getBoolean("responses.reasoning-summary.enabled", true);
                String level = section == null ? "auto" : section.getString("responses.reasoning-summary.level", "auto");
                Set<String> models = section == null ? Set.of() : new HashSet<>(section.getStringList("responses.reasoning-summary.supported-models"));
                return new OpenAIResponsesAdapter(apiTimeoutSeconds, enabled, models, level);
            } else if ("chat".equalsIgnoreCase(apiType)) {
                return new OpenAIAdapter(apiTimeoutSeconds);
            }
            throw new IllegalArgumentException("OpenAI adapter api-type must be responses or chat");
        } else if ("anthropic".equals(lower)) {
            if (!"messages".equalsIgnoreCase(apiType)) throw new IllegalArgumentException("Anthropic adapter api-type must be messages");
            String apiVersion = section != null ? section.getString("api-version", "2023-06-01") : "2023-06-01";
            return new AnthropicAdapter(apiTimeoutSeconds, apiVersion);
        } else if ("gemini".equals(lower)) {
            if (!"generate-content".equalsIgnoreCase(apiType)) throw new IllegalArgumentException("Gemini adapter api-type must be generate-content");
            return new GeminiAdapter(apiTimeoutSeconds);
        } else if ("deepseek".equals(lower)) {
            if (!"chat".equalsIgnoreCase(apiType)) throw new IllegalArgumentException("DeepSeek adapter api-type must be chat");
            return new DeepSeekAdapter(apiTimeoutSeconds);
        }
        throw new IllegalArgumentException("Unsupported AI adapter: " + providerName);
    }

    public void recreateHttpClient() {
        httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(apiTimeoutSeconds))
                .build();
        if (geminiCachedContentManager != null) geminiCachedContentManager.setClient(httpClient);
    }

    public boolean isBedrockPlayer(Player player) {
        return floodgateEnabled && floodgateApi.isFloodgatePlayer(player.getUniqueId());
    }

    void clearPlayerHistory(UUID id) {
        if (CONSOLE_UUID.equals(id)) {
            synchronized (consoleRoundLimit) { clearPlayerHistoryLocked(id); }
        } else clearPlayerHistoryLocked(id);
    }

    private void clearPlayerHistoryLocked(UUID id) {
        synchronized (playerHistoryLock(id)) {
        invalidatePlayerRequests(id);
        PlayerStreamOutput output = activeStreamOutputs.remove(id);
        if (output != null) {
            Player owner = getServer().getPlayer(id);
            if (owner != null) schedulerAdapter.runTask(owner, output::closeNow, output::closeOnDisable);
            else output.closeOnDisable();
        }
        ConversationHistory memory;
        if (CONSOLE_UUID.equals(id)) {
            synchronized (consoleRoundLimit) {
                consoleRoundLimit.clear();
                memory = conversationMemories.remove(id);
                if (memory != null) memory.reset();
            }
        } else {
            memory = conversationMemories.remove(id);
            if (memory != null) memory.reset();
        }
        if (historyPersistence != null) historyPersistence.delete(id);
        }
    }

    void openNextRoundPrompt(Player player) {
        if (isBedrockPlayer(player)) BedrockForms.openNextRoundForm(this, player);
        else new RoundLimitMenu(this, player).open();
    }
}
