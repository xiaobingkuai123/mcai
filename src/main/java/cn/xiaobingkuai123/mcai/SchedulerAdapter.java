package cn.xiaobingkuai123.mcai;
import org.bukkit.command.CommandSender;

public interface SchedulerAdapter {
    void runTask(CommandSender sender, Runnable task);
    void runTaskLater(CommandSender sender, Runnable task, long delayTicks);
    void runAsync(Runnable task);
    void runGlobalTask(Runnable task);

    /** Runs on the player's entity scheduler (Paper: server thread). Retirement callback must not access Player APIs. */
    default void runTask(CommandSender sender, Runnable task, Runnable retired) {
        runTask(sender, task);
    }
}
