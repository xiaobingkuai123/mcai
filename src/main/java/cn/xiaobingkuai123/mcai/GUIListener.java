package cn.xiaobingkuai123.mcai;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.List;

public class GUIListener implements Listener {
    private final McAI plugin;
    public GUIListener(McAI plugin) { this.plugin = plugin; }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof MainMenuHolder || holder instanceof ModeMenuHolder
                || holder instanceof ClearConfirmHolder || holder instanceof RoundLimitHolder)) return;

        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        Material type = item.getType();
        plugin.schedulerAdapter.runTask(player, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != top) return;
            if (holder instanceof ClearConfirmHolder confirm) {
                boolean allowed = confirm.action == ClearConfirmHolder.Action.CLEAR_PERSONAL_HISTORY
                        ? player.hasPermission("mcai.use") || player.hasPermission("mcai.admin") : player.hasPermission("mcai.admin");
                if (allowed) handleClearConfirmClick(type, player, confirm);
                return;
            }
            if (!player.hasPermission("mcai.use") && !player.hasPermission("mcai.admin")) return;
            if (holder instanceof MainMenuHolder menu) {
                if (menu.view == MainMenuHolder.View.HISTORY_CLEAR) {
                    if (type == Material.PAPER) openConfirmation(plugin, player, ClearConfirmHolder.Action.CLEAR_PERSONAL_HISTORY, 0);
                    else if (type == Material.BARRIER && player.hasPermission("mcai.admin")) openConfirmation(plugin, player, ClearConfirmHolder.Action.CLEAR_PUBLIC_HISTORY, 0);
                } else if (type == Material.BARRIER || player.hasPermission("mcai.use")) handleMainMenuClick(type, player);
            } else if (player.hasPermission("mcai.use")) {
                if (holder instanceof ModeMenuHolder) handleModeMenuClick(type, player);
                else handleRoundLimitClick(type, player);
            }
        });
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof MainMenuHolder || holder instanceof ModeMenuHolder
                || holder instanceof ClearConfirmHolder || holder instanceof RoundLimitHolder)) return;
        java.util.Set<Integer> rawSlots = event.getRawSlots();
        for (Integer slot : rawSlots) {
            if (slot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void handleMainMenuClick(Material type, Player player) {
        if (type == Material.PAPER) {
            plugin.soundManager.playButtonClick(player);
            player.closeInventory();
            plugin.isProMode.put(player.getUniqueId(), false);
            new ModeMenu(plugin, player, false).open();
        } else if (type == Material.DIAMOND) {
            plugin.soundManager.playButtonClick(player);
            player.closeInventory();
            plugin.isProMode.put(player.getUniqueId(), true);
            new ModeMenu(plugin, player, true).open();
        } else if (type == Material.GOLD_INGOT) {
            plugin.soundManager.playButtonClick(player);
            player.closeInventory();
            if (!plugin.economyEnabled) {
                player.sendMessage(plugin.languageManager.get("buy-disabled"));
            } else {
                plugin.economyManager.startPurchaseInput(player);
            }
        } else if (type == Material.BARRIER) {
            plugin.soundManager.playButtonClick(player);
            Inventory inv = Bukkit.createInventory(new MainMenuHolder(MainMenuHolder.View.HISTORY_CLEAR), 9,
                    plugin.languageManager.get("public-ai-clear-select"));
            inv.setItem(3, button(plugin, Material.PAPER, "public-ai-clear-personal"));
            if (player.hasPermission("mcai.admin")) inv.setItem(5, button(plugin, Material.BARRIER, "public-ai-clear-public"));
            player.openInventory(inv);
        }
    }

    private void handleModeMenuClick(Material type, Player player) {
        boolean isPro = plugin.isProMode.getOrDefault(player.getUniqueId(), false);
        if (type == Material.IRON_INGOT) {
            plugin.soundManager.playButtonClick(player);
            String mode = isPro ? "pro" : "normal";
            plugin.selectedChatMode.put(player.getUniqueId(), mode);
            startChatInput(player);
        } else if (type == Material.DIAMOND) {
            plugin.soundManager.playButtonClick(player);
            String mode = isPro ? "pro_think" : "normal_think";
            plugin.selectedChatMode.put(player.getUniqueId(), mode);
            startChatInput(player);
        } else if (type == Material.ARROW) {
            plugin.soundManager.playButtonClick(player);
            player.closeInventory();
            new JavaMainMenu(plugin, player).open();
        }
    }

    private void handleRoundLimitClick(Material type, Player player) {
        if (type == Material.GREEN_WOOL) { plugin.clearPlayerHistory(player.getUniqueId()); player.closeInventory(); player.sendMessage(plugin.languageManager.get("round-limit-started")); }
        else if (type == Material.RED_WOOL) player.closeInventory();
    }

    private void handleClearConfirmClick(Material type, Player player, ClearConfirmHolder holder) {
        if (holder.action == ClearConfirmHolder.Action.PUBLIC_RESET_DECISION) {
            if (type == Material.GREEN_WOOL || type == Material.RED_WOOL) {
                plugin.publicAiManager.resolveAdminDecision(player, holder.decisionId, type == Material.GREEN_WOOL);
                player.closeInventory();
            }
            return;
        }
        if (type == Material.GREEN_WOOL) {
            plugin.soundManager.playHistoryCleared(player);
            if (holder.action == ClearConfirmHolder.Action.CLEAR_PUBLIC_HISTORY) plugin.publicAiManager.clearHistory();
            else plugin.clearPlayerHistory(player.getUniqueId());
            player.sendMessage(plugin.languageManager.get(holder.action == ClearConfirmHolder.Action.CLEAR_PUBLIC_HISTORY ? "public-ai-cleared" : "history-cleared"));
            player.closeInventory();
        } else if (type == Material.RED_WOOL) {
            plugin.soundManager.playButtonClick(player);
            new JavaMainMenu(plugin, player).open();
        }
    }

    private void startChatInput(Player player) {
        player.closeInventory();
        plugin.waitingInputType.put(player.getUniqueId(), "chat");
        int points = plugin.economyManager.getRemainingPoints(player);
        player.sendMessage(plugin.languageManager.get("chat-input-prompt"));
        player.sendMessage(plugin.languageManager.get("chat-input-balance", "points", String.valueOf(points)));
    }

    static void openConfirmation(McAI plugin, Player player, ClearConfirmHolder.Action action, long decisionId) {
        boolean decision = action == ClearConfirmHolder.Action.PUBLIC_RESET_DECISION;
        String title = decision ? "public-ai-decision-title" : action == ClearConfirmHolder.Action.CLEAR_PUBLIC_HISTORY
                ? "public-ai-clear-confirm" : "public-ai-personal-confirm";
        Inventory inv = Bukkit.createInventory(new ClearConfirmHolder(action, decisionId), 9, plugin.languageManager.get(title));
        inv.setItem(3, button(plugin, Material.GREEN_WOOL, decision ? "public-ai-accept" : "clear-confirm-yes"));
        inv.setItem(5, button(plugin, Material.RED_WOOL, decision ? "public-ai-reject" : "clear-confirm-no"));
        player.openInventory(inv);
    }

    private static ItemStack button(McAI plugin, Material material, String key) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(plugin.languageManager.get(key));
        meta.setLore(List.of(plugin.languageManager.get("public-ai-gui-lore")));
        item.setItemMeta(meta);
        return item;
    }
}
