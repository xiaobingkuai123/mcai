package cn.xiaobingkuai123.mcai;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class ChatInputListener implements Listener {
    private final McAI plugin;
    public ChatInputListener(McAI plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (!plugin.waitingInputType.containsKey(player.getUniqueId())) {
            String question = plugin.publicAiManager.parseTrigger(message);
            if (question == null) return;
            event.setCancelled(true);
            plugin.publicAiManager.submit(player, question);
            return;
        }
        event.setCancelled(true);

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
