package cn.xiaobingkuai123.mcai;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class FoliaScheduler implements SchedulerAdapter {
    private final JavaPlugin plugin;
    public FoliaScheduler(JavaPlugin plugin) { this.plugin = plugin; }

    @Override
    public void runTask(CommandSender sender, Runnable task) {
        runTask(sender, task, null);
    }

    @Override
    public void runTask(CommandSender sender, Runnable task, Runnable retired) {
        if (sender instanceof Player player) {
            var scheduled = player.getScheduler().run(
                    plugin,
                    ignored -> task.run(),
                    retired
            );
            if (scheduled == null && retired != null) {
                retired.run();
            }
        } else if (sender instanceof ConsoleCommandSender) {
            Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
        } else {
            Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
        }
    }

    @Override
    public void runTaskLater(CommandSender sender, Runnable task, long delayTicks) {
        if (delayTicks <= 0) {
            runTask(sender, task);
            return;
        }
        if (sender instanceof Player player) {
            player.getScheduler().runDelayed(plugin, ignored -> task.run(), null, delayTicks);
        } else {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), delayTicks);
        }
    }

    @Override
    public void runAsync(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }
}
