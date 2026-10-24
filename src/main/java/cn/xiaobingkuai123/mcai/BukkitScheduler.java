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
        // Paper 无退休概念；玩家退出后任务仍会执行。
        // 由 task 内部自行检查 player.isOnline()。
        Bukkit.getScheduler().runTask(plugin, task);
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
