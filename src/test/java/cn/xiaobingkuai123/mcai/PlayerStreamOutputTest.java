package cn.xiaobingkuai123.mcai;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerStreamOutputTest {
    static final class Scheduler implements SchedulerAdapter {
        final Queue<Runnable> immediate=new ConcurrentLinkedQueue<>(), delayed=new ConcurrentLinkedQueue<>();
        long delay;
        public void runTask(CommandSender sender,Runnable task){immediate.offer(task);}
        public void runTaskLater(CommandSender sender,Runnable task,long ticks){delay=ticks;delayed.offer(task);}
        public void runAsync(Runnable task){throw new AssertionError("unexpected async scheduling");}
        public void runGlobalTask(Runnable task){immediate.offer(task);}
        void run(){Runnable task;while((task=immediate.poll())!=null)task.run();}
        void tick(){Runnable task=delayed.poll();if(task!=null)task.run();}
    }
    static final class Fixture {
        final Scheduler scheduler=new Scheduler(); final AtomicLong clock=new AtomicLong();
        final AtomicBoolean online=new AtomicBoolean(true), enabled=new AtomicBoolean(true);
        final List<String> messages=new ArrayList<>();
        final Player player=(Player) Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->{
            if(method.getName().equals("isOnline"))return online.get();
            if(method.getName().equals("sendMessage")&&args[0] instanceof String s){assertTrue(online.get()&&enabled.get());messages.add(ChatColor.stripColor(s));return null;}
            if(method.getName().equals("toString"))return "test player";
            if(method.getReturnType()==boolean.class)return false;
            return null;
        });
        final PlayerStreamOutput output=new PlayerStreamOutput(null,player,scheduler,enabled::get,k->k,clock::get,()->{},java.util.logging.Logger.getAnonymousLogger(),1000,48,true,0);
        void reasoning(String text){output.accept(new AiStreamClient.Delta(text,""));}
    }
    @Test void reasoningFlushesWithoutAnyAnswerAt500msFromArrivalAndTickIsBounded(){
        var f=new Fixture();f.reasoning("short");f.clock.set(500_000_000);f.scheduler.run();
        assertEquals(List.of("reasoning-summary-label","short"),f.messages);assertEquals(4,f.scheduler.delay);
    }
    @Test void shortBufferWaitsThenFlushesOnScheduledTick(){
        var f=new Fixture();f.reasoning("a");f.scheduler.run();assertTrue(f.messages.isEmpty());
        f.clock.set(499_999_999);f.scheduler.tick();assertTrue(f.messages.isEmpty());
        f.clock.set(500_000_000);f.scheduler.tick();assertEquals(List.of("reasoning-summary-label","a"),f.messages);
    }
    @Test void finishFlushesRemainingBeforeSeparateAnswerWithoutRepeatingSummaryOrLabels(){
        var f=new Fixture();f.reasoning("first\n");f.scheduler.run();f.reasoning("last");
        f.output.accept(new AiStreamClient.Delta("","preview answer"));
        f.output.finish("final answer","first\nlast");f.output.finish("duplicate","duplicate");f.scheduler.run();
        assertEquals(List.of("reasoning-summary-label","first","last","answer-label","final answer"),f.messages);
    }
    @Test void failureFlushesPendingSummaryBeforeError(){
        var f=new Fixture();f.reasoning("partial");f.output.fail("error");f.scheduler.run();
        assertEquals(List.of("reasoning-summary-label","partial","error"),f.messages);
    }
    @Test void nonStreamingFallbackAndNoSummaryBothFinishNormally(){
        var f=new Fixture();f.output.finish("answer","summary");f.scheduler.run();
        assertEquals(List.of("reasoning-summary-label","summary","answer-label","answer"),f.messages);
        var empty=new Fixture();empty.output.finish("answer",null);empty.scheduler.run();
        assertEquals(List.of("answer-label","answer"),empty.messages);
    }
    @Test void unicodeChunksRespect240CodePointsAndNeverSplitSurrogates(){
        var f=new Fixture();String text="😀".repeat(481);f.reasoning(text);f.output.finish("answer",text);f.scheduler.run();
        assertEquals(List.of(240,240,1),f.messages.subList(1,4).stream().map(s->s.codePointCount(0,s.length())).toList());
        assertEquals(text,String.join("",f.messages.subList(1,4)));
    }
    @Test void concurrentAcceptPreservesEachProducerOrderWithoutLoss() throws Exception {
        var f=new Fixture();
        try(var pool=Executors.newFixedThreadPool(4)){
            List<Callable<Void>> tasks=new ArrayList<>();
            for(int i=0;i<4;i++){int producer=i;tasks.add(()->{for(int n=0;n<100;n++)f.reasoning(producer+":"+n+"\n");return null;});}
            for(var future:pool.invokeAll(tasks))future.get();
        }
        f.output.finish("answer",null);f.scheduler.run();
        List<String> body=f.messages.subList(1,401);assertEquals(400,new HashSet<>(body).size());
        for(int i=0;i<4;i++){List<String> expected=new ArrayList<>();for(int n=0;n<100;n++)expected.add(i+":"+n);int producer=i;assertEquals(expected,body.stream().filter(s->s.startsWith(producer+":")).toList());}
        assertEquals("answer-label",f.messages.get(401));
    }
    @Test void quitAndDisableSuppressAlreadyQueuedFinishFailureAndTicks(){
        var quit=new Fixture();quit.reasoning("pending");quit.output.finish("answer",null);quit.online.set(false);quit.output.closeNow();quit.scheduler.run();quit.scheduler.tick();assertTrue(quit.messages.isEmpty());
        var disable=new Fixture();disable.reasoning("pending");disable.output.fail("error");disable.enabled.set(false);disable.output.closeOnDisable();disable.scheduler.run();disable.scheduler.tick();assertTrue(disable.messages.isEmpty());
        assertThrows(CancellationException.class,()->disable.reasoning("late"));
    }
}
