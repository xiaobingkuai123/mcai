package cn.xiaobingkuai123.mcai;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.UUID;

public class PlayerQuitListener implements Listener {
    private final McAI plugin;
    public PlayerQuitListener(McAI plugin) { this.plugin = plugin; }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        plugin.publicAiManager.onViewerQuit(event.getPlayer());
        plugin.publicAiManager.onPlayerQuit(playerId);

        PlayerStreamOutput output = plugin.activeStreamOutputs.remove(playerId);
        if (output != null) {
            output.closeNow();
        }

        // 玩家退出必须立即释放占位，不能等 worker 的 finally。
        // worker 后续用 token 判断归属，不会误删重连后的新请求。
        plugin.activePlayerRequests.remove(playerId);

        if (plugin.historyPersistenceEnabled && plugin.historyPersistence != null
                && plugin.schedulerAdapter != null) {
            ConversationHistory memory = plugin.conversationMemories.get(playerId);
            if (memory != null) {
                var snapshot = memory.persistedMessages();
                HistoryPersistence persistence = plugin.historyPersistence;
                try {
                    plugin.schedulerAdapter.runAsync(() -> {
                        try { persistence.saveMessages(playerId, snapshot); }
                        catch (Exception ignored) { }
                    });
                } catch (RuntimeException ignored) {
                    // Shutdown must not prevent the remaining player-state cleanup.
                }
            }
        }
        plugin.conversationMemories.remove(playerId);
        plugin.rateLimiter.remove(playerId);
        plugin.waitingInputType.remove(playerId);
        plugin.selectedChatMode.remove(playerId);
        plugin.isProMode.remove(playerId);
    }
}
