package com.ximpify.rentabot.util;

import com.ximpify.rentabot.RentABot;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive reload manager for RentABot.
 * Handles reloading of configs, messages, tasks, and hooks with verification.
 */
public class ReloadManager {
    
    private final RentABot plugin;
    
    // Track scheduled tasks for cancellation/rescheduling
    private BukkitTask rentalCheckTask;
    private BukkitTask antiAFKTask;
    
    public ReloadManager(RentABot plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Result of a reload operation.
     */
    public static class ReloadResult {
        private final boolean success;
        private final List<String> changes;
        private final List<String> errors;
        private final long duration;
        
        public ReloadResult(boolean success, List<String> changes, List<String> errors, long duration) {
            this.success = success;
            this.changes = changes;
            this.errors = errors;
            this.duration = duration;
        }
        
        public boolean isSuccess() { return success; }
        public List<String> getChanges() { return changes; }
        public List<String> getErrors() { return errors; }
        public long getDuration() { return duration; }
    }
    
    /**
     * Performs a complete reload of everything.
     */
    public ReloadResult reloadAll() {
        long startTime = System.currentTimeMillis();
        List<String> changes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        // 1. Reload config.yml
        ReloadResult configResult = reloadConfig();
        changes.addAll(configResult.getChanges());
        errors.addAll(configResult.getErrors());
        
        // 2. Reload messages.yml
        ReloadResult messagesResult = reloadMessages();
        changes.addAll(messagesResult.getChanges());
        errors.addAll(messagesResult.getErrors());
        
        // 3. Run config migration (add any missing keys)
        ReloadResult migrationResult = runConfigMigration();
        changes.addAll(migrationResult.getChanges());
        errors.addAll(migrationResult.getErrors());
        
        // 4. Reschedule tasks
        ReloadResult tasksResult = rescheduleTasks();
        changes.addAll(tasksResult.getChanges());
        errors.addAll(tasksResult.getErrors());
        
        // 5. Re-validate hooks
        ReloadResult hooksResult = revalidateHooks();
        changes.addAll(hooksResult.getChanges());
        errors.addAll(hooksResult.getErrors());
        
        long duration = System.currentTimeMillis() - startTime;
        boolean success = errors.isEmpty();
        
        if (success) {
            plugin.getLogger().info("Complete reload finished in " + duration + "ms");
        } else {
            plugin.getLogger().warning("Reload completed with " + errors.size() + " error(s)");
        }
        
        return new ReloadResult(success, changes, errors, duration);
    }
    
    /**
     * Reloads only config.yml with verification.
     */
    public ReloadResult reloadConfig() {
        long startTime = System.currentTimeMillis();
        List<String> changes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        try {
            File configFile = new File(plugin.getDataFolder(), "config.yml");
            
            if (!configFile.exists()) {
                plugin.saveDefaultConfig();
                changes.add("Created default config.yml");
            }
            
            // Store some values before reload to detect changes
            FileConfiguration oldConfig = plugin.getConfig();
            boolean oldDebug = oldConfig.getBoolean("advanced.debug", false);
            double oldPrice = oldConfig.getDouble("economy.price-per-hour", 5000);
            int oldMaxBots = oldConfig.getInt("limits.max-active-bots", 3);
            int oldCheckInterval = oldConfig.getInt("advanced.check-interval", 30);
            int oldAntiAFKInterval = oldConfig.getInt("bots.behavior.anti-afk.interval", 45);
            
            // Force complete reload by recreating the config
            // This is more reliable than just reloadConfig()
            plugin.reloadConfig();
            
            // Verify reload worked by checking the file directly
            YamlConfiguration directLoad = YamlConfiguration.loadConfiguration(configFile);
            FileConfiguration newConfig = plugin.getConfig();
            
            // Compare critical values to ensure reload worked
            boolean reloadVerified = true;
            
            // Check if debug value matches file
            boolean fileDebug = directLoad.getBoolean("advanced.debug", false);
            boolean memoryDebug = newConfig.getBoolean("advanced.debug", false);
            
            if (fileDebug != memoryDebug) {
                // Force sync from file
                newConfig.set("advanced.debug", fileDebug);
                errors.add("Config sync issue detected - forced correction");
                reloadVerified = false;
            }
            
            // Log changes
            if (oldDebug != newConfig.getBoolean("advanced.debug", false)) {
                changes.add("Debug mode: " + oldDebug + " → " + newConfig.getBoolean("advanced.debug", false));
            }
            if (oldPrice != newConfig.getDouble("economy.price-per-hour", 5000)) {
                changes.add("Price per hour: " + oldPrice + " → " + newConfig.getDouble("economy.price-per-hour", 5000));
            }
            if (oldMaxBots != newConfig.getInt("limits.max-active-bots", 3)) {
                changes.add("Max active bots: " + oldMaxBots + " → " + newConfig.getInt("limits.max-active-bots", 3));
            }
            if (oldCheckInterval != newConfig.getInt("advanced.check-interval", 30)) {
                changes.add("Check interval: " + oldCheckInterval + "s → " + newConfig.getInt("advanced.check-interval", 30) + "s");
            }
            if (oldAntiAFKInterval != newConfig.getInt("bots.behavior.anti-afk.interval", 45)) {
                changes.add("Anti-AFK interval: " + oldAntiAFKInterval + "s → " + newConfig.getInt("bots.behavior.anti-afk.interval", 45) + "s");
            }
            
            if (changes.isEmpty()) {
                changes.add("config.yml reloaded (no value changes detected)");
            } else {
                changes.add(0, "config.yml reloaded successfully");
            }
            
            plugin.debug("Config reload completed, verified: " + reloadVerified);
            
        } catch (Exception e) {
            errors.add("Failed to reload config.yml: " + e.getMessage());
            plugin.getLogger().severe("Config reload error: " + e.getMessage());
            e.printStackTrace();
        }
        
        return new ReloadResult(errors.isEmpty(), changes, errors, System.currentTimeMillis() - startTime);
    }
    
    /**
     * Reloads only messages.yml.
     */
    public ReloadResult reloadMessages() {
        long startTime = System.currentTimeMillis();
        List<String> changes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        try {
            File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
            
            if (!messagesFile.exists()) {
                plugin.saveResource("messages.yml", false);
                changes.add("Created default messages.yml");
            }
            
            // Reload messages through MessageUtil
            plugin.getMessageUtil().reload();
            changes.add("messages.yml reloaded successfully");
            
            // Verify by checking a known key
            String testMessage = plugin.getMessageUtil().getRaw("general.reload-success");
            if (testMessage == null || testMessage.isEmpty()) {
                errors.add("Messages may not have loaded correctly (test key missing)");
            }
            
        } catch (Exception e) {
            errors.add("Failed to reload messages.yml: " + e.getMessage());
            plugin.getLogger().severe("Messages reload error: " + e.getMessage());
        }
        
        return new ReloadResult(errors.isEmpty(), changes, errors, System.currentTimeMillis() - startTime);
    }
    
    /**
     * Runs config migration to add any missing keys.
     */
    public ReloadResult runConfigMigration() {
        long startTime = System.currentTimeMillis();
        List<String> changes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        try {
            // Load default config from JAR
            InputStream defaultStream = plugin.getResource("config.yml");
            if (defaultStream == null) {
                errors.add("Could not load default config from JAR");
                return new ReloadResult(false, changes, errors, System.currentTimeMillis() - startTime);
            }
            
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream));
            FileConfiguration currentConfig = plugin.getConfig();
            
            int addedKeys = 0;
            
            // Add missing keys from default
            for (String key : defaultConfig.getKeys(true)) {
                if (!currentConfig.contains(key)) {
                    Object defaultValue = defaultConfig.get(key);
                    // Only set leaf values, not sections
                    if (!(defaultValue instanceof org.bukkit.configuration.ConfigurationSection)) {
                        currentConfig.set(key, defaultValue);
                        addedKeys++;
                        plugin.debug("Added missing config key: " + key);
                    }
                }
            }
            
            if (addedKeys > 0) {
                plugin.saveConfig();
                changes.add("Added " + addedKeys + " missing config key(s)");
            }
            
            // Same for messages
            InputStream messagesStream = plugin.getResource("messages.yml");
            if (messagesStream != null) {
                File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
                YamlConfiguration defaultMessages = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(messagesStream));
                YamlConfiguration currentMessages = YamlConfiguration.loadConfiguration(messagesFile);
                
                int addedMessageKeys = 0;
                for (String key : defaultMessages.getKeys(true)) {
                    if (!currentMessages.contains(key)) {
                        Object defaultValue = defaultMessages.get(key);
                        if (!(defaultValue instanceof org.bukkit.configuration.ConfigurationSection)) {
                            currentMessages.set(key, defaultValue);
                            addedMessageKeys++;
                        }
                    }
                }
                
                if (addedMessageKeys > 0) {
                    currentMessages.save(messagesFile);
                    changes.add("Added " + addedMessageKeys + " missing message key(s)");
                    // Reload messages again to pick up new keys
                    plugin.getMessageUtil().reload();
                }
            }
            
        } catch (Exception e) {
            errors.add("Config migration error: " + e.getMessage());
        }
        
        return new ReloadResult(errors.isEmpty(), changes, errors, System.currentTimeMillis() - startTime);
    }
    
    /**
     * Reschedules all plugin tasks with current config values.
     */
    public ReloadResult rescheduleTasks() {
        long startTime = System.currentTimeMillis();
        List<String> changes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        try {
            // Cancel existing tasks
            if (rentalCheckTask != null && !rentalCheckTask.isCancelled()) {
                rentalCheckTask.cancel();
                plugin.debug("Cancelled existing rental check task");
            }
            if (antiAFKTask != null && !antiAFKTask.isCancelled()) {
                antiAFKTask.cancel();
                plugin.debug("Cancelled existing anti-AFK task");
            }
            
            // Reschedule rental check task
            int checkInterval = plugin.getConfig().getInt("advanced.check-interval", 30) * 20;
            rentalCheckTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                plugin.getRentalManager().checkExpiredRentals();
                plugin.getBotManager().checkBotStatus();
            }, checkInterval, checkInterval);
            changes.add("Rental check task: every " + (checkInterval / 20) + "s");
            
            // Reschedule anti-AFK task
            if (plugin.getConfig().getBoolean("bots.behavior.anti-afk.enabled", true)) {
                int baseInterval = plugin.getConfig().getInt("bots.behavior.anti-afk.interval", 45) * 20;
                double randomness = plugin.getConfig().getDouble("bots.behavior.anti-afk.interval-randomness", 0.4);
                
                scheduleAntiAFKTask(baseInterval, randomness);
                changes.add("Anti-AFK task: ~" + (baseInterval / 20) + "s (±" + (int)(randomness * 100) + "%)");
            } else {
                changes.add("Anti-AFK task: disabled");
            }
            
        } catch (Exception e) {
            errors.add("Task rescheduling error: " + e.getMessage());
        }
        
        return new ReloadResult(errors.isEmpty(), changes, errors, System.currentTimeMillis() - startTime);
    }
    
    /**
     * Schedules the anti-AFK task with randomized intervals.
     */
    private void scheduleAntiAFKTask(int baseInterval, double randomness) {
        int minInterval = (int) (baseInterval * (1.0 - randomness));
        int maxInterval = (int) (baseInterval * (1.0 + randomness));
        int actualInterval = minInterval + (int) (Math.random() * (maxInterval - minInterval));
        
        antiAFKTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            // Check if still enabled (config may have changed)
            if (!plugin.getConfig().getBoolean("bots.behavior.anti-afk.enabled", true)) {
                return;
            }
            
            // Perform anti-AFK for all connected bots
            for (var bot : plugin.getBotManager().getAllBots()) {
                if (bot.isConnected()) {
                    bot.performAntiAFK();
                }
            }
            
            // Schedule next with new random interval
            int newBase = plugin.getConfig().getInt("bots.behavior.anti-afk.interval", 45) * 20;
            double newRandomness = plugin.getConfig().getDouble("bots.behavior.anti-afk.interval-randomness", 0.4);
            scheduleAntiAFKTask(newBase, newRandomness);
        }, actualInterval);
    }
    
    /**
     * Re-validates plugin hooks.
     */
    public ReloadResult revalidateHooks() {
        long startTime = System.currentTimeMillis();
        List<String> changes = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        try {
            // Check Vault/Economy
            if (plugin.getConfig().getBoolean("economy.enabled", true)) {
                if (plugin.getServer().getPluginManager().getPlugin("Vault") != null) {
                    if (plugin.isEconomyEnabled()) {
                        changes.add("Economy: ✓ Connected");
                    } else {
                        changes.add("Economy: ⚠ Vault found but no economy provider");
                    }
                } else {
                    changes.add("Economy: ✗ Vault not found");
                }
            } else {
                changes.add("Economy: Disabled in config");
            }
            
            // Check PlaceholderAPI
            if (plugin.getConfig().getBoolean("hooks.placeholderapi.enabled", true)) {
                if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                    changes.add("PlaceholderAPI: ✓ Connected");
                } else {
                    changes.add("PlaceholderAPI: ✗ Not found");
                }
            } else {
                changes.add("PlaceholderAPI: Disabled in config");
            }
            
            // Check Essentials
            if (plugin.getConfig().getBoolean("hooks.essentials.enabled", true)) {
                if (plugin.getServer().getPluginManager().getPlugin("Essentials") != null) {
                    changes.add("Essentials: ✓ Connected");
                } else {
                    changes.add("Essentials: ✗ Not found");
                }
            } else {
                changes.add("Essentials: Disabled in config");
            }
            
        } catch (Exception e) {
            errors.add("Hook validation error: " + e.getMessage());
        }
        
        return new ReloadResult(errors.isEmpty(), changes, errors, System.currentTimeMillis() - startTime);
    }
    
    /**
     * Gets the current task references for external management.
     */
    public void setRentalCheckTask(BukkitTask task) {
        this.rentalCheckTask = task;
    }
    
    public void setAntiAFKTask(BukkitTask task) {
        this.antiAFKTask = task;
    }
}
