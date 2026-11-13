package cn.xiaobingkuai123.mcai;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlayerStreamOutput {
    private final McAI plugin; private final Player player;
    private final SchedulerAdapter scheduler;
    private final java.util.function.BooleanSupplier enabled;
    private final java.util.function.Function<String, String> label;
    private final java.util.function.LongSupplier clock;
    private final Runnable answerSound;
    private final java.util.logging.Logger logger;
    private final long updateTicks; private final int windowChars; private final boolean showReasoning; private final long bossbarHoldTicks;
    private final AtomicBoolean closed=new AtomicBoolean(false), pumpStarted=new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<ReasoningChunk> pendingReasoningChunks=new ConcurrentLinkedQueue<>();
    private final StringBuilder pendingChatReasoning=new StringBuilder();
    private record ReasoningChunk(String text, long received) {}
    private boolean reasoningLabelSent, pendingStarted; private long firstPendingNanos;
    private static final class PreviewState { final boolean answering; final String text; PreviewState(boolean a,String t){answering=a;text=t;} }
    private volatile PreviewState previewState=new PreviewState(false,"");
    private BossBar bar; private boolean displayedAnswerPhase; private long lastTickErrorLog;
    public PlayerStreamOutput(McAI plugin, Player player) {
        this(plugin, player, plugin.schedulerAdapter, plugin::isEnabled, plugin.languageManager::get,
                System::nanoTime, () -> plugin.soundManager.playAnswer(player), plugin.getLogger(),
                plugin.getConfig().getLong("chat.streaming.update-ticks", 4),
                plugin.getConfig().getInt("chat.streaming.window-chars", 48),
                plugin.getConfig().getBoolean("chat.streaming.show-reasoning-in-chat", true),
                plugin.getConfig().getLong("chat.streaming.bossbar-hold-ticks", 40));
    }

    // Same output path with deterministic scheduler/clock dependencies for acceptance tests.
    PlayerStreamOutput(McAI plugin, Player player, SchedulerAdapter scheduler,
            java.util.function.BooleanSupplier enabled, java.util.function.Function<String, String> label,
            java.util.function.LongSupplier clock, Runnable answerSound, java.util.logging.Logger logger,
            long updateTicks, int windowChars, boolean showReasoning, long holdTicks) {
        this.plugin=plugin; this.player=player; this.scheduler=scheduler; this.enabled=enabled;
        this.label=label; this.clock=clock; this.answerSound=answerSound; this.logger=logger;
        this.updateTicks=Math.max(1,Math.min(4,updateTicks));
        this.windowChars=Math.max(1,Math.min(64,windowChars));
        this.showReasoning=showReasoning; this.bossbarHoldTicks=Math.max(0,holdTicks);
    }
    public boolean isClosed(){return closed.get();}
    public void start(){scheduler.runTask(player,()->{if(closed.get()||!enabled.getAsBoolean()||!player.isOnline())return;if(!pumpStarted.compareAndSet(false,true))return;tick();});}
    public synchronized void accept(AiStreamClient.Delta delta){
        if(closed.get() || !enabled.getAsBoolean())throw new CancellationException("Player stream closed");
        if(delta.reasoning()!=null&&!delta.reasoning().isEmpty()){
            if(showReasoning) pendingReasoningChunks.offer(new ReasoningChunk(delta.reasoning(), clock.getAsLong()));
            if(!previewState.answering){appendPreview(delta.reasoning()); schedulePump();}
        }
        if(delta.content()!=null&&!delta.content().isEmpty()){ if(!previewState.answering)previewState=new PreviewState(true,""); appendPreview(delta.content()); schedulePump(); }
    }
    private void appendPreview(String delta){String cleaned=ChatColor.stripColor(delta);if(cleaned==null||cleaned.isEmpty())return;cleaned=cleaned.replaceAll("[\\r\\n\\t\\p{Cntrl}]+"," ");PreviewState p=previewState;String combined=p.text+cleaned;int count=combined.codePointCount(0,combined.length());if(count>windowChars)combined=combined.substring(combined.offsetByCodePoints(0,count-windowChars));previewState=new PreviewState(p.answering,combined);}
    private void schedulePump(){if(!pumpStarted.compareAndSet(false,true))return;scheduler.runTask(player,this::tick);}
    private void tick(){if(closed.get()||!enabled.getAsBoolean())return;try{if(!player.isOnline()){closeNow();return;}flushReasoningChat(false);PreviewState snap=previewState;boolean answer=snap.answering;String title=ChatColor.stripColor(label.apply(answer?"stream-answer-title":"stream-thinking-title"));Component name=Component.text(title+(snap.text.isEmpty()?"":" · "+snap.text));if(bar==null){displayedAnswerPhase=answer;bar=BossBar.bossBar(name,1.0f,answer?BossBar.Color.BLUE:BossBar.Color.PURPLE,BossBar.Overlay.PROGRESS);player.showBossBar(bar);}else{if(displayedAnswerPhase!=answer){displayedAnswerPhase=answer;bar.color(answer?BossBar.Color.BLUE:BossBar.Color.PURPLE);}bar.name(name);}}catch(RuntimeException e){long now=System.currentTimeMillis();if(now-lastTickErrorLog>5000){lastTickErrorLog=now;logger.warning("BossBar tick failed: "+e.getClass().getSimpleName());}}if(!closed.get()&&enabled.getAsBoolean())try{scheduler.runTaskLater(player,this::tick,updateTicks);}catch(RuntimeException e){try{hideBar();}catch(RuntimeException ignored){}}}
    private void flushReasoningChat(boolean force){
        ReasoningChunk chunk;while((chunk=pendingReasoningChunks.poll())!=null){if(!pendingStarted){firstPendingNanos=chunk.received();pendingStarted=true;}pendingChatReasoning.append(chunk.text());}
        if(pendingChatReasoning.isEmpty())return;long age=clock.getAsLong()-firstPendingNanos;boolean newline=pendingChatReasoning.indexOf("\n")>=0;int chars=pendingChatReasoning.codePointCount(0,pendingChatReasoning.length());if(!force&&!newline&&chars<96&&age<500_000_000L)return;
        if(!reasoningLabelSent&&!pendingChatReasoning.toString().isBlank()){player.sendMessage(label.apply("reasoning-summary-label"));reasoningLabelSent=true;}
        if(!pendingChatReasoning.toString().isBlank()) sendBody(pendingChatReasoning.toString(),ChatColor.GRAY);pendingChatReasoning.setLength(0);pendingStarted=false;
    }
    private void sendBody(String text,ChatColor color){String normalized=text.replace("\r\n","\n").replace('\r','\n');for(String line:normalized.split("\n",-1)){if(line.isEmpty())continue;int offset=0;while(offset<line.length()){int end=line.offsetByCodePoints(offset,Math.min(240,line.codePointCount(offset,line.length())));player.sendMessage(color.toString()+line.substring(offset,end));offset=end;}}}
    public void finish(String answer,String fullReasoning){scheduler.runTask(player,()->{if(!enabled.getAsBoolean()||!closed.compareAndSet(false,true))return;try{if(!player.isOnline()){pendingReasoningChunks.clear();return;}if(showReasoning&&pendingReasoningChunks.isEmpty()&&pendingChatReasoning.isEmpty()&&!reasoningLabelSent&&fullReasoning!=null&&!fullReasoning.isBlank())pendingReasoningChunks.offer(new ReasoningChunk(fullReasoning,clock.getAsLong()));flushReasoningChat(true);answerSound.run();player.sendMessage(label.apply("answer-label"));sendBody(answer,ChatColor.WHITE);}finally{scheduleHide();}});}
    public void fail(String message){scheduler.runTask(player,()->{if(!enabled.getAsBoolean()||!closed.compareAndSet(false,true))return;try{if(enabled.getAsBoolean()&&player.isOnline()){flushReasoningChat(true);player.sendMessage(message);}}finally{scheduleHide();}});}
    private void scheduleHide(){BossBar finalBar=bar;if(finalBar==null)return;if(bossbarHoldTicks<=0){hideBar();return;}scheduler.runTaskLater(player,()->{if(enabled.getAsBoolean()&&player.isOnline()&&bar==finalBar){player.hideBossBar(finalBar);bar=null;}},bossbarHoldTicks);}
    public synchronized void closeNow(){closed.set(true);pendingReasoningChunks.clear();pendingChatReasoning.setLength(0);hideBar();} public synchronized void closeOnDisable(){closed.set(true);pendingReasoningChunks.clear();}
    private void hideBar(){if(bar!=null){player.hideBossBar(bar);bar=null;}}
    public static void sendText(McAI plugin,Player player,String labelKey,String text,ChatColor color){player.sendMessage(plugin.languageManager.get(labelKey));if(text==null||text.isBlank())return;String normalized=text.replace("\r\n","\n").replace('\r','\n');for(String line:normalized.split("\n",-1)){if(line.isEmpty())continue;int offset=0;while(offset<line.length()){int end=line.offsetByCodePoints(offset,Math.min(240,line.codePointCount(offset,line.length())));player.sendMessage(color.toString()+line.substring(offset,end));offset=end;}}}
}
