package cn.xiaobingkuai123.mcai;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.form.CustomForm;

public class BedrockForms {
    private static void handleFormClosed(McAI plugin, Player player) {
        plugin.waitingInputType.remove(player.getUniqueId());
        plugin.selectedChatMode.remove(player.getUniqueId());
        plugin.isProMode.remove(player.getUniqueId());
    }

    public static void openMainMenu(McAI plugin, Player player) {
        plugin.soundManager.playMenuOpen(player);
        LanguageManager lang = plugin.languageManager;
        SimpleForm form = SimpleForm.builder()
                .title(lang.get("bedrock-main-title"))
                .button(lang.get("bedrock-normal-btn"))
                .button(lang.get("bedrock-pro-btn"))
                .button(lang.get("bedrock-buy-btn"))
                .button(lang.get("bedrock-clear-btn"))
                .validResultHandler(response -> plugin.schedulerAdapter.runTask(player, () -> {
                    if (!player.isOnline() || !player.hasPermission("mcai.use")) return;
                    int id = response.clickedButtonId();
                    if (id == 0) openChatForm(plugin, player, false);
                    else if (id == 1) openChatForm(plugin, player, true);
                    else if (id == 2) openBuyForm(plugin, player);
                    else if (id == 3) openClearConfirmForm(plugin, player);
                }))
                .closedResultHandler(() -> plugin.schedulerAdapter.runTask(
                        player, () -> handleFormClosed(plugin, player)))
                .build();
        plugin.getFloodgateApi().sendForm(player.getUniqueId(), form);
    }

    public static void openChatForm(McAI plugin, Player player, boolean isPro) {
        LanguageManager lang = plugin.languageManager;
        int thinkCost = isPro ? plugin.proThinkCostPoints : plugin.normalThinkCostPoints;
        CustomForm form = CustomForm.builder()
                .title(lang.get("bedrock-chat-title"))
                .input(lang.get("bedrock-chat-input"), lang.get("bedrock-chat-placeholder"))
                .toggle(lang.get("bedrock-think-toggle", "cost", String.valueOf(thinkCost)), false)
                .validResultHandler(response -> plugin.schedulerAdapter.runTask(player, () -> {
                    if (!player.isOnline() || !player.hasPermission("mcai.use")) return;
                    String question = response.asInput(0).trim();
                    if (question.isEmpty()) return;
                    boolean thinking = response.asToggle(1);
                    String mode = isPro ? (thinking ? "pro_think" : "pro") : (thinking ? "normal_think" : "normal");
                    plugin.selectedChatMode.put(player.getUniqueId(), mode);
                    if (!plugin.economyManager.performPreChecks(player, question, mode)) return;
                    plugin.soundManager.playThinkingStart(player);
                    player.sendMessage(plugin.languageManager.get("thinking"));
                    plugin.chatManager.askPlayer(player, question, mode);
                }))
                .closedResultHandler(() -> plugin.schedulerAdapter.runTask(
                        player, () -> handleFormClosed(plugin, player)))
                .build();
        plugin.getFloodgateApi().sendForm(player.getUniqueId(), form);
    }

    private static void openClearConfirmForm(McAI plugin, Player player) {
        LanguageManager lang = plugin.languageManager;
        SimpleForm form = SimpleForm.builder()
                .title(lang.get("bedrock-clear-title"))
                .content(lang.get("bedrock-clear-content"))
                .button(lang.get("bedrock-clear-confirm"))
                .button(lang.get("bedrock-clear-cancel"))
                .button(lang.get("bedrock-back-btn"))
                .validResultHandler(response -> plugin.schedulerAdapter.runTask(player, () -> {
                    if (!player.isOnline() || !player.hasPermission("mcai.use")) return;
                    int id = response.clickedButtonId();
                    if (id == 0) {
                        plugin.soundManager.playHistoryCleared(player);
                        plugin.conversationHistory.remove(player.getUniqueId());
                        player.sendMessage(plugin.languageManager.get("history-cleared"));
                    } else if (id == 2) {
                        openMainMenu(plugin, player);
                    }
                }))
                .closedResultHandler(() -> plugin.schedulerAdapter.runTask(
                        player, () -> handleFormClosed(plugin, player)))
                .build();
        plugin.getFloodgateApi().sendForm(player.getUniqueId(), form);
    }

    private static void openBuyForm(McAI plugin, Player player) {
        if (!plugin.economyEnabled) {
            player.sendMessage(plugin.languageManager.get("buy-disabled"));
            return;
        }
        LanguageManager lang = plugin.languageManager;
        int freePoints = plugin.economyManager.getRemainingPoints(player);
        String maxStr = plugin.maxBuyPerDay < 0 ? "∞" : String.valueOf(plugin.maxBuyPerDay);
        var record = plugin.economyManager.dailyUsageRecords.get(player.getUniqueId());
        CustomForm form = CustomForm.builder()
                .title(lang.get("bedrock-buy-title"))
                .label(lang.get("bedrock-buy-info",
                        "free", String.valueOf(freePoints),
                        "bought", String.valueOf(record != null ? record.pointsBought : 0),
                        "max", maxStr,
                        "price", plugin.getEconomy().format(plugin.costPerPoint),
                        "balance", plugin.getEconomy().format(plugin.getEconomy().getBalance(player))))
                .input(lang.get("bedrock-buy-input"), lang.get("bedrock-buy-placeholder"))
                .validResultHandler(response -> plugin.schedulerAdapter.runTask(player, () -> {
                    if (!player.isOnline() || !player.hasPermission("mcai.use")) return;
                    int amount;
                    try {
                        amount = Integer.parseInt(response.asInput(1).trim());
                    } catch (NumberFormatException e) {
                        player.sendMessage(lang.get("buy-invalid-number"));
                        return;
                    }
                    plugin.economyManager.performPurchase(player, amount);
                }))
                .closedResultHandler(() -> plugin.schedulerAdapter.runTask(
                        player, () -> handleFormClosed(plugin, player)))
                .build();
        plugin.getFloodgateApi().sendForm(player.getUniqueId(), form);
    }
}
