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

        PlayerStreamOutput output = plugin.activeStreamOutputs.remove(playerId);
        if (output != null) {
            output.closeNow();
        }

        // 玩家退出必须立即释放占位，不能等 worker 的 finally。
        // worker 后续用 token 判断归属，不会误删重连后的新请求。
        plugin.activePlayerRequests.remove(playerId);

        plugin.conversationHistory.remove(playerId);
        plugin.lastRequestTime.remove(playerId);
        plugin.requestCountPerMinute.remove(playerId);
        plugin.waitingInputType.remove(playerId);
        plugin.selectedChatMode.remove(playerId);
        plugin.isProMode.remove(playerId);
    }
}
