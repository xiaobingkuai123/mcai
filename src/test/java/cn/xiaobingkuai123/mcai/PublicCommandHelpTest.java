package cn.xiaobingkuai123.mcai;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicCommandHelpTest {
    static CommandSender sender(boolean console, boolean admin, List<String> messages) {
        Class<?> type=console ? ConsoleCommandSender.class : Player.class;
        return (CommandSender) Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->switch(m.getName()) {
            case "hasPermission" -> !a[0].equals("mcai.admin") || admin;
            case "sendMessage" -> { messages.add((String)a[0]); yield null; }
            default -> m.getReturnType()==boolean.class ? false : null;
        });
    }
    @Test void finalUsageOutputIsFilteredForPlayersAdminsAndConsoleInBothLanguages() throws Exception {
        for(String language:List.of("zh_CN","en_US")) {
            var lang=LanguageManager.parseLanguage(Files.readString(Path.of("src/main/resources/lang/"+language+".yml")));
            for(int identity=0;identity<3;identity++) {
                List<String> messages=new ArrayList<>();
                var sender=sender(identity==2,identity==1,messages);
                CommandHandler.sendPublicUsage(sender,k->lang.getString(k));
                String output=String.join("\n",messages);
                assertTrue(output.contains("/ai public chat"));
                assertEquals(identity!=0,output.contains("/ai public clear"));
                assertEquals(identity!=0,output.contains("/ai public setname"));
                assertFalse(output.contains("@AI"));
            }
        }
    }
    @Test void publicTabCompletionUsesSameAdminBoundaryIncludingConsole() {
        var handler=new CommandHandler(null,null,null);
        assertEquals(List.of("chat"),handler.onTabComplete(sender(false,false,new ArrayList<>()),null,"ai",new String[]{"public",""}));
        for(var sender:List.of(sender(false,true,new ArrayList<>()),sender(true,false,new ArrayList<>())))
            assertEquals(List.of("chat","clear","setname"),handler.onTabComplete(sender,null,"ai",new String[]{"public",""}));
    }
}
