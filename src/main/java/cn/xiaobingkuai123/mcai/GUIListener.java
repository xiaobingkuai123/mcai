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
                || holder instanceof ClearConfirmHolder)) return;

        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) return;

        Material type = item.getType();
        plugin.schedulerAdapter.runTask(player, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != top) return;
            if (!player.hasPermission("mcai.use")) return;
            if (holder instanceof MainMenuHolder) {
                handleMainMenuClick(type, player);
            } else if (holder instanceof ModeMenuHolder) {
                handleModeMenuClick(type, player);
            } else {
                handleClearConfirmClick(type, player);
            }
        });
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof MainMenuHolder || holder instanceof ModeMenuHolder
                || holder instanceof ClearConfirmHolder)) return;
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
            openClearConfirmMenu(player);
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

    private void handleClearConfirmClick(Material type, Player player) {
        if (type == Material.GREEN_WOOL) {
            plugin.soundManager.playHistoryCleared(player);
            plugin.conversationHistory.remove(player.getUniqueId());
            player.sendMessage(plugin.languageManager.get("history-cleared"));
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

    private void openClearConfirmMenu(Player player) {
        LanguageManager lang = plugin.languageManager;
        Inventory inv = Bukkit.createInventory(new ClearConfirmHolder(), 9, lang.get("clear-confirm-title"));
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        glassMeta.setDisplayName(" ");
        glass.setItemMeta(glassMeta);
        inv.setItem(0, glass);
        inv.setItem(8, glass);

        ItemStack yes = new ItemStack(Material.GREEN_WOOL);
        ItemMeta yesMeta = yes.getItemMeta();
        yesMeta.setDisplayName(lang.get("clear-confirm-yes"));
        yesMeta.setLore(List.of(lang.get("clear-confirm-lore-yes")));
        yes.setItemMeta(yesMeta);
        inv.setItem(3, yes);

        ItemStack no = new ItemStack(Material.RED_WOOL);
        ItemMeta noMeta = no.getItemMeta();
        noMeta.setDisplayName(lang.get("clear-confirm-no"));
        noMeta.setLore(List.of(lang.get("clear-confirm-lore-no")));
        no.setItemMeta(noMeta);
        inv.setItem(5, no);

        player.openInventory(inv);
    }
}
