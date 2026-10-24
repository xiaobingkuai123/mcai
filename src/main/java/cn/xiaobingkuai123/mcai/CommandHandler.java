package cn.xiaobingkuai123.mcai;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.*;

public class CommandHandler implements CommandExecutor, TabCompleter {
    private final McAI plugin;
    private final ChatManager chatManager;
    private final EconomyManager economyManager;

    public CommandHandler(McAI plugin, ChatManager chatManager, EconomyManager economyManager) {
        this.plugin = plugin;
        this.chatManager = chatManager;
        this.economyManager = economyManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof BlockCommandSender) {
            sender.sendMessage(plugin.languageManager.get("command-block-denied"));
            return true;
        }
        if (sender instanceof ConsoleCommandSender) return handleConsoleCommand(sender, args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.languageManager.get("invalid-command"));
            return true;
        }
        if (!player.hasPermission("mcai.use")) {
            player.sendMessage(plugin.languageManager.get("no-permission"));
            return true;
        }
        if (args.length == 0) { openMainMenu(player); return true; }
        if (args[0].equalsIgnoreCase("help")) { showHelp(sender); return true; }

        boolean isProMode = false;
        int argIndex = 0;
        if (args[argIndex].equalsIgnoreCase("pro")) { isProMode = true; argIndex++; }
        if (argIndex >= args.length) { player.sendMessage(plugin.languageManager.get("invalid-command")); return true; }

        String sub = args[argIndex].toLowerCase();
        switch (sub) {
            case "chat" -> handleChat(player, args, argIndex, isProMode);
            case "buy" -> handleBuy(player, args, argIndex);
            case "give" -> handleGive(player, args, argIndex);
            case "take" -> handleTake(player, args, argIndex);
            case "clear" -> {
                plugin.conversationHistory.remove(player.getUniqueId());
                player.sendMessage(plugin.languageManager.get("history-cleared"));
                plugin.soundManager.playHistoryCleared(player);
            }
            case "reload" -> {
                if (!player.hasPermission("mcai.admin")) { player.sendMessage(plugin.languageManager.get("no-permission")); return true; }
                if (performReload(sender)) player.sendMessage(plugin.languageManager.get("reload-success"));
                else player.sendMessage(plugin.languageManager.get("reload-fail"));
            }
            default -> player.sendMessage(plugin.languageManager.get("invalid-command"));
        }
        return true;
    }

    private boolean handleConsoleCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { showHelp(sender); return true; }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "chat" -> {
                int idx = 1; boolean think = false;
                if (idx < args.length && args[idx].equalsIgnoreCase("think")) { think = true; idx++; }
                if (idx >= args.length) { sender.sendMessage(plugin.languageManager.get("console-usage-chat")); return true; }
                sender.sendMessage(plugin.languageManager.get("console-thinking"));
                chatManager.askConsole(String.join(" ", Arrays.copyOfRange(args, idx, args.length)), false, think);
            }
            case "pro" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("chat")) { sender.sendMessage(plugin.languageManager.get("console-usage-pro")); return true; }
                int idx = 2; boolean think = false;
                if (idx < args.length && args[idx].equalsIgnoreCase("think")) { think = true; idx++; }
                if (idx >= args.length) { sender.sendMessage(plugin.languageManager.get("console-message-required")); return true; }
                sender.sendMessage(plugin.languageManager.get("console-thinking"));
                chatManager.askConsole(String.join(" ", Arrays.copyOfRange(args, idx, args.length)), true, think);
            }
            case "give" -> {
                if (args.length < 3) { sender.sendMessage(plugin.languageManager.get("console-usage-give")); return true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(plugin.languageManager.get("console-player-not-found", "player", args[1])); return true; }
                int amount; try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException e) { sender.sendMessage(plugin.languageManager.get("buy-invalid-number")); return true; }
                economyManager.givePoints(sender, target, amount);
            }
            case "take" -> {
                if (args.length < 3) { sender.sendMessage(plugin.languageManager.get("console-usage-take")); return true; }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) { sender.sendMessage(plugin.languageManager.get("console-player-not-found", "player", args[1])); return true; }
                int amount; try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException e) { sender.sendMessage(plugin.languageManager.get("buy-invalid-number")); return true; }
                economyManager.takePoints(sender, target, amount);
            }
            case "clear" -> { plugin.conversationHistory.remove(McAI.CONSOLE_UUID); sender.sendMessage(plugin.languageManager.get("console-clear")); }
            case "reload" -> { if (performReload(sender)) sender.sendMessage(plugin.languageManager.get("reload-success")); else sender.sendMessage(plugin.languageManager.get("reload-fail")); }
            case "buy" -> sender.sendMessage(plugin.languageManager.get("console-buy-disabled"));
            default -> sender.sendMessage(plugin.languageManager.get("console-usage"));
        }
        return true;
    }

    private void handleChat(Player player, String[] args, int idx, boolean pro) {
        idx++;
        boolean think = false;
        if (idx < args.length && args[idx].equalsIgnoreCase("think")) { think = true; idx++; }
        String question = idx < args.length ? String.join(" ", Arrays.copyOfRange(args, idx, args.length)) : "";
        String mode = pro ? (think ? "pro_think" : "pro") : (think ? "normal_think" : "normal");
        if (question.isEmpty()) {
            plugin.selectedChatMode.put(player.getUniqueId(), mode);
            plugin.isProMode.put(player.getUniqueId(), pro);
            startChatInput(player);
            return;
        }
        plugin.selectedChatMode.put(player.getUniqueId(), mode);
        if (!economyManager.performPreChecks(player, question, mode)) return;
        plugin.soundManager.playThinkingStart(player);
        player.sendMessage(plugin.languageManager.get("thinking"));
        chatManager.askPlayer(player, question, mode);
    }

    private void handleBuy(Player player, String[] args, int idx) {
        idx++;
        if (plugin.getEconomy() == null || !plugin.economyEnabled) { player.sendMessage(plugin.languageManager.get("buy-disabled")); return; }
        if (idx >= args.length) { economyManager.startPurchaseInput(player); return; }
        int amount; try { amount = Integer.parseInt(args[idx]); } catch (NumberFormatException e) { player.sendMessage(plugin.languageManager.get("buy-invalid-number")); return; }
        economyManager.performPurchase(player, amount);
    }

    private void handleGive(Player player, String[] args, int idx) {
        if (!player.hasPermission("mcai.admin")) { player.sendMessage(plugin.languageManager.get("no-permission")); return; }
        idx++;
        if (idx + 1 >= args.length) { player.sendMessage(plugin.languageManager.get("console-usage-give")); return; }
        Player target = Bukkit.getPlayer(args[idx]);
        if (target == null) { player.sendMessage(plugin.languageManager.get("player-not-found", "player", args[idx])); return; }
        int amount; try { amount = Integer.parseInt(args[idx + 1]); } catch (NumberFormatException e) { player.sendMessage(plugin.languageManager.get("buy-invalid-number")); return; }
        economyManager.givePoints(player, target, amount);
    }

    private void handleTake(Player player, String[] args, int idx) {
        if (!player.hasPermission("mcai.admin")) { player.sendMessage(plugin.languageManager.get("no-permission")); return; }
        idx++;
        if (idx + 1 >= args.length) { player.sendMessage(plugin.languageManager.get("console-usage-take")); return; }
        Player target = Bukkit.getPlayer(args[idx]);
        if (target == null) { player.sendMessage(plugin.languageManager.get("player-not-found", "player", args[idx])); return; }
        int amount; try { amount = Integer.parseInt(args[idx + 1]); } catch (NumberFormatException e) { player.sendMessage(plugin.languageManager.get("buy-invalid-number")); return; }
        economyManager.takePoints(player, target, amount);
    }

    private void showHelp(CommandSender sender) {
        int freePoints = sender instanceof Player player ? economyManager.getRemainingPoints(player) : 10;
        sender.sendMessage(plugin.languageManager.get("help-header"));
        sender.sendMessage(plugin.languageManager.get("help-chat", "normal", String.valueOf(plugin.normalCostPoints)));
        sender.sendMessage(plugin.languageManager.get("help-chat-think", "normalthink", String.valueOf(plugin.normalThinkCostPoints)));
        sender.sendMessage(plugin.languageManager.get("help-pro-chat", "pro", String.valueOf(plugin.proCostPoints)));
        sender.sendMessage(plugin.languageManager.get("help-pro-chat-think", "prothink", String.valueOf(plugin.proThinkCostPoints)));
        if (plugin.economyEnabled && plugin.getEconomy() != null) {
            sender.sendMessage(plugin.languageManager.get("help-buy", "price", plugin.getEconomy().format(plugin.costPerPoint)));
        }
        sender.sendMessage(plugin.languageManager.get("help-give"));
        sender.sendMessage(plugin.languageManager.get("help-take"));
        sender.sendMessage(plugin.languageManager.get("help-clear"));
        sender.sendMessage(plugin.languageManager.get("help-reload"));
        sender.sendMessage(plugin.languageManager.get("help-help"));
        String priceDisplay = plugin.economyEnabled && plugin.getEconomy() != null ? plugin.getEconomy().format(plugin.costPerPoint) : "N/A";
        sender.sendMessage(plugin.languageManager.get("help-points", "free", String.valueOf(freePoints), "price", priceDisplay));
    }

    private void openMainMenu(Player player) {
        if (plugin.isFloodgateEnabled() && plugin.isBedrockPlayer(player)) {
            BedrockForms.openMainMenu(plugin, player);
            return;
        }
        plugin.soundManager.playMenuOpen(player);
        new JavaMainMenu(plugin, player).open();
    }

    private void startChatInput(Player player) {
        plugin.waitingInputType.put(player.getUniqueId(), "chat");
        player.closeInventory();
        int points = economyManager.getRemainingPoints(player);
        player.sendMessage(plugin.languageManager.get("chat-input-prompt"));
        player.sendMessage(plugin.languageManager.get("chat-input-balance", "points", String.valueOf(points)));
    }

    boolean performReload(CommandSender sender) {
        if (!plugin.isReloading.compareAndSet(false, true)) {
            sender.sendMessage(plugin.languageManager.get("reload-busy"));
            return false;
        }
        try {
            plugin.waitingInputType.clear();
            plugin.selectedChatMode.clear();
            plugin.isProMode.clear();
            plugin.initializeEconomy();
            plugin.refreshConfigurationCache();
            plugin.recreateHttpClient();
            plugin.languageManager.reload();
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning(plugin.languageManager.get("log-reload-failure", "error", e.getMessage()));
            return false;
        } finally {
            plugin.isReloading.set(false);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String s : new String[]{"chat", "pro", "buy", "give", "take", "clear", "reload", "help"}) {
                if (s.startsWith(prefix)) suggestions.add(s);
            }
        } else if (args.length == 2) {
            String firstArg = args[0].toLowerCase();
            if (firstArg.equals("pro")) { if ("chat".startsWith(args[1].toLowerCase())) suggestions.add("chat"); }
            else if (firstArg.equals("chat")) { if ("think".startsWith(args[1].toLowerCase())) suggestions.add("think"); }
            else if (firstArg.equals("buy")) { String p = args[1].toLowerCase(); if ("1".startsWith(p)) suggestions.add("1"); if ("5".startsWith(p)) suggestions.add("5"); if ("10".startsWith(p)) suggestions.add("10"); }
            else if (firstArg.equals("give") || firstArg.equals("take")) { String p = args[1].toLowerCase(); for (Player player : Bukkit.getOnlinePlayers()) if (player.getName().toLowerCase().startsWith(p)) suggestions.add(player.getName()); }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("pro") && args[1].equalsIgnoreCase("chat")) { if ("think".startsWith(args[2].toLowerCase())) suggestions.add("think"); }
            else if (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("take")) { String p = args[2].toLowerCase(); if ("1".startsWith(p)) suggestions.add("1"); if ("5".startsWith(p)) suggestions.add("5"); if ("10".startsWith(p)) suggestions.add("10"); }
        }
        return suggestions;
    }
}
