package com.ximpify.rentabot.commands;

import com.ximpify.rentabot.RentABot;
import com.ximpify.rentabot.bot.RentableBot;
import com.ximpify.rentabot.rental.RentalManager.RentalResult;
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
 * Main command handler for /rentabot
 */
public class RentCommand implements CommandExecutor, TabCompleter {
    
    private final RentABot plugin;
    
    public RentCommand(RentABot plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Handle reload command for console/admin (before player check)
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("rentabot.admin")) {
                plugin.getMessageUtil().send(sender, "general.no-permission");
                return true;
            }
            plugin.reload();
            plugin.getMessageUtil().send(sender, "general.reload-success");
            return true;
        }
        
        if (!(sender instanceof Player player)) {
            plugin.getMessageUtil().send(sender, "general.player-only");
            return true;
        }
        
        if (!player.hasPermission("rentabot.use")) {
            plugin.getMessageUtil().send(player, "general.no-permission");
            return true;
        }
        
        if (args.length == 0) {
            // Open GUI when no arguments
            plugin.getGUIManager().openMainMenu(player);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "gui", "menu" -> plugin.getGUIManager().openMainMenu(player);
            case "create", "rent", "new" -> handleCreate(player, args);
            case "stop", "remove", "delete" -> handleStop(player, args);
            case "list", "ls" -> handleList(player);
            case "tp", "teleport", "summon" -> handleTeleport(player, args);
            case "rename" -> handleRename(player, args);
            case "extend", "renew" -> handleExtend(player, args);
            case "info", "status" -> handleInfo(player, args);
            case "shop", "buy" -> plugin.getGUIManager().openShopMenu(player);
            case "help", "?" -> showHelp(player);
            default -> plugin.getMessageUtil().send(player, "general.invalid-args");
        }
        
        return true;
    }
    
    private void handleCreate(Player player, String[] args) {
        if (!player.hasPermission("rentabot.create")) {
            plugin.getMessageUtil().send(player, "general.no-permission");
            return;
        }
        
        if (args.length < 2) {
            plugin.getMessageUtil().send(player, "general.invalid-args");
            plugin.getMessageUtil().sendRaw(player, "&7Usage: &f/rentabot create <hours> [name]");
            return;
        }
        
        // Parse hours
        int hours;
        try {
            hours = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            plugin.getMessageUtil().send(player, "create.invalid-hours",
                "min", String.valueOf(plugin.getConfig().getInt("economy.min-hours", 1)),
                "max", String.valueOf(plugin.getConfig().getInt("economy.max-hours", 168)));
            return;
        }
        
        // Get or generate bot name
        String botName;
        if (args.length >= 3) {
            botName = args[2];
        } else {
            botName = player.getName() + "_Bot" + (plugin.getBotManager().getPlayerBotCount(player.getUniqueId()) + 1);
        }
        
        // Create rental
        RentalResult result = plugin.getRentalManager().createRental(player, botName, hours);
        
        if (result.success()) {
            // Show success message
            String price = result.args().length > 2 ? result.args()[2] : "Free";
            plugin.getMessageUtil().send(player, "create.success",
                "bot", botName,
                "hours", String.valueOf(hours),
                "price", price);
            
            // Play sound
            playSound(player, "on-create");
        } else {
            // Show error message
            String messageKey = "create." + result.messageKey();
            if (result.args().length > 0) {
                plugin.getMessageUtil().send(player, messageKey, 
                    parseResultArgs(result.messageKey(), result.args()));
            } else {
                plugin.getMessageUtil().send(player, messageKey);
            }
        }
    }
    
    private void handleStop(Player player, String[] args) {
        if (!player.hasPermission("rentabot.stop")) {
            plugin.getMessageUtil().send(player, "general.no-permission");
            return;
        }
        
        if (args.length < 2) {
            plugin.getMessageUtil().send(player, "general.invalid-args");
            plugin.getMessageUtil().sendRaw(player, "&7Usage: &f/rentabot stop <name>");
            return;
        }
        
        String botName = args[1];
        RentalResult result = plugin.getRentalManager().stopRental(player, botName, false);
        
        if (result.success()) {
            plugin.getMessageUtil().send(player, "stop.success", "bot", botName);
            if (result.args().length > 1 && !result.args()[1].equals("0")) {
                plugin.getMessageUtil().send(player, "economy.refunded", "price", result.args()[1]);
            }
            playSound(player, "on-stop");
        } else {
            String messageKey = result.messageKey().equals("not-found") ? "general.bot-not-found" : "stop." + result.messageKey();
            plugin.getMessageUtil().send(player, messageKey, "bot", botName);
        }
    }
    
    private void handleList(Player player) {
        if (!player.hasPermission("rentabot.list")) {
            plugin.getMessageUtil().send(player, "general.no-permission");
            return;
        }
        
        var bots = plugin.getBotManager().getPlayerBots(player.getUniqueId());
        
        plugin.getMessageUtil().send(player, "list.header");
        
        if (bots.isEmpty()) {
            plugin.getMessageUtil().send(player, "list.empty");
        } else {
            for (RentableBot bot : bots) {
                Duration remaining = Duration.between(Instant.now(), bot.getExpiresAt());
                String timeLeft = plugin.getRentalManager().formatTime(Math.max(0, remaining.toSeconds()));
                String status = bot.isConnected() 
                    ? plugin.getMessageUtil().getRaw("list.status-online")
                    : plugin.getMessageUtil().getRaw("list.status-offline");
                
                plugin.getMessageUtil().send(player, "list.entry",
                    "bot", bot.getInternalName(),
                    "time", timeLeft,
                    "status", status);
            }
        }
        
        plugin.getMessageUtil().send(player, "list.footer");
    }
    
    private void handleTeleport(Player player, String[] args) {
        if (!player.hasPermission("rentabot.tp")) {
            plugin.getMessageUtil().send(player, "general.no-permission");
            return;
        }
        
        if (args.length < 2) {
            plugin.getMessageUtil().send(player, "general.invalid-args");
            plugin.getMessageUtil().sendRaw(player, "&7Usage: &f/rentabot tp <name>");
            return;
        }
        
        String botName = args[1];
        var optBot = plugin.getBotManager().getBot(botName);
        
        if (optBot.isEmpty()) {
            plugin.getMessageUtil().send(player, "general.bot-not-found", "bot", botName);
            return;
        }
        
        RentableBot bot = optBot.get();
        
        // Check ownership
        if (!bot.getOwnerUUID().equals(player.getUniqueId()) && !player.hasPermission("rentabot.admin")) {
            plugin.getMessageUtil().send(player, "stop.not-owner");
            return;
        }
        
        // Teleport bot to player
        if (plugin.getBotManager().teleportBot(botName, player.getLocation())) {
            plugin.getMessageUtil().send(player, "tp.success", "bot", botName);
        } else {
            plugin.getMessageUtil().send(player, "tp.failed", "bot", botName, "reason", "Bot is not connected");
        }
    }
    
    private void handleRename(Player player, String[] args) {
        if (!player.hasPermission("rentabot.rename")) {
            plugin.getMessageUtil().send(player, "general.no-permission");
            return;
        }
        
        if (args.length < 3) {
            plugin.getMessageUtil().send(player, "general.invalid-args");
            plugin.getMessageUtil().sendRaw(player, "&7Usage: &f/rentabot rename <oldname> <newname>");
            return;
        }
        
        String oldName = args[1];
        String newName = args[2];
        
        var optBot = plugin.getBotManager().getBot(oldName);
        
        if (optBot.isEmpty()) {
            plugin.getMessageUtil().send(player, "general.bot-not-found", "bot", oldName);
            return;
        }
        
        RentableBot bot = optBot.get();
        
        // Check ownership
        if (!bot.getOwnerUUID().equals(player.getUniqueId()) && !player.hasPermission("rentabot.admin")) {
            plugin.getMessageUtil().send(player, "stop.not-owner");
            return;
        }
        
        // Check if name is same
        if (oldName.equalsIgnoreCase(newName)) {
            plugin.getMessageUtil().send(player, "rename.same-name");
            return;
        }
        
        // Check if new name is available
        if (!plugin.getBotManager().isNameAvailable(newName)) {
            plugin.getMessageUtil().send(player, "create.name-taken");
            return;
        }
        
        // Rename
        if (plugin.getBotManager().renameBot(oldName, newName)) {
            plugin.getMessageUtil().send(player, "rename.success", "old", oldName, "bot", newName);
        }
    }
    
    private void handleExtend(Player player, String[] args) {
        if (!player.hasPermission("rentabot.create")) {
            plugin.getMessageUtil().send(player, "general.no-permission");
            return;
        }
        
        if (!plugin.getConfig().getBoolean("rentals.allow-extend", true)) {
            plugin.getMessageUtil().sendRaw(player, "&cRental extensions are disabled.");
            return;
        }
        
        if (args.length < 3) {
            plugin.getMessageUtil().send(player, "general.invalid-args");
            plugin.getMessageUtil().sendRaw(player, "&7Usage: &f/rentabot extend <name> <hours>");
            return;
        }
        
        String botName = args[1];
        int hours;
        try {
            hours = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            plugin.getMessageUtil().send(player, "create.invalid-hours",
                "min", String.valueOf(plugin.getConfig().getInt("economy.min-hours", 1)),
                "max", String.valueOf(plugin.getConfig().getInt("economy.max-hours", 168)));
            return;
        }
        
        RentalResult result = plugin.getRentalManager().extendRental(player, botName, hours);
        
        if (result.success()) {
            plugin.getMessageUtil().send(player, "extend.success",
                "hours", result.args()[0],
                "time", result.args()[1]);
        } else {
            String messageKey = result.messageKey().equals("not-found") ? "general.bot-not-found" : "extend." + result.messageKey();
            plugin.getMessageUtil().send(player, messageKey, "bot", botName);
        }
    }
    
    private void handleInfo(Player player, String[] args) {
        if (args.length < 2) {
            plugin.getMessageUtil().send(player, "general.invalid-args");
            plugin.getMessageUtil().sendRaw(player, "&7Usage: &f/rentabot info <name>");
            return;
        }
        
        String botName = args[1];
        var optBot = plugin.getBotManager().getBot(botName);
        
        if (optBot.isEmpty()) {
            plugin.getMessageUtil().send(player, "general.bot-not-found", "bot", botName);
            return;
        }
        
        RentableBot bot = optBot.get();
        
        // Check ownership (unless admin)
        if (!bot.getOwnerUUID().equals(player.getUniqueId()) && !player.hasPermission("rentabot.admin")) {
            plugin.getMessageUtil().send(player, "stop.not-owner");
            return;
        }
        
        Duration remaining = Duration.between(Instant.now(), bot.getExpiresAt());
        String status = bot.isConnected() 
            ? plugin.getMessageUtil().getRaw("list.status-online")
            : plugin.getMessageUtil().getRaw("list.status-offline");
        
        plugin.getMessageUtil().send(player, "admin.info-header");
        plugin.getMessageUtil().send(player, "admin.info-name", "bot", bot.getInternalName());
        plugin.getMessageUtil().send(player, "admin.info-owner", "player", bot.getOwnerName());
        plugin.getMessageUtil().send(player, "admin.info-expires", 
            "time", plugin.getRentalManager().formatTime(Math.max(0, remaining.toSeconds())));
        plugin.getMessageUtil().send(player, "admin.info-status", "status", status);
        
        // Show additional status info
        if (bot.isConnected()) {
            // Health and food
            plugin.getMessageUtil().sendRaw(player, "&7Health: &c" + String.format("%.1f", bot.getHealth()) + 
                " &8/ &7Food: &e" + bot.getFood());
            
            // Position
            if (bot.isPositionInitialized()) {
                plugin.getMessageUtil().sendRaw(player, "&7Position: &f" + 
                    String.format("%.0f, %.0f, %.0f", bot.getX(), bot.getY(), bot.getZ()));
            }
            
            // Spawn point
            if (bot.hasSpawnPoint()) {
                plugin.getMessageUtil().sendRaw(player, "&7Spawn Point: &a" + 
                    String.format("%.0f, %.0f, %.0f", bot.getSavedX(), bot.getSavedY(), bot.getSavedZ()) +
                    " &7(saved)");
            } else {
                plugin.getMessageUtil().sendRaw(player, "&7Spawn Point: &cnone &8(use /tpa to set)");
            }
            
            // Uptime
            plugin.getMessageUtil().sendRaw(player, "&7Uptime: &f" + bot.getUptime());
        }
    }
    
    private void showHelp(Player player) {
        plugin.getMessageUtil().send(player, "help.header");
        for (String line : plugin.getMessageUtil().getStringList("help.commands")) {
            plugin.getMessageUtil().sendRaw(player, line);
        }
        plugin.getMessageUtil().send(player, "help.footer");
    }
    
    private void playSound(Player player, String soundKey) {
        if (plugin.getConfig().getBoolean("notifications.sounds.enabled", true)) {
            String soundName = plugin.getConfig().getString("notifications.sounds." + soundKey);
            if (soundName != null) {
                try {
                    player.playSound(player.getLocation(), 
                        org.bukkit.Sound.valueOf(soundName), 1.0f, 1.0f);
                } catch (Exception ignored) {}
            }
        }
    }
    
    private String[] parseResultArgs(String key, String[] args) {
        return switch (key) {
            case "cooldown" -> new String[]{"time", args[0]};
            case "invalid-hours" -> new String[]{"min", args[0], "max", args[1]};
            case "limit-reached" -> new String[]{"count", args[0], "max", args[1]};
            case "invalid-name" -> new String[]{"reason", args[0]};
            case "not-enough-money" -> new String[]{"price", args[0], "balance", args[1]};
            default -> new String[]{};
        };
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            completions.addAll(Arrays.asList("gui", "shop", "create", "stop", "list", "tp", "rename", "extend", "info", "help"));
            // Add reload for admins
            if (player.hasPermission("rentabot.admin")) {
                completions.add("reload");
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("stop") || sub.equals("tp") || sub.equals("rename") || 
                sub.equals("extend") || sub.equals("info")) {
                // Suggest player's bot names
                completions.addAll(plugin.getBotManager().getPlayerBots(player.getUniqueId())
                    .stream()
                    .map(RentableBot::getInternalName)
                    .collect(Collectors.toList()));
            } else if (sub.equals("create")) {
                // Suggest hour amounts
                completions.addAll(Arrays.asList("1", "6", "12", "24", "48", "72", "168"));
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("extend")) {
                completions.addAll(Arrays.asList("1", "6", "12", "24"));
            }
        }
        
        String input = args[args.length - 1].toLowerCase();
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(input))
            .collect(Collectors.toList());
    }
}
