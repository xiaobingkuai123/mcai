package cn.xiaobingkuai123.mcai;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlayerStreamOutput {
    private final McAI plugin;
    private final Player player;

    private final long updateTicks;
    private final int windowChars;
    private final boolean showReasoning;
    private final long bossbarHoldTicks;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean pumpStarted = new AtomicBoolean(false);

    // 仅由 API worker 访问。
    private final StringBuilder reasoning = new StringBuilder();

    // 阶段和预览文字一起发布，避免 Boss 条读取到混合状态。
    private static final class PreviewState {
        final boolean answering;
        final String text;
        PreviewState(boolean answering, String text) {
            this.answering = answering;
            this.text = text;
        }
    }
    private volatile PreviewState previewState = new PreviewState(false, "");

    // 仅由玩家调度器访问。
    private BossBar bar;
    private boolean displayedAnswerPhase;
    private long lastTickErrorLog = 0L;

    public PlayerStreamOutput(McAI plugin, Player player) {
        this.plugin = plugin;
        this.player = player;

        updateTicks = Math.max(1, plugin.getConfig().getLong(
                "chat.streaming.update-ticks", 4));

        windowChars = Math.max(1, Math.min(64, plugin.getConfig().getInt(
                "chat.streaming.window-chars", 48)));

        showReasoning = plugin.getConfig().getBoolean(
                "chat.streaming.show-reasoning-in-chat", true);

        bossbarHoldTicks = Math.max(0, plugin.getConfig().getLong(
                "chat.streaming.bossbar-hold-ticks", 40));
    }

    public boolean isClosed() { return closed.get(); }

    /**
     * 在请求发起时立即调用，让 Boss 条在等首字节期间就出现。
     */
    public void start() {
        plugin.schedulerAdapter.runTask(player, () -> {
            if (closed.get() || !player.isOnline()) return;
            if (!pumpStarted.compareAndSet(false, true)) return;
            tick();
        });
    }

    public void accept(AiStreamClient.Delta delta) {
        if (closed.get()) {
            throw new CancellationException("Player stream closed");
        }

        if (delta.reasoning() != null && delta.reasoning().length() > 0) {
            reasoning.append(delta.reasoning());
            if (!previewState.answering) {
                appendPreview(delta.reasoning());
                schedulePump();
            }
        }

        if (delta.content() != null && delta.content().length() > 0) {
            if (!previewState.answering) {
                String completedReasoning = reasoning.toString();
                plugin.schedulerAdapter.runTask(player, () -> {
                    if (closed.get() || !player.isOnline()) return;
                    if (showReasoning && !completedReasoning.isBlank()) {
                        plugin.soundManager.playReasoningDone(player);
                        sendText(plugin, player, "reasoning-label",
                                completedReasoning, ChatColor.GRAY);
                    }
                });
                previewState = new PreviewState(true, "");
            }
            appendPreview(delta.content());
            schedulePump();
        }
    }

    private void appendPreview(String delta) {
        String cleaned = ChatColor.stripColor(delta);
        if (cleaned == null || cleaned.isEmpty()) return;

        // Boss 条只显示一行，换行/制表符替换为空格。
        cleaned = cleaned.replaceAll("[\\r\\n\\t\\p{Cntrl}]+", " ");

        PreviewState previous = previewState;
        String combined = previous.text + cleaned;
        int count = combined.codePointCount(0, combined.length());

        if (count > windowChars) {
            int start = combined.offsetByCodePoints(0, count - windowChars);
            combined = combined.substring(start);
        }

        previewState = new PreviewState(previous.answering, combined);
    }

    private void schedulePump() {
        // 由 start() 提前启动时，pumpStarted 已经为 true，直接返回。
        if (!pumpStarted.compareAndSet(false, true)) return;
        plugin.schedulerAdapter.runTask(player, this::tick);
    }

    private void tick() {
        if (closed.get()) return;

        try {
            if (!player.isOnline()) {
                closeNow();
                return;
            }

            PreviewState snapshot = previewState;
            boolean currentAnswerPhase = snapshot.answering;
            String currentPreview = snapshot.text;

            String title = ChatColor.stripColor(plugin.languageManager.get(
                    currentAnswerPhase ? "stream-answer-title" : "stream-thinking-title"));

            Component name = Component.text(
                    title + (currentPreview.isEmpty() ? "" : " · " + currentPreview));

            if (bar == null) {
                displayedAnswerPhase = currentAnswerPhase;

                // 无法预知生成总长度，进度条仅用于装饰。
                // 标题始终显示最近 N 个字符，新文字加入后删除旧文字。
                bar = BossBar.bossBar(
                        name,
                        1.0f,
                        currentAnswerPhase ? BossBar.Color.BLUE : BossBar.Color.PURPLE,
                        BossBar.Overlay.PROGRESS
                );
                player.showBossBar(bar);
            } else {
                if (displayedAnswerPhase != currentAnswerPhase) {
                    displayedAnswerPhase = currentAnswerPhase;
                    bar.color(currentAnswerPhase ? BossBar.Color.BLUE : BossBar.Color.PURPLE);
                }
                bar.name(name);
            }
        } catch (RuntimeException error) {
            long now = System.currentTimeMillis();
            if (now - lastTickErrorLog > 5000) {
                lastTickErrorLog = now;
                plugin.getLogger().warning("BossBar tick failed: " + error.getMessage());
            }
            // 不 return，继续调度下一次刷新。
        }

        if (!closed.get() && plugin.isEnabled()) {
            try {
                plugin.schedulerAdapter.runTaskLater(player, this::tick, updateTicks);
            } catch (RuntimeException schedulingError) {
                plugin.getLogger().warning(
                        "BossBar scheduling stopped: " + schedulingError.getMessage());
                try { hideBar(); } catch (RuntimeException ignore) {}
            }
        }
    }

    public void finish(String answer, String fullReasoning) {
        plugin.schedulerAdapter.runTask(player, () -> {
            if (!closed.compareAndSet(false, true)) return;
            try {
                if (!player.isOnline()) return;

                if (!previewState.answering && showReasoning
                        && fullReasoning != null && !fullReasoning.isBlank()) {
                    sendText(plugin, player, "reasoning-label",
                            fullReasoning, ChatColor.GRAY);
                }

                plugin.soundManager.playAnswer(player);
                sendText(plugin, player, "answer-label", answer, ChatColor.WHITE);
            } finally {
                scheduleHide();
            }
        });
    }

    public void fail(String message) {
        plugin.schedulerAdapter.runTask(player, () -> {
            if (!closed.compareAndSet(false, true)) return;
            try {
                if (player.isOnline()) player.sendMessage(message);
            } finally {
                scheduleHide();
            }
        });
    }

    /**
     * 延迟 bossbar-hold-ticks 再隐藏 Boss 条，让玩家看清最后的滚动内容。
     */
    private void scheduleHide() {
        final BossBar finalBar = bar;
        if (finalBar == null) return;

        if (bossbarHoldTicks <= 0) {
            hideBar();
            return;
        }

        plugin.schedulerAdapter.runTaskLater(player, () -> {
            // 防止延迟期间玩家又发起新请求，覆盖了新 Boss 条。
            if (bar == finalBar) {
                player.hideBossBar(finalBar);
                bar = null;
            }
        }, bossbarHoldTicks);
    }

    public void closeNow() {
        closed.set(true);
        hideBar();
    }

    public void closeOnDisable() {
        // Folia 热禁用时无法安全跨区域操作玩家界面。
        // 接受限制：BossBar 保留到玩家断开连接。
        closed.set(true);
    }

    private void hideBar() {
        if (bar != null) {
            player.hideBossBar(bar);
            bar = null;
        }
    }

    public static void sendText(McAI plugin, Player player, String labelKey,
                                String text, ChatColor color) {
        player.sendMessage(plugin.languageManager.get(labelKey));
        if (text == null || text.isBlank()) return;

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');

        String[] lines = normalized.split("\n", -1);
        for (String line : lines) {
            if (line.isEmpty()) {
                player.sendMessage(Component.empty());
                continue;
            }

            int offset = 0;
            while (offset < line.length()) {
                int available = line.codePointCount(offset, line.length());
                int end = line.offsetByCodePoints(offset, Math.min(240, available));
                player.sendMessage(color.toString() + line.substring(offset, end));
                offset = end;
            }
        }
    }
}
