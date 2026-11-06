package cn.xiaobingkuai123.mcai;

import cn.xiaobingkuai123.mcai.adapter.*;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
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
    private volatile java.net.http.HttpClient httpClient;
    private Economy economy;
    private FloodgateApi floodgateApi;
    private boolean floodgateEnabled;

    LanguageManager languageManager;
    SoundManager soundManager;
    SchedulerAdapter schedulerAdapter;
    final AtomicBoolean isReloading = new AtomicBoolean(false);

    volatile String normalProviderName;
    volatile String proProviderName;
    volatile int apiTimeoutSeconds;
    volatile int thinkDelayTicks;
    volatile String systemPrompt;
    volatile int maxHistory;
    volatile int adminMaxHistory;
    volatile boolean logToConsole;
    volatile ConversationHistory.Mode historyMode;
    volatile int historyMaxRounds;
    volatile boolean historyPersistenceEnabled;
    volatile boolean geminiCachedContentEnabled;
    volatile boolean roundPromptEnabled;
    volatile double apiTemperature;
    volatile double apiTopP;
    volatile int apiMaxRetries;
    volatile boolean apiStreaming;
    volatile boolean thinkingEnabled;
    volatile int thinkingBudget;
    volatile String reasoningEffort;
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
    volatile ApiAdapter normalApiAdapter;
    volatile ApiAdapter proApiAdapter;

    CommandHandler commandHandler;
    ChatManager chatManager;
    EconomyManager economyManager;

    final Map<UUID, Long> lastRequestTime = new ConcurrentHashMap<>();
    final Map<UUID, Integer> requestCountPerMinute = new ConcurrentHashMap<>();
    final Map<UUID, String> waitingInputType = new ConcurrentHashMap<>();
    final Map<UUID, String> selectedChatMode = new ConcurrentHashMap<>();
    final Map<UUID, Boolean> isProMode = new ConcurrentHashMap<>();

    // 每个请求一个 token，防止旧 worker 的 finally 误删新请求的占位。
    final Map<UUID, Object> activePlayerRequests = new ConcurrentHashMap<>();
    final Map<UUID, PlayerStreamOutput> activeStreamOutputs = new ConcurrentHashMap<>();

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
        lastRequestTime.clear();
        requestCountPerMinute.clear();
        waitingInputType.clear();
        selectedChatMode.clear();
        isProMode.clear();
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
        reloadConfig();
        if (getConfig().getConfigurationSection("api") == null
                || getConfig().getConfigurationSection("chat") == null) {
            throw new IllegalArgumentException("config.yml must contain api and chat sections");
        }
        normalProviderName = getConfig().getString("api.normal-provider", "deepseek");
        proProviderName = getConfig().getString("api.pro-provider", "deepseek");
        apiTimeoutSeconds = getConfig().getInt("api.timeout-seconds", 120);
        systemPrompt = getConfig().getString("chat.system-prompt", "Minecraft助手");
        maxHistory = getConfig().getInt("chat.max-history", 5);
        adminMaxHistory = getConfig().getInt("chat.admin-max-history", 30);
        logToConsole = getConfig().getBoolean("chat.log-to-console", true);
        String historyModeName = getConfig().getString("chat.history.mode", "sliding");
        historyMode = "accumulate".equalsIgnoreCase(historyModeName) ? ConversationHistory.Mode.ACCUMULATE : ConversationHistory.Mode.SLIDING;
        historyMaxRounds = Math.max(0, getConfig().getInt("chat.history.max-rounds", maxHistory));
        historyPersistenceEnabled = getConfig().getBoolean("chat.history.persistence.enabled", true);
        geminiCachedContentEnabled = getConfig().getBoolean("api.gemini.cached-content.enabled", false);
        roundPromptEnabled = getConfig().getBoolean("chat.round-prompt.enabled", true);
        apiTemperature = getConfig().getDouble("api.request.temperature", 0.7);
        apiTopP = getConfig().getDouble("api.request.top-p", 1.0);
        apiMaxRetries = Math.max(0, getConfig().getInt("api.request.max-retries", 1));
        apiStreaming = getConfig().getBoolean("api.request.streaming", true);
        thinkingEnabled = getConfig().getBoolean("api.request.thinking.enabled", true);
        thinkingBudget = TokenBudget.thinkingTokens(
                getConfig().getInt("api.request.thinking.budget", 4096));
        reasoningEffort = getConfig().getString("api.request.reasoning-effort", "medium");
        thinkDelayTicks = getConfig().getInt("chat.think-delay-ticks", 20);
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
        normalApiAdapter = createAdapter(normalProviderName);
        proApiAdapter = createAdapter(proProviderName);
        soundManager.reload();
    }

    private ApiAdapter createAdapter(String providerName) {
        var section = getConfig().getConfigurationSection("api." + providerName);
        String apiType = section != null ? section.getString("api-type", "") : "";
        String lower = providerName.toLowerCase();
        if ("openai".equals(lower)) {
            if ("responses".equalsIgnoreCase(apiType)) {
                return new OpenAIResponsesAdapter(apiTimeoutSeconds);
            } else {
                return new OpenAIAdapter(apiTimeoutSeconds);
            }
        } else if ("anthropic".equals(lower)) {
            String apiVersion = section != null ? section.getString("api-version", "2023-06-01") : "2023-06-01";
            return new AnthropicAdapter(apiTimeoutSeconds, apiVersion);
        } else if ("gemini".equals(lower)) {
            return new GeminiAdapter(apiTimeoutSeconds);
        } else if ("deepseek".equals(lower)) {
            return new DeepSeekAdapter(apiTimeoutSeconds);
        } else {
            return new DeepSeekAdapter(apiTimeoutSeconds);
        }
    }

    public void recreateHttpClient() {
        httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(apiTimeoutSeconds))
                .build();
        if (geminiCachedContentManager != null) geminiCachedContentManager.setClient(httpClient);
    }

    public int getEffectiveMaxHistory(Player player) {
        return player.hasPermission("mcai.group.admin") ? adminMaxHistory : maxHistory;
    }

    public boolean isBedrockPlayer(Player player) {
        return floodgateEnabled && floodgateApi.isFloodgatePlayer(player.getUniqueId());
    }

    void clearPlayerHistory(UUID id) {
        ConversationHistory memory = conversationMemories.remove(id);
        if (historyPersistenceEnabled && historyPersistence != null) historyPersistence.delete(id);
        if (memory != null) memory.reset();
    }

    void openNextRoundPrompt(Player player) {
        if (isBedrockPlayer(player)) BedrockForms.openNextRoundForm(this, player);
        else new RoundLimitMenu(this, player).open();
    }
}
