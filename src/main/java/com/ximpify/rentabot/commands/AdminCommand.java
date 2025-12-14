package com.ximpify.rentabot.commands;

import com.ximpify.rentabot.RentABot;
import com.ximpify.rentabot.bot.RentableBot;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Admin command handler for /rentabotadmin
 */
public class AdminCommand implements CommandExecutor, TabCompleter {
    
    private final RentABot plugin;
    
    public AdminCommand(RentABot plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("rentabot.admin")) {
            plugin.getMessageUtil().send(sender, "general.no-permission");
            return true;
        }
        
        if (args.length == 0) {
            showAdminHelp(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "list", "ls" -> handleList(sender);
            case "stop", "remove" -> handleStop(sender, args);
            case "stopall", "clear" -> handleStopAll(sender);
            case "info" -> handleInfo(sender, args);
            case "give", "create" -> handleGive(sender, args);
            case "reload" -> handleReload(sender);
            case "debug" -> handleDebug(sender);
            case "help", "?" -> showAdminHelp(sender);
            default -> plugin.getMessageUtil().send(sender, "general.invalid-args");
        }
        
        return true;
    }
    
    private void handleList(CommandSender sender) {
        var bots = plugin.getBotManager().getAllBots();
        
        plugin.getMessageUtil().send(sender, "admin.list-header");
        
        if (bots.isEmpty()) {
            plugin.getMessageUtil().send(sender, "admin.list-empty");
        } else {
            for (RentableBot bot : bots) {
                Duration remaining = Duration.between(Instant.now(), bot.getExpiresAt());
                String timeLeft = plugin.getRentalManager().formatTime(Math.max(0, remaining.toSeconds()));
                
                plugin.getMessageUtil().send(sender, "admin.list-entry",
                    "bot", bot.getInternalName(),
                    "player", bot.getOwnerName(),
                    "time", timeLeft);
            }
        }
        
        plugin.getMessageUtil().sendRaw(sender, "&8&m--------------------------------");
        plugin.getMessageUtil().sendRaw(sender, "&7Total bots: &f" + bots.size());
    }
    
    private void handleStop(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getMessageUtil().send(sender, "general.invalid-args");
            plugin.getMessageUtil().sendRaw(sender, "&7Usage: &f/rabadmin stop <name>");
            return;
        }
        
        String botName = args[1];
        var optBot = plugin.getBotManager().getBot(botName);
        
        if (optBot.isEmpty()) {
            plugin.getMessageUtil().send(sender, "general.bot-not-found", "bot", botName);
            return;
        }
        
        RentableBot bot = optBot.get();
        String ownerName = bot.getOwnerName();
        
        plugin.getBotManager().stopBot(botName);
        plugin.getStorageManager().deleteRental(botName);
        
        plugin.getMessageUtil().send(sender, "admin.stop-success", "bot", botName, "player", ownerName);
        
        // Notify owner if online
        Player owner = plugin.getServer().getPlayer(bot.getOwnerUUID());
        if (owner != null) {
            plugin.getMessageUtil().sendRaw(owner, "&c&lAdmin stopped your bot: &f" + botName);
        }
    }
    
    private void handleStopAll(CommandSender sender) {
        int count = plugin.getBotManager().getTotalBotCount();
        
        if (count == 0) {
            plugin.getMessageUtil().send(sender, "admin.stopall-empty");
            return;
        }
        
        // Stop all bots
        for (RentableBot bot : new ArrayList<>(plugin.getBotManager().getAllBots())) {
            plugin.getBotManager().stopBot(bot.getInternalName());
            plugin.getStorageManager().deleteRental(bot.getInternalName());
        }
        
        plugin.getMessageUtil().send(sender, "admin.stopall-success", "count", String.valueOf(count));
    }
    
    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getMessageUtil().send(sender, "general.invalid-args");
            plugin.getMessageUtil().sendRaw(sender, "&7Usage: &f/rabadmin info <name>");
            return;
        }
        
        String botName = args[1];
        var optBot = plugin.getBotManager().getBot(botName);
        
        if (optBot.isEmpty()) {
            plugin.getMessageUtil().send(sender, "general.bot-not-found", "bot", botName);
            return;
        }
        
        RentableBot bot = optBot.get();
        Duration remaining = Duration.between(Instant.now(), bot.getExpiresAt());
        
        String status = bot.isConnected() 
            ? plugin.getMessageUtil().getRaw("list.status-online")
            : plugin.getMessageUtil().getRaw("list.status-offline");
        
        plugin.getMessageUtil().send(sender, "admin.info-header");
        plugin.getMessageUtil().send(sender, "admin.info-name", "bot", bot.getInternalName());
        plugin.getMessageUtil().send(sender, "admin.info-owner", "player", bot.getOwnerName());
        plugin.getMessageUtil().send(sender, "admin.info-created", 
            "time", bot.getCreatedAt().toString());
        plugin.getMessageUtil().send(sender, "admin.info-expires", 
            "time", plugin.getRentalManager().formatTime(Math.max(0, remaining.toSeconds())));
        
        if (bot.getWorld() != null) {
            plugin.getMessageUtil().send(sender, "admin.info-location",
                "world", bot.getWorld(),
                "x", String.valueOf((int) bot.getX()),
                "y", String.valueOf((int) bot.getY()),
                "z", String.valueOf((int) bot.getZ()));
        }
        
        plugin.getMessageUtil().send(sender, "admin.info-status", "status", status);
    }
    
    private void handleReload(CommandSender sender) {
        plugin.reload();
        plugin.getMessageUtil().send(sender, "general.reload-success");
    }
    
    private void handleDebug(CommandSender sender) {
        boolean current = plugin.getConfig().getBoolean("advanced.debug", false);
        plugin.getConfig().set("advanced.debug", !current);
        plugin.saveConfig();
        
        plugin.getMessageUtil().sendRaw(sender, "&7Debug mode: " + (!current ? "&aEnabled" : "&cDisabled"));
    }
    
    /**
     * Handles giving a bot to a player (for ShopGUIPlus/console - NO CHARGE)
     * Usage: /rabadmin give <player> <hours> [botname]
     */
    private void handleGive(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.getMessageUtil().sendRaw(sender, "&cUsage: /rabadmin give <player> <hours> [botname]");
            return;
        }
        
        // Get target player
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            plugin.getMessageUtil().send(sender, "general.player-not-found", "player", args[1]);
            return;
        }
        
        // Parse hours
        int hours;
        try {
            hours = Integer.parseInt(args[2]);
            if (hours < 1) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            plugin.getMessageUtil().sendRaw(sender, "&cInvalid hours! Must be a positive number.");
            return;
        }
        
        // Get or generate bot name
        String botName;
        if (args.length >= 4) {
            botName = args[3];
        } else {
            botName = target.getName() + "_Bot" + 
                (plugin.getBotManager().getPlayerBotCount(target.getUniqueId()) + 1);
        }
        
        // Check if name is available
        if (!plugin.getBotManager().isNameAvailable(botName)) {
            plugin.getMessageUtil().sendRaw(sender, "&cBot name '" + botName + "' is already taken!");
            return;
        }
        
        // Check player's bot limit (still enforced even for gifts)
        int maxBots = plugin.getConfig().getInt("limits.max-bots-per-player", 3);
        int currentBots = plugin.getBotManager().getPlayerBotCount(target.getUniqueId());
        if (maxBots > 0 && currentBots >= maxBots && !target.hasPermission("rentabot.bypass.limit")) {
            plugin.getMessageUtil().sendRaw(sender, "&cPlayer has reached bot limit (" + currentBots + "/" + maxBots + ")");
            return;
        }
        
        // Create the bot (NO ECONOMY CHARGE!)
        RentableBot bot = plugin.getBotManager().createBot(botName, target.getUniqueId(), target.getName(), hours);
        
        if (bot != null) {
            // Save to storage
            plugin.getStorageManager().saveRental(bot);
            
            // Notify admin
            plugin.getMessageUtil().sendRaw(sender, "&aGave bot '&f" + botName + "&a' to &f" + target.getName() + 
                " &afor &f" + hours + " &ahour(s) &7(free - no charge)");
            
            // Notify target player
            plugin.getMessageUtil().send(target, "create.success",
                "bot", botName,
                "hours", String.valueOf(hours),
                "price", "Free (Gift)");
        } else {
            plugin.getMessageUtil().sendRaw(sender, "&cFailed to create bot! Check console for errors.");
        }
    }
    
    private void showAdminHelp(CommandSender sender) {
        plugin.getMessageUtil().send(sender, "help.admin-header");
        for (String line : plugin.getMessageUtil().getStringList("help.admin-commands")) {
            plugin.getMessageUtil().sendRaw(sender, line);
        }
        plugin.getMessageUtil().send(sender, "help.admin-footer");
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("rentabot.admin")) {
            return List.of();
        }
        
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.addAll(Arrays.asList("list", "stop", "stopall", "info", "give", "reload", "debug", "help"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("stop") || sub.equals("info")) {
                // Suggest all bot names
                completions.addAll(plugin.getBotManager().getAllBots()
                    .stream()
                    .map(RentableBot::getInternalName)
                    .collect(Collectors.toList()));
            } else if (sub.equals("give") || sub.equals("create")) {
                // Suggest online players
                completions.addAll(Bukkit.getOnlinePlayers()
                    .stream()
                    .map(Player::getName)
                    .collect(Collectors.toList()));
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if (sub.equals("give") || sub.equals("create")) {
                // Suggest hour amounts
                completions.addAll(Arrays.asList("1", "6", "12", "24", "48", "72", "168"));
            }
        }
        
        String input = args[args.length - 1].toLowerCase();
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(input))
            .collect(Collectors.toList());
    }
}
