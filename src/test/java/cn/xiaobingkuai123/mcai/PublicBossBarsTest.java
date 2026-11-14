package cn.xiaobingkuai123.mcai;

import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicBossBarsTest {
    static class Recipient {
        final UUID id = UUID.randomUUID();
        boolean online = true;
        final Set<Object> shown = new HashSet<>();
        final List<String> chat = new ArrayList<>();
        final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class[]{Player.class}, (p,m,a) -> switch(m.getName()) {
            case "getUniqueId" -> id;
            case "isOnline" -> online;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "showBossBar" -> { shown.add(a[0]); yield null; }
            case "hideBossBar" -> { shown.remove(a[0]); yield null; }
            case "sendMessage" -> { chat.add(String.valueOf(a[0])); yield null; }
            default -> m.getReturnType() == boolean.class ? false : null;
        });
    }
    @Test void allUsesSeparateBarsNoDeltaChatAndCleansEveryRecipient() {
        var scheduler = new PlayerStreamOutputTest.Scheduler();
        var a = new Recipient(); var b = new Recipient();
        var bars = new PublicAiManager.PublicBossBars(scheduler, () -> true, k -> k, null);
        bars.accept(new AiStreamClient.Delta("summary", ""));
        bars.refresh(List.of(a.player,b.player)); scheduler.run();
        assertEquals(1,a.shown.size()); assertEquals(1,b.shown.size());
        assertNotSame(a.shown.iterator().next(),b.shown.iterator().next());
        bars.accept(new AiStreamClient.Delta("", "answer"));
        a.online=false;
        bars.refresh(List.of(a.player,b.player)); scheduler.run();
        assertEquals(1,b.shown.size()); assertTrue(a.chat.isEmpty()); assertTrue(b.chat.isEmpty());
        bars.close(); scheduler.run(); assertTrue(a.shown.isEmpty()); assertTrue(b.shown.isEmpty());
    }
    @Test void askerOnlyAndClosedOutputDoesNotReplacePrivateBar() {
        var scheduler = new PlayerStreamOutputTest.Scheduler();
        var a=new Recipient(); var b=new Recipient();
        Object privateBar=new Object(); a.shown.add(privateBar);
        var bars=new PublicAiManager.PublicBossBars(scheduler,()->true,k->k,a.id);
        bars.refresh(List.of(a.player,b.player)); scheduler.run();
        assertEquals(2,a.shown.size()); assertTrue(b.shown.isEmpty());
        bars.close(); scheduler.run();
        bars.accept(new AiStreamClient.Delta("","late")); bars.refresh(List.of(a.player)); scheduler.run();
        assertEquals(Set.of(privateBar),a.shown);
    }
    @Test void reasoningPreviewCannotReplaceAnswerAndInvalidatedOutputStopsUpdating() {
        var scheduler=new PlayerStreamOutputTest.Scheduler();
        var recipient=new Recipient(); var active=new AtomicBoolean(true);
        var bars=new PublicAiManager.PublicBossBars(scheduler,active::get,k->k,null);
        bars.accept(new AiStreamClient.Delta("summary","")); bars.refresh(List.of(recipient.player)); scheduler.run();
        var bar=(net.kyori.adventure.bossbar.BossBar)recipient.shown.iterator().next();
        bars.accept(new AiStreamClient.Delta("","answer")); bars.refresh(List.of(recipient.player)); scheduler.run();
        var answerName=bar.name();
        bars.accept(new AiStreamClient.Delta("late summary","")); bars.refresh(List.of(recipient.player)); scheduler.run();
        assertEquals(answerName,bar.name());
        active.set(false); bars.accept(new AiStreamClient.Delta("","late answer"));
        bars.refresh(List.of(recipient.player)); scheduler.run(); assertEquals(answerName,bar.name());
        bars.close(); scheduler.run(); assertTrue(recipient.shown.isEmpty());
    }
}
