package cn.xiaobingkuai123.mcai;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class ChatInputListener implements Listener {
    private final McAI plugin;
    public ChatInputListener(McAI plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!plugin.waitingInputType.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        String message = event.getMessage().trim();

        plugin.schedulerAdapter.runTask(player, () -> {
            if (!player.isOnline()) return;
            String inputType = plugin.waitingInputType.remove(player.getUniqueId());
            if (inputType == null) return;

            if (message.equalsIgnoreCase("c")
                    || message.equalsIgnoreCase("cancel")
                    || message.equals("取消")) {
                plugin.selectedChatMode.remove(player.getUniqueId());
                plugin.isProMode.remove(player.getUniqueId());
                player.sendMessage(plugin.languageManager.get("prefix")
                        + plugin.languageManager.get("buy".equals(inputType)
                        ? "buy-input-cancelled" : "chat-input-cancelled"));
                return;
            }

            if ("buy".equals(inputType)) {
                plugin.economyManager.handlePurchaseInput(player, message);
                return;
            }
            if (!"chat".equals(inputType) || message.isBlank()) return;
            if (!player.hasPermission("mcai.use")) {
                player.sendMessage(plugin.languageManager.get("no-permission"));
                return;
            }
            String mode = plugin.selectedChatMode.getOrDefault(player.getUniqueId(), "normal");
            if (!plugin.economyManager.performPreChecks(player, message, mode)) return;
            plugin.soundManager.playThinkingStart(player);
            player.sendMessage(plugin.languageManager.get("thinking"));
            plugin.chatManager.askPlayer(player, message, mode);
        });
    }
}
