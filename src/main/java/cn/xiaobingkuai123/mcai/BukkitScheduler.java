package cn.xiaobingkuai123.mcai;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class BukkitScheduler implements SchedulerAdapter {
    private final JavaPlugin plugin;
    public BukkitScheduler(JavaPlugin plugin) { this.plugin = plugin; }

    @Override
    public void runTask(CommandSender sender, Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void runTask(CommandSender sender, Runnable task, Runnable retired) {
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sender instanceof org.bukkit.entity.Player player && !player.isOnline()) {
                    if (retired != null) retired.run();
                } else task.run();
            });
        } catch (RuntimeException e) {
            if (retired != null) retired.run();
            throw e;
        }
    }

    @Override
    public void runTaskLater(CommandSender sender, Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }
}
