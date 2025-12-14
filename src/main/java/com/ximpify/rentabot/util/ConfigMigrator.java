package com.ximpify.rentabot.util;

import com.ximpify.rentabot.RentABot;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Handles configuration migration between plugin versions.
 * Adds missing keys from default config while preserving user settings.
 */
public class ConfigMigrator {
    
    private final RentABot plugin;
    private final int currentConfigVersion;
    
    // Config version history:
    // 1 = Initial release (1.0.0)
    // 2 = Added bot lifecycle (1.1.0)
    // 3 = Added update checker (1.2.0)
    private static final int LATEST_CONFIG_VERSION = 3;
    
    public ConfigMigrator(RentABot plugin) {
        this.plugin = plugin;
        this.currentConfigVersion = plugin.getConfig().getInt("config-version", 1);
    }
    
    /**
     * Check and migrate configuration if needed.
     * @return true if migration was performed
     */
    public boolean migrateIfNeeded() {
        if (currentConfigVersion >= LATEST_CONFIG_VERSION) {
            plugin.debug("Config is up to date (version " + currentConfigVersion + ")");
            return false;
        }
        
        plugin.getLogger().info("Migrating config from version " + currentConfigVersion + " to " + LATEST_CONFIG_VERSION + "...");
        
        try {
            // Create backup
            createBackup("config.yml");
            
            // Load default config from JAR
            InputStream defaultStream = plugin.getResource("config.yml");
            if (defaultStream == null) {
                plugin.getLogger().severe("Could not load default config.yml from JAR!");
                return false;
            }
            
            YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream));
            
            // Get current config
            FileConfiguration currentConfig = plugin.getConfig();
            
            // Track added keys
            List<String> addedKeys = new ArrayList<>();
            List<String> removedKeys = new ArrayList<>();
            
            // Add missing keys from default
            for (String key : defaultConfig.getKeys(true)) {
                if (!currentConfig.contains(key)) {
                    Object defaultValue = defaultConfig.get(key);
                    // Only set leaf values, not sections
                    if (!(defaultValue instanceof org.bukkit.configuration.ConfigurationSection)) {
                        currentConfig.set(key, defaultValue);
                        addedKeys.add(key);
                    }
                }
            }
            
            // Run version-specific migrations
            runMigrations(currentConfig, currentConfigVersion);
            
            // Update config version
            currentConfig.set("config-version", LATEST_CONFIG_VERSION);
            
            // Save
            plugin.saveConfig();
            
            // Log results
            if (!addedKeys.isEmpty()) {
                plugin.getLogger().info("Config updated! Added " + addedKeys.size() + " new option(s):");
                for (String key : addedKeys) {
                    plugin.getLogger().info("  + " + key);
                }
            }
            
            if (!removedKeys.isEmpty()) {
                plugin.getLogger().info("Removed " + removedKeys.size() + " obsolete option(s):");
                for (String key : removedKeys) {
                    plugin.getLogger().info("  - " + key);
                }
            }
            
            plugin.getLogger().info("Config backup saved to config.yml.backup");
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().severe("Config migration failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Migrate messages.yml if needed.
     */
    public boolean migrateMessages() {
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            return false;
        }
        
        try {
            // Load current messages
            YamlConfiguration currentMessages = YamlConfiguration.loadConfiguration(messagesFile);
            int messagesVersion = currentMessages.getInt("messages-version", 1);
            
            // Load default messages
            InputStream defaultStream = plugin.getResource("messages.yml");
            if (defaultStream == null) return false;
            
            YamlConfiguration defaultMessages = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream));
            int latestMessagesVersion = defaultMessages.getInt("messages-version", 1);
            
            if (messagesVersion >= latestMessagesVersion) {
                return false;
            }
            
            plugin.getLogger().info("Migrating messages.yml from version " + messagesVersion + " to " + latestMessagesVersion + "...");
            
            // Create backup
            createBackup("messages.yml");
            
            // Add missing keys
            List<String> addedKeys = new ArrayList<>();
            for (String key : defaultMessages.getKeys(true)) {
                if (!currentMessages.contains(key)) {
                    Object defaultValue = defaultMessages.get(key);
                    if (!(defaultValue instanceof org.bukkit.configuration.ConfigurationSection)) {
                        currentMessages.set(key, defaultValue);
                        addedKeys.add(key);
                    }
                }
            }
            
            // Update version
            currentMessages.set("messages-version", latestMessagesVersion);
            currentMessages.save(messagesFile);
            
            if (!addedKeys.isEmpty()) {
                plugin.getLogger().info("Messages updated! Added " + addedKeys.size() + " new message(s).");
            }
            
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().severe("Messages migration failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Run version-specific migrations.
     */
    private void runMigrations(FileConfiguration config, int fromVersion) {
        // Version 1 → 2: Bot lifecycle changes
        if (fromVersion < 2) {
            // Remove obsolete refund-percentage if it exists
            // (refunds are now calculated differently)
            plugin.debug("Running migration: v1 → v2 (bot lifecycle)");
        }
        
        // Version 2 → 3: Update checker
        if (fromVersion < 3) {
            plugin.debug("Running migration: v2 → v3 (update checker)");
            // New keys are auto-added, no special migration needed
        }
    }
    
    /**
     * Create a timestamped backup of a config file.
     */
    private void createBackup(String fileName) throws IOException {
        File original = new File(plugin.getDataFolder(), fileName);
        if (!original.exists()) return;
        
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File backup = new File(plugin.getDataFolder(), fileName + "." + timestamp + ".backup");
        
        // Also keep a simple .backup for easy access
        File simpleBackup = new File(plugin.getDataFolder(), fileName + ".backup");
        
        Files.copy(original.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(original.toPath(), simpleBackup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        
        plugin.debug("Created backup: " + backup.getName());
    }
    
    /**
     * Get the current config version.
     */
    public int getCurrentConfigVersion() {
        return currentConfigVersion;
    }
    
    /**
     * Get the latest config version.
     */
    public static int getLatestConfigVersion() {
        return LATEST_CONFIG_VERSION;
    }
}
