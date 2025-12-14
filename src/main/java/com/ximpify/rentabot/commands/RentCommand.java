package com.ximpify.rentabot.commands;

import com.ximpify.rentabot.RentABot;
import com.ximpify.rentabot.bot.BotStatus;
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
            case "stop", "pause" -> handleStop(player, args);
            case "resume", "continue", "unpause" -> handleResume(player, args);
            case "delete", "remove" -> handleDelete(player, args);
            case "list", "ls" -> handleList(player);
            case "tp", "teleport", "summon" -> handleTeleport(player, args);
            case "rename" -> handleRename(player, args);
            case "extend", "renew" -> handleExtend(player, args);
            case "info", "status" -> handleInfo(player, args);
            case "shop", "buy" -> plugin.getGUIManager().openShopMenu(player);
            case "help", "?" -> showHelp(player);
            case "version", "ver", "v" -> showVersion(player);
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
        
        // Get bot first to show remaining time in message
        var optBot = plugin.getBotManager().getBot(botName);
        if (optBot.isEmpty() || !optBot.get().getOwnerUUID().equals(player.getUniqueId())) {
            plugin.getMessageUtil().send(player, "general.bot-not-found", "bot", botName);
            return;
        }
        
        RentableBot bot = optBot.get();
        if (bot.getStatus() != BotStatus.ACTIVE) {
            plugin.getMessageUtil().send(player, "stop.already-stopped", "bot", botName);
            return;
        }
        
        // Calculate time remaining before stopping
        Duration remaining = Duration.between(Instant.now(), bot.getExpiresAt());
        long secondsRemaining = Math.max(0, remaining.toSeconds());
        
        RentalResult result = plugin.getRentalManager().stopRental(player, botName, false);
        
        if (result.success()) {
            String timeLeft = plugin.getRentalManager().formatTime(secondsRemaining);
            plugin.getMessageUtil().send(player, "stop.success", "bot", botName, "time", timeLeft);
            playSound(player, "on-stop");
        } else {
            String messageKey = result.messageKey().equals("not-found") ? "general.bot-not-found" : "stop." + result.messageKey();
            plugin.getMessageUtil().send(player, messageKey, "bot", botName);
        }
    }
    
    private void handleResume(Player player, String[] args) {
        if (!player.hasPermission("rentabot.resume")) {
            plugin.getMessageUtil().send(player, "general.no-permission");
            return;
        }
        
        if (args.length < 2) {
            plugin.getMessageUtil().send(player, "general.invalid-args");
            plugin.getMessageUtil().sendRaw(player, "&7Usage: &f/rentabot resume <name> [hours]");
            return;
        }
        
        String botName = args[1];
        
        // Verify bot exists and player owns it before proceeding
        var optBot = plugin.getBotManager().getBot(botName);
        if (optBot.isEmpty()) {
            plugin.getMessageUtil().send(player, "general.bot-not-found", "bot", botName);
            return;
        }
        
        if (!optBot.get().getOwnerUUID().equals(player.getUniqueId())) {
            plugin.getMessageUtil().send(player, "general.not-owner");
            return;
        }
        
        // Check if additional hours provided
        int additionalHours = 0;
        if (args.length >= 3) {
            try {
                additionalHours = Integer.parseInt(args[2]);
                if (additionalHours < 1) {
                    plugin.getMessageUtil().sendRaw(player, "&cHours must be at least 1!");
                    return;
                }
            } catch (NumberFormatException e) {
                plugin.getMessageUtil().sendRaw(player, "&cInvalid hours: &f" + args[2]);
                return;
            }
        }
        
        RentalResult result = plugin.getRentalManager().resumeRental(player, botName, additionalHours);
        
        if (result.success()) {
            // Re-fetch the bot to get updated expiry time after resume
            var resumedBot = plugin.getBotManager().getBot(botName);
            if (resumedBot.isPresent()) {
                Duration remaining = Duration.between(Instant.now(), resumedBot.get().getExpiresAt());
                String timeLeft = plugin.getRentalManager().formatTime(Math.max(0, remaining.toSeconds()));
                plugin.getMessageUtil().send(player, "resume.success", "bot", botName, "time", timeLeft);
            } else {
                plugin.getMessageUtil().send(player, "resume.success", "bot", botName, "time", "N/A");
            }
            playSound(player, "on-create");
        } else {
            String messageKey = switch (result.messageKey()) {
                case "not-found" -> "general.bot-not-found";
                case "not-owner" -> "general.not-owner";
                case "max-active-reached" -> "resume.max-active";
                case "already-active" -> "resume.already-active";
                case "no-time-remaining" -> "resume.no-time";
                case "insufficient-funds" -> "economy.insufficient-funds";
                default -> "resume." + result.messageKey();
            };
            if (result.args() != null && result.args().length > 0) {
                plugin.getMessageUtil().send(player, messageKey, parseResultArgs(result.messageKey(), result.args()));
            } else {
                plugin.getMessageUtil().send(player, messageKey, "bot", botName);
            }
        }
    }
    
    private void handleDelete(Player player, String[] args) {
        if (!player.hasPermission("rentabot.delete")) {
            plugin.getMessageUtil().send(player, "general.no-permission");
            return;
        }
        
        if (args.length < 2) {
            plugin.getMessageUtil().send(player, "general.invalid-args");
            plugin.getMessageUtil().sendRaw(player, "&7Usage: &f/rentabot delete <name> [confirm]");
            return;
        }
        
        String botName = args[1];
        
        // Check if bot exists and player owns it
        var optBot = plugin.getBotManager().getBot(botName);
        if (optBot.isEmpty() || !optBot.get().getOwnerUUID().equals(player.getUniqueId())) {
            plugin.getMessageUtil().send(player, "general.bot-not-found", "bot", botName);
            return;
        }
        
        // Require confirmation
        boolean confirmed = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
        
        if (!confirmed) {
            plugin.getMessageUtil().send(player, "delete.confirm", "bot", botName);
            plugin.getMessageUtil().sendRaw(player, "&7Type: &f/rentabot delete " + botName + " confirm");
            return;
        }
        
        RentalResult result = plugin.getRentalManager().deleteRental(player, botName, false);
        
        if (result.success()) {
            plugin.getMessageUtil().send(player, "delete.success", "bot", botName);
            playSound(player, "on-stop");
        } else {
            String messageKey = result.messageKey().equals("not-found") ? "general.bot-not-found" : "delete." + result.messageKey();
            plugin.getMessageUtil().send(player, messageKey, "bot", botName);
        }
    }
    
    private void handleList(Player player) {
        if (!player.hasPermission("rentabot.list")) {
            plugin.getMessageUtil().send(player, "general.no-permission");
            return;
        }
        
        var bots = plugin.getBotManager().getPlayerBots(player.getUniqueId());
        
        // Count by status
        int activeCount = 0, stoppedCount = 0, expiredCount = 0;
        for (RentableBot bot : bots) {
            switch (bot.getStatus()) {
                case ACTIVE -> activeCount++;
                case STOPPED -> stoppedCount++;
                case EXPIRED -> expiredCount++;
            }
        }
        
        int maxActive = plugin.getConfig().getInt("limits.max-active-bots", 3);
        int maxReserved = plugin.getConfig().getInt("limits.max-reserved-bots", 5);
        
        plugin.getMessageUtil().send(player, "list.header",
            "active", String.valueOf(activeCount),
            "stopped", String.valueOf(stoppedCount),
            "expired", String.valueOf(expiredCount),
            "max_active", String.valueOf(maxActive),
            "max_reserved", String.valueOf(maxReserved));
        
        if (bots.isEmpty()) {
            plugin.getMessageUtil().send(player, "list.empty");
        } else {
            for (RentableBot bot : bots) {
                String timeLeft;
                String statusIndicator;
                String statusName;
                
                switch (bot.getStatus()) {
                    case ACTIVE -> {
                        Duration remaining = Duration.between(Instant.now(), bot.getExpiresAt());
                        timeLeft = plugin.getRentalManager().formatTime(Math.max(0, remaining.toSeconds()));
                        statusIndicator = "&a●";
                        statusName = "ACTIVE";
                    }
                    case STOPPED -> {
                        timeLeft = plugin.getRentalManager().formatTime(bot.getRemainingSeconds());
                        statusIndicator = "&e●";
                        statusName = "PAUSED";
                    }
                    case EXPIRED -> {
                        timeLeft = "0s";
                        statusIndicator = "&c●";
                        statusName = "EXPIRED";
                    }
                    default -> {
                        timeLeft = "?";
                        statusIndicator = "&7●";
                        statusName = "UNKNOWN";
                    }
                }
                
                String connectionStatus = bot.isConnected() 
                    ? plugin.getMessageUtil().getRaw("list.status-online")
                    : plugin.getMessageUtil().getRaw("list.status-offline");
                
                plugin.getMessageUtil().send(player, "list.entry",
                    "bot", bot.getInternalName(),
                    "time", timeLeft,
                    "status", connectionStatus,
                    "state", statusName,
                    "indicator", statusIndicator);
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
    
    private void showVersion(Player player) {
        String version = plugin.getPluginMeta().getVersion();
        int totalBots = plugin.getBotManager().getAllBots().size();
        int connectedBots = (int) plugin.getBotManager().getAllBots().stream()
            .filter(RentableBot::isConnected).count();
        
        plugin.getMessageUtil().sendRaw(player, "");
        plugin.getMessageUtil().sendRaw(player, "&b&lRentABot &8- &7Bot Rental System");
        plugin.getMessageUtil().sendRaw(player, "&7Version: &f" + version);
        plugin.getMessageUtil().sendRaw(player, "&7Author: &fXimpify");
        plugin.getMessageUtil().sendRaw(player, "&7Bots: &a" + connectedBots + " online &7/ &f" + totalBots + " total");
        plugin.getMessageUtil().sendRaw(player, "&7GitHub: &fgithub.com/MubTaXim/RentABot");
        plugin.getMessageUtil().sendRaw(player, "");
    }
    
    private void playSound(Player player, String soundKey) {
        if (plugin.getConfig().getBoolean("notifications.sounds.enabled", true)) {
            String soundName = plugin.getConfig().getString("notifications.sounds." + soundKey);
            if (soundName != null) {
                try {
                    org.bukkit.Sound sound = org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.minecraft(soundName.toLowerCase()));
                    if (sound != null) {
                        player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
                    }
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
            completions.addAll(Arrays.asList("gui", "shop", "create", "stop", "resume", "delete", "list", "tp", "rename", "extend", "info", "help", "version"));
            // Add reload for admins
            if (player.hasPermission("rentabot.admin")) {
                completions.add("reload");
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            switch (sub) {
                case "stop" -> {
                    // Only suggest ACTIVE bots for stop
                    completions.addAll(plugin.getBotManager().getPlayerBots(player.getUniqueId())
                        .stream()
                        .filter(b -> b.getStatus() == BotStatus.ACTIVE)
                        .map(RentableBot::getInternalName)
                        .collect(Collectors.toList()));
                }
                case "resume" -> {
                    // Only suggest STOPPED or EXPIRED bots for resume
                    completions.addAll(plugin.getBotManager().getPlayerBots(player.getUniqueId())
                        .stream()
                        .filter(b -> b.getStatus() == BotStatus.STOPPED || b.getStatus() == BotStatus.EXPIRED)
                        .map(RentableBot::getInternalName)
                        .collect(Collectors.toList()));
                }
                case "delete" -> {
                    // Suggest all bots for delete
                    completions.addAll(plugin.getBotManager().getPlayerBots(player.getUniqueId())
                        .stream()
                        .map(RentableBot::getInternalName)
                        .collect(Collectors.toList()));
                }
                case "tp", "rename", "extend", "info" -> {
                    // Suggest all bot names
                    completions.addAll(plugin.getBotManager().getPlayerBots(player.getUniqueId())
                        .stream()
                        .map(RentableBot::getInternalName)
                        .collect(Collectors.toList()));
                }
                case "create" -> {
                    // Suggest hour amounts
                    completions.addAll(Arrays.asList("1", "6", "12", "24", "48", "72", "168"));
                }
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if (sub.equals("extend")) {
                completions.addAll(Arrays.asList("1", "6", "12", "24"));
            } else if (sub.equals("resume")) {
                // Suggest hours for resume
                completions.addAll(Arrays.asList("1", "6", "12", "24"));
            } else if (sub.equals("delete")) {
                completions.add("confirm");
            }
        }
        
        String input = args[args.length - 1].toLowerCase();
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(input))
            .collect(Collectors.toList());
    }
}
