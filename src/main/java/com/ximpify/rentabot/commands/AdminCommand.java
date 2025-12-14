package com.ximpify.rentabot.commands;

import com.ximpify.rentabot.RentABot;
import com.ximpify.rentabot.bot.RentableBot;
import com.ximpify.rentabot.util.ReloadManager;
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
            case "reload" -> handleReload(sender, args);
            case "update" -> handleUpdate(sender, args);
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
        
        // Use deleteBot to fully remove the bot (not stopBot which only pauses)
        plugin.getBotManager().deleteBot(botName);
        plugin.getStorageManager().deleteRental(botName);
        
        plugin.getMessageUtil().send(sender, "admin.stop-success", "bot", botName, "player", ownerName);
        
        // Notify owner if online
        Player owner = plugin.getServer().getPlayer(bot.getOwnerUUID());
        if (owner != null) {
            plugin.getMessageUtil().sendRaw(owner, "&c&lAdmin removed your bot: &f" + botName);
        }
    }
    
    private void handleStopAll(CommandSender sender) {
        int count = plugin.getBotManager().getTotalAllBotsCount();
        
        if (count == 0) {
            plugin.getMessageUtil().send(sender, "admin.stopall-empty");
            return;
        }
        
        // Stop all bots (use copy to avoid ConcurrentModificationException)
        // Use deleteBot for full removal
        for (RentableBot bot : new ArrayList<>(plugin.getBotManager().getAllBots())) {
            String botName = bot.getInternalName();
            plugin.getBotManager().deleteBot(botName);
            plugin.getStorageManager().deleteRental(botName);
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
    
    /**
     * Comprehensive reload command with subcommands.
     * Usage: /rabadmin reload [all|config|messages|tasks|hooks]
     */
    private void handleReload(CommandSender sender, String[] args) {
        String subType = args.length >= 2 ? args[1].toLowerCase() : "all";
        ReloadManager rm = plugin.getReloadManager();
        ReloadManager.ReloadResult result;
        
        plugin.getMessageUtil().sendRaw(sender, "&8&m----------------------------------------");
        plugin.getMessageUtil().sendRaw(sender, "&6&lRentABot Reload");
        plugin.getMessageUtil().sendRaw(sender, "&8&m----------------------------------------");
        
        switch (subType) {
            case "config" -> {
                plugin.getMessageUtil().sendRaw(sender, "&7Reloading &fconfig.yml&7...");
                result = rm.reloadConfig();
                sendReloadResult(sender, result);
            }
            case "messages" -> {
                plugin.getMessageUtil().sendRaw(sender, "&7Reloading &fmessages.yml&7...");
                result = rm.reloadMessages();
                sendReloadResult(sender, result);
            }
            case "tasks" -> {
                plugin.getMessageUtil().sendRaw(sender, "&7Rescheduling tasks...");
                result = rm.rescheduleTasks();
                sendReloadResult(sender, result);
            }
            case "hooks" -> {
                plugin.getMessageUtil().sendRaw(sender, "&7Re-validating hooks...");
                result = rm.revalidateHooks();
                sendReloadResult(sender, result);
            }
            case "all" -> {
                plugin.getMessageUtil().sendRaw(sender, "&7Performing full reload...");
                result = rm.reloadAll();
                sendReloadResult(sender, result);
            }
            default -> {
                plugin.getMessageUtil().sendRaw(sender, "&cUnknown reload type: " + subType);
                plugin.getMessageUtil().sendRaw(sender, "&7Valid options: &fall, config, messages, tasks, hooks");
                return;
            }
        }
        
        plugin.getMessageUtil().sendRaw(sender, "&8&m----------------------------------------");
    }
    
    /**
     * Sends reload result feedback to the sender.
     */
    private void sendReloadResult(CommandSender sender, ReloadManager.ReloadResult result) {
        // Show changes
        if (!result.getChanges().isEmpty()) {
            plugin.getMessageUtil().sendRaw(sender, "");
            plugin.getMessageUtil().sendRaw(sender, "&a✓ Changes:");
            for (String change : result.getChanges()) {
                plugin.getMessageUtil().sendRaw(sender, "  &7• &f" + change);
            }
        }
        
        // Show errors
        if (!result.getErrors().isEmpty()) {
            plugin.getMessageUtil().sendRaw(sender, "");
            plugin.getMessageUtil().sendRaw(sender, "&c✗ Errors:");
            for (String error : result.getErrors()) {
                plugin.getMessageUtil().sendRaw(sender, "  &7• &c" + error);
            }
        }
        
        // Summary
        plugin.getMessageUtil().sendRaw(sender, "");
        if (result.isSuccess()) {
            plugin.getMessageUtil().sendRaw(sender, "&a✓ Reload completed successfully in " + result.getDuration() + "ms");
        } else {
            plugin.getMessageUtil().sendRaw(sender, "&e⚠ Reload completed with errors in " + result.getDuration() + "ms");
        }
    }
    
    /**
     * Update command for checking and downloading updates.
     * Usage: /rabadmin update [check|download|status]
     */
    private void handleUpdate(CommandSender sender, String[] args) {
        String subType = args.length >= 2 ? args[1].toLowerCase() : "check";
        
        switch (subType) {
            case "check" -> {
                plugin.getMessageUtil().sendRaw(sender, "&7Checking for updates...");
                plugin.getUpdateChecker().reset(); // Reset to force fresh check
                plugin.getUpdateChecker().checkForUpdates(sender);
            }
            case "download" -> {
                plugin.getMessageUtil().sendRaw(sender, "&7Starting download...");
                plugin.getUpdateChecker().downloadUpdate(sender).thenAccept(result -> {
                    if (!result.isSuccess()) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.getMessageUtil().sendRaw(sender, "&c" + result.getMessage());
                        });
                    }
                });
            }
            case "status" -> {
                showUpdateStatus(sender);
            }
            default -> {
                plugin.getMessageUtil().sendRaw(sender, "&cUnknown update action: " + subType);
                plugin.getMessageUtil().sendRaw(sender, "&7Valid options: &fcheck, download, status");
            }
        }
    }
    
    /**
     * Shows current update status.
     */
    private void showUpdateStatus(CommandSender sender) {
        var checker = plugin.getUpdateChecker();
        
        plugin.getMessageUtil().sendRaw(sender, "&8&m----------------------------------------");
        plugin.getMessageUtil().sendRaw(sender, "&6&lRentABot Update Status");
        plugin.getMessageUtil().sendRaw(sender, "&8&m----------------------------------------");
        plugin.getMessageUtil().sendRaw(sender, "&7Current Version: &f" + checker.getCurrentVersion());
        
        if (checker.getLatestVersion() != null) {
            plugin.getMessageUtil().sendRaw(sender, "&7Latest Version: &f" + checker.getLatestVersion());
            
            if (checker.isUpdateAvailable()) {
                plugin.getMessageUtil().sendRaw(sender, "&7Update Available: &aYes");
                
                if (checker.isDownloadCompleted()) {
                    plugin.getMessageUtil().sendRaw(sender, "&7Download Status: &aCompleted");
                    plugin.getMessageUtil().sendRaw(sender, "&7File: &f" + checker.getDownloadedFilePath());
                    plugin.getMessageUtil().sendRaw(sender, "&e&lRestart the server to apply!");
                } else if (checker.isDownloadInProgress()) {
                    plugin.getMessageUtil().sendRaw(sender, "&7Download Status: &eIn Progress...");
                } else {
                    plugin.getMessageUtil().sendRaw(sender, "&7Download Status: &7Not started");
                    plugin.getMessageUtil().sendRaw(sender, "&7Use: &f/rabadmin update download");
                }
            } else {
                plugin.getMessageUtil().sendRaw(sender, "&7Update Available: &aNo (up to date)");
            }
        } else {
            plugin.getMessageUtil().sendRaw(sender, "&7Latest Version: &7Unknown (run check first)");
            plugin.getMessageUtil().sendRaw(sender, "&7Use: &f/rabadmin update check");
        }
        
        plugin.getMessageUtil().sendRaw(sender, "&8&m----------------------------------------");
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
            completions.addAll(Arrays.asList("list", "stop", "stopall", "info", "give", "reload", "update", "debug", "help"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            switch (sub) {
                case "stop", "info" -> {
                    // Suggest all bot names
                    completions.addAll(plugin.getBotManager().getAllBots()
                        .stream()
                        .map(RentableBot::getInternalName)
                        .collect(Collectors.toList()));
                }
                case "give", "create" -> {
                    // Suggest online players
                    completions.addAll(Bukkit.getOnlinePlayers()
                        .stream()
                        .map(Player::getName)
                        .collect(Collectors.toList()));
                }
                case "reload" -> {
                    // Reload subcommands
                    completions.addAll(Arrays.asList("all", "config", "messages", "tasks", "hooks"));
                }
                case "update" -> {
                    // Update subcommands
                    completions.addAll(Arrays.asList("check", "download", "status"));
                }
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
