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

        synchronized (plugin.playerHistoryLock(playerId)) {
            plugin.invalidatePlayerRequests(playerId);
            ConversationHistory memory = plugin.conversationMemories.remove(playerId);
            if (plugin.historyPersistence != null) {
                // Synchronous coordinated commit finishes before the next login can load history.
                long version = plugin.historyPersistence.reserveSave(playerId);
                if (plugin.historyPersistenceEnabled && memory != null && plugin.schedulerAdapter != null) {
                    var snapshot = memory.persistedMessages();
                    HistoryPersistence persistence = plugin.historyPersistence;
                    try { persistence.saveMessages(playerId, snapshot, version); }
                    catch (Exception error) { plugin.getLogger().warning("Unable to save player history on quit"); }
                }
            }
        }
        plugin.rateLimiter.remove(playerId);
        plugin.waitingInputType.remove(playerId);
        plugin.selectedChatMode.remove(playerId);
        plugin.isProMode.remove(playerId);
    }
}
