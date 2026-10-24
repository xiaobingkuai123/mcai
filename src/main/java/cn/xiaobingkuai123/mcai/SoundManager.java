package cn.xiaobingkuai123.mcai;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

public class SoundManager {
    private final McAI plugin;
    volatile boolean enabled;
    volatile float globalVolume;
    volatile float globalPitch;

    public SoundManager(McAI plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        ConfigurationSection soundsSection = plugin.getConfig().getConfigurationSection("sounds");
        if (soundsSection == null) {
            enabled = true; globalVolume = 0.5f; globalPitch = 1.0f; return;
        }
        enabled = soundsSection.getBoolean("enabled", true);
        globalVolume = (float) soundsSection.getDouble("global-volume", 0.5);
        globalPitch = (float) soundsSection.getDouble("global-pitch", 1.0);
    }

    public void playMenuOpen(Player player) { play(player, "menu-open"); }
    public void playButtonClick(Player player) { play(player, "button-click"); }
    public void playThinkingStart(Player player) { play(player, "thinking-start"); }
    public void playReasoningDone(Player player) { play(player, "reasoning-done"); }
    public void playAnswer(Player player) { play(player, "answer"); }
    public void playBuySuccess(Player player) { play(player, "buy-success"); }
    public void playBuyFail(Player player) { play(player, "buy-fail"); }
    public void playPointsExhausted(Player player) { play(player, "points-exhausted"); }
    public void playHistoryCleared(Player player) { play(player, "history-cleared"); }

    private void play(Player player, String configKey) {
        if (!enabled || player == null || !player.isOnline()) return;
        ConfigurationSection soundSection = plugin.getConfig().getConfigurationSection("sounds." + configKey);
        if (soundSection == null) return;
        String soundName = soundSection.getString("sound");
        if (soundName == null || soundName.isEmpty()) return;
        try {
            Sound sound = Sound.valueOf(soundName);
            float volume = (float) soundSection.getDouble("volume", 1.0) * globalVolume;
            float pitch = (float) soundSection.getDouble("pitch", 1.0) * globalPitch;
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {}
    }
}
