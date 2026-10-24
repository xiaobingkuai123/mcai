package cn.xiaobingkuai123.mcai;

import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EconomyManager {
    private final McAI plugin;
    final Map<UUID, DailyUsageRecord> dailyUsageRecords = new ConcurrentHashMap<>();
    private File dailyUsageFile;

    static class DailyUsageRecord {
        String currentDate = "";
        int pointsUsed;
        int pointsBought;

        synchronized void resetIfNewDay() {
            String today = LocalDate.now().toString();
            if (!today.equals(currentDate)) {
                currentDate = today;
                pointsUsed = 0;
                pointsBought = 0;
            }
        }
        synchronized void addUsedPoints(int points) { resetIfNewDay(); pointsUsed += points; }
        synchronized void addBoughtPoints(int points) { resetIfNewDay(); pointsBought += points; }
        synchronized void subtractBoughtPoints(int points) { resetIfNewDay(); pointsBought -= points; }
        synchronized void subtractUsedPoints(int points) { resetIfNewDay(); pointsUsed = Math.max(0, pointsUsed - points); }
        synchronized int getRemaining(int maxFreePoints) { resetIfNewDay(); return maxFreePoints + pointsBought - pointsUsed; }
        synchronized Map<String, Object> serialize() {
            resetIfNewDay();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("date", currentDate);
            map.put("pointsUsed", pointsUsed);
            map.put("pointsBought", pointsBought);
            return map;
        }
        static DailyUsageRecord deserialize(Map<String, Object> data) {
            DailyUsageRecord record = new DailyUsageRecord();
            record.currentDate = (String) data.getOrDefault("date", "");
            record.pointsUsed = ((Number) data.getOrDefault("pointsUsed", 0)).intValue();
            record.pointsBought = ((Number) data.getOrDefault("pointsBought", 0)).intValue();
            return record;
        }
    }

    static final class Charge {
        final DailyUsageRecord record;
        final String date;
        int refundable;

        Charge(DailyUsageRecord record, String date, int amount) {
            this.record = record;
            this.date = date;
            this.refundable = amount;
        }

        synchronized int refund(int requested) {
            int amount = Math.min(Math.max(0, requested), refundable);
            refundable -= amount;
            if (record == null || amount == 0) return 0;
            synchronized (record) {
                record.resetIfNewDay();
                if (!date.equals(record.currentDate)) return 0;
                record.pointsUsed = Math.max(0, record.pointsUsed - amount);
            }
            return amount;
        }
    }

    static final class GroupConfig {
        final String name;
        final int maxPoints;
        final int maxQuestionLength;
        GroupConfig(String name, int maxPoints, int maxQuestionLength) {
            this.name = name;
            this.maxPoints = maxPoints;
            this.maxQuestionLength = maxQuestionLength;
        }
    }

    public EconomyManager(McAI plugin) { this.plugin = plugin; }

    void loadDailyUsageData() {
        dailyUsageFile = new File(plugin.getDataFolder(), "dayuse.yml");
        if (!dailyUsageFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dailyUsageFile);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection section = yaml.getConfigurationSection(key);
                if (section != null) {
                    dailyUsageRecords.put(uuid, DailyUsageRecord.deserialize(section.getValues(false)));
                }
            } catch (Exception ignored) {}
        }
    }

    synchronized void saveDailyUsageData() {
        if (dailyUsageFile == null) dailyUsageFile = new File(plugin.getDataFolder(), "dayuse.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, DailyUsageRecord> entry : dailyUsageRecords.entrySet()) {
            yaml.createSection(entry.getKey().toString(), entry.getValue().serialize());
        }
        try {
            yaml.save(dailyUsageFile);
        } catch (IOException e) {
            plugin.getLogger().warning(plugin.languageManager.get("log-dayuse-save-failure"));
        }
    }

    public void saveDailyUsageDataAsync() {
        plugin.schedulerAdapter.runAsync(this::saveDailyUsageData);
    }

    public int getCostForMode(String mode) {
        if ("normal".equals(mode)) return plugin.normalCostPoints;
        if ("normal_think".equals(mode)) return plugin.normalThinkCostPoints;
        if ("pro".equals(mode)) return plugin.proCostPoints;
        if ("pro_think".equals(mode)) return plugin.proThinkCostPoints;
        return plugin.normalCostPoints;
    }

    public GroupConfig getGroupConfig(Player player) {
        ConfigurationSection groupsSection = plugin.getConfig().getConfigurationSection("daily-limit.groups");
        if (groupsSection == null) return new GroupConfig("default", 10, 200);
        for (String groupName : new String[]{"admin", "vip"}) {
            ConfigurationSection section = groupsSection.getConfigurationSection(groupName);
            if (section != null && player.hasPermission(section.getString("permission", "mcai.group." + groupName))) {
                return new GroupConfig(groupName, section.getInt("max-points", 10), section.getInt("max-question-length", 200));
            }
        }
        ConfigurationSection defaultSection = groupsSection.getConfigurationSection("default");
        if (defaultSection != null) {
            return new GroupConfig("default", defaultSection.getInt("max-points", 10), defaultSection.getInt("max-question-length", 200));
        }
        return new GroupConfig("fallback", 3, 80);
    }

    public int getRemainingPoints(Player player) {
        GroupConfig config = getGroupConfig(player);
        if (config.maxPoints < 0) return 999;
        DailyUsageRecord record = dailyUsageRecords.computeIfAbsent(player.getUniqueId(), k -> new DailyUsageRecord());
        return record.getRemaining(config.maxPoints);
    }

    public boolean performPreChecks(Player player, String question, String mode) {
        if (plugin.activePlayerRequests.containsKey(player.getUniqueId())) {
            player.sendMessage(plugin.languageManager.get("stream-busy"));
            return false;
        }

        boolean isPro = mode.startsWith("pro");
        String providerName = isPro ? plugin.proProviderName : plugin.normalProviderName;
        var apiSection = plugin.getConfig().getConfigurationSection("api." + providerName);
        if (apiSection == null) {
            player.sendMessage(plugin.languageManager.get("log-provider-section-missing", "provider", providerName));
            return false;
        }
        String apiKey = apiSection.getString("key", "");
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("xxx")) {
            player.sendMessage(plugin.languageManager.get("no-api-key", "provider", providerName));
            return false;
        }

        GroupConfig groupConfig = getGroupConfig(player);
        if (groupConfig.maxQuestionLength > 0 && question.length() > groupConfig.maxQuestionLength) {
            player.sendMessage(plugin.languageManager.get("question-too-long", "limit", String.valueOf(groupConfig.maxQuestionLength)));
            return false;
        }
        if (!checkRateLimit(player)) {
            player.sendMessage(plugin.languageManager.get("rate-limited", "limit", String.valueOf(plugin.ratePerMinute)));
            return false;
        }
        if (!checkDailyLimit(player, mode)) return false;
        return true;
    }

    private boolean checkRateLimit(Player player) {
        if (!plugin.rateLimitEnabled) return true;
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        synchronized (plugin) {
            Long lastTime = plugin.lastRequestTime.get(id);
            int count = plugin.requestCountPerMinute.getOrDefault(id, 0);
            if (lastTime != null && now - lastTime > 60_000) count = 0;
            if (count >= plugin.ratePerMinute) return false;
            plugin.requestCountPerMinute.put(id, count + 1);
            plugin.lastRequestTime.put(id, now);
        }
        return true;
    }

    private boolean checkDailyLimit(Player player, String mode) {
        if (!plugin.dailyLimitEnabled) return true;
        GroupConfig config = getGroupConfig(player);
        if (config.maxPoints < 0) return true;
        int cost = getCostForMode(mode);
        DailyUsageRecord record = dailyUsageRecords.computeIfAbsent(player.getUniqueId(), k -> new DailyUsageRecord());
        if (record.getRemaining(config.maxPoints) < cost) {
            player.sendMessage(plugin.economyEnabled
                    ? plugin.languageManager.get("daily-limit-reached")
                    : plugin.languageManager.get("daily-limit-no-economy"));
            plugin.soundManager.playPointsExhausted(player);
            return false;
        }
        return true;
    }

    public Charge consumePoints(Player player, String mode) {
        if (!plugin.dailyLimitEnabled) return new Charge(null, "", 0);
        GroupConfig config = getGroupConfig(player);
        if (config.maxPoints < 0) return new Charge(null, "", 0);

        DailyUsageRecord record = dailyUsageRecords.computeIfAbsent(
                player.getUniqueId(), k -> new DailyUsageRecord());
        int cost = getCostForMode(mode);
        if (cost < 0) throw new IllegalStateException("Negative points cost");

        Charge charge;
        synchronized (record) {
            record.resetIfNewDay();
            long remaining = (long) config.maxPoints + record.pointsBought - record.pointsUsed;
            if (remaining < cost || (long) record.pointsUsed + cost > Integer.MAX_VALUE) {
                player.sendMessage(plugin.languageManager.get("daily-limit-reached"));
                return null;
            }
            record.pointsUsed += cost;
            charge = new Charge(record, record.currentDate, cost);
        }
        saveDailyUsageDataAsync();
        return charge;
    }

    public int refundPoints(Charge charge, int amount) {
        int refunded = charge.refund(amount);
        if (refunded > 0) saveDailyUsageDataAsync();
        return refunded;
    }

    public void performPurchase(Player player, int amount) {
        plugin.schedulerAdapter.runTask(player, () -> {
            if (!player.isOnline()) return;
            var economy = plugin.getEconomy();
            if (economy == null || !plugin.economyEnabled) {
                player.sendMessage(plugin.languageManager.get("buy-disabled"));
                return;
            }
            double price = plugin.costPerPoint;
            double totalCost = price * amount;
            if (amount <= 0 || !Double.isFinite(price) || price < 0 || !Double.isFinite(totalCost)) {
                player.sendMessage(plugin.languageManager.get("buy-invalid-number"));
                return;
            }

            DailyUsageRecord record = dailyUsageRecords.computeIfAbsent(
                    player.getUniqueId(), k -> new DailyUsageRecord());
            synchronized (record) {
                record.resetIfNewDay();
                long bought = (long) record.pointsBought + amount;
                if (bought > Integer.MAX_VALUE) {
                    player.sendMessage(plugin.languageManager.get("buy-invalid-number"));
                    return;
                }
                if (plugin.maxBuyPerDay >= 0 && bought > plugin.maxBuyPerDay) {
                    player.sendMessage(plugin.languageManager.get("buy-max-today",
                            "remain", String.valueOf(Math.max(0L, (long) plugin.maxBuyPerDay - record.pointsBought))));
                    plugin.soundManager.playBuyFail(player);
                    return;
                }
                if (!economy.has(player, totalCost)) {
                    player.sendMessage(plugin.languageManager.get("buy-not-enough-money",
                            "cost", economy.format(totalCost),
                            "balance", economy.format(economy.getBalance(player))));
                    plugin.soundManager.playBuyFail(player);
                    return;
                }
                EconomyResponse response = economy.withdrawPlayer(player, totalCost);
                if (!response.transactionSuccess()) {
                    player.sendMessage(plugin.languageManager.get("buy-transaction-failed"));
                    plugin.soundManager.playBuyFail(player);
                    return;
                }
                record.pointsBought = (int) bought;
            }

            saveDailyUsageDataAsync();
            player.sendMessage(plugin.languageManager.get("buy-success",
                    "amount", String.valueOf(amount),
                    "cost", economy.format(totalCost),
                    "balance", economy.format(economy.getBalance(player))));
            plugin.soundManager.playBuySuccess(player);
        });
    }

    public void startPurchaseInput(Player player) {
        plugin.waitingInputType.put(player.getUniqueId(), "buy");
        player.closeInventory();
        DailyUsageRecord record = dailyUsageRecords.computeIfAbsent(player.getUniqueId(), k -> new DailyUsageRecord());
        record.resetIfNewDay();
        String maxStr = plugin.maxBuyPerDay < 0 ? "∞" : String.valueOf(plugin.maxBuyPerDay);
        String messageKey = plugin.maxBuyPerDay < 0 ? "buy-input-remain-unlimited" : "buy-input-remain";
        player.sendMessage(plugin.languageManager.get("buy-input-prompt",
                "price", plugin.getEconomy().format(plugin.costPerPoint)));
        player.sendMessage(plugin.languageManager.get(messageKey,
                "bought", String.valueOf(record.pointsBought), "max", maxStr));
    }

    public void handlePurchaseInput(Player player, String message) {
        plugin.waitingInputType.remove(player.getUniqueId());
        int amount;
        try { amount = Integer.parseInt(message.trim()); } catch (NumberFormatException e) {
            player.sendMessage(plugin.languageManager.get("buy-invalid-number"));
            return;
        }
        performPurchase(player, amount);
    }

    public void givePoints(CommandSender sender, Player target, int amount) {
        changePoints(sender, target, amount, true);
    }

    public void takePoints(CommandSender sender, Player target, int amount) {
        changePoints(sender, target, amount, false);
    }

    private void changePoints(CommandSender sender, Player target, int amount, boolean give) {
        if (amount <= 0) {
            sender.sendMessage(plugin.languageManager.get("buy-invalid-number"));
            return;
        }
        plugin.schedulerAdapter.runTask(target, () -> {
            if (!target.isOnline()) {
                plugin.schedulerAdapter.runTask(sender, () ->
                        sender.sendMessage(plugin.languageManager.get(
                                "player-not-found", "player", target.getName())));
                return;
            }
            DailyUsageRecord record = dailyUsageRecords.computeIfAbsent(
                    target.getUniqueId(), k -> new DailyUsageRecord());
            int actual;
            synchronized (record) {
                record.resetIfNewDay();
                long next = (long) record.pointsBought + amount;
                if (give && next > Integer.MAX_VALUE) {
                    plugin.schedulerAdapter.runTask(sender, () ->
                            sender.sendMessage(plugin.languageManager.get("buy-invalid-number")));
                    return;
                }
                actual = give ? amount : Math.min(amount, Math.max(0, record.pointsBought));
                record.pointsBought += give ? actual : -actual;
            }
            saveDailyUsageDataAsync();
            String prefix = give ? "give" : "take";
            target.sendMessage(plugin.languageManager.get(
                    prefix + "-success-broadcast", "amount", String.valueOf(actual)));
            if (give) plugin.soundManager.playBuySuccess(target);
            plugin.schedulerAdapter.runTask(sender, () ->
                    sender.sendMessage(plugin.languageManager.get(prefix + "-success",
                            "player", target.getName(), "amount", String.valueOf(actual))));
        });
    }
}
