package com.ximpify.rentabot.bot;

import com.ximpify.rentabot.RentABot;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all bot instances on the server.
 */
public class BotManager {
    
    private final RentABot plugin;
    // All bots (active + reserved)
    private final Map<String, RentableBot> bots;
    // Track counts per player for active bots only
    private final Map<UUID, Integer> activeBotCounts;
    
    public BotManager(RentABot plugin) {
        this.plugin = plugin;
        this.bots = new ConcurrentHashMap<>();
        this.activeBotCounts = new ConcurrentHashMap<>();
    }
    
    /**
     * Creates and connects a new bot.
     * 
     * @param botName The name for the bot
     * @param ownerUUID The UUID of the bot owner
     * @param ownerName The name of the bot owner
     * @param hours Duration in hours
     * @return The created bot, or null if failed
     */
    public RentableBot createBot(String botName, UUID ownerUUID, String ownerName, int hours) {
        // Check if name is taken
        if (bots.containsKey(botName.toLowerCase())) {
            return null;
        }
        
        // Get prefix/suffix for display purposes only
        String prefix = plugin.getConfig().getString("bots.naming.prefix", "Bot_");
        String suffix = plugin.getConfig().getString("bots.naming.suffix", "");
        
        // Connection name must be valid Minecraft username: [a-zA-Z0-9_] only, max 16 chars
        // Remove any illegal characters and ensure valid format
        String connectionName = sanitizeUsername(prefix + botName + suffix);
        
        // Create bot instance - connectionName is used for Minecraft, botName is internal reference
        RentableBot bot = new RentableBot(plugin, connectionName, botName, ownerUUID, ownerName, hours);
        
        // Connect bot
        if (bot.connect()) {
            bots.put(botName.toLowerCase(), bot);
            activeBotCounts.merge(ownerUUID, 1, (a, b) -> a + b);
            plugin.getLogger().info("Bot '" + botName + "' created for player " + ownerName);
            return bot;
        }
        
        return null;
    }
    
    /**
     * Stops a bot (pauses it, doesn't delete).
     * Bot stays in memory with STOPPED status.
     */
    public boolean stopBot(String botName) {
        RentableBot bot = bots.get(botName.toLowerCase());
        if (bot != null && bot.getStatus() == BotStatus.ACTIVE) {
            bot.stopAndFreeze();
            UUID owner = bot.getOwnerUUID();
            activeBotCounts.computeIfPresent(owner, (k, v) -> v > 1 ? v - 1 : null);
            plugin.getLogger().info("Bot '" + botName + "' stopped (paused)");
            return true;
        }
        return false;
    }
    
    /**
     * Permanently deletes a bot from memory and database.
     */
    public boolean deleteBot(String botName) {
        RentableBot bot = bots.remove(botName.toLowerCase());
        if (bot != null) {
            // Disconnect if connected
            if (bot.isConnected()) {
                bot.disconnect("Bot deleted");
            }
            // Update counts if was active
            if (bot.getStatus() == BotStatus.ACTIVE) {
                UUID owner = bot.getOwnerUUID();
                activeBotCounts.computeIfPresent(owner, (k, v) -> v > 1 ? v - 1 : null);
            }
            plugin.getLogger().info("Bot '" + botName + "' permanently deleted");
            return true;
        }
        return false;
    }
    
    /**
     * Resumes a stopped bot.
     */
    public boolean resumeBot(String botName) {
        RentableBot bot = bots.get(botName.toLowerCase());
        if (bot != null && bot.getStatus() == BotStatus.STOPPED && bot.hasTimeRemaining()) {
            if (bot.resume()) {
                activeBotCounts.merge(bot.getOwnerUUID(), 1, (a, b) -> a + b);
                plugin.getLogger().info("Bot '" + botName + "' resumed");
                return true;
            }
        }
        return false;
    }
    
    /**
     * Resumes an expired bot with new hours.
     */
    public boolean resumeBotWithHours(String botName, int hours) {
        RentableBot bot = bots.get(botName.toLowerCase());
        if (bot != null && (bot.getStatus() == BotStatus.EXPIRED || 
                          (bot.getStatus() == BotStatus.STOPPED && !bot.hasTimeRemaining()))) {
            if (bot.resumeWithHours(hours)) {
                activeBotCounts.merge(bot.getOwnerUUID(), 1, (a, b) -> a + b);
                plugin.getLogger().info("Bot '" + botName + "' resumed with " + hours + " hours");
                return true;
            }
        }
        return false;
    }
    
    /**
     * Marks a bot as expired.
     */
    public void expireBot(String botName) {
        RentableBot bot = bots.get(botName.toLowerCase());
        if (bot != null && bot.getStatus() == BotStatus.ACTIVE) {
            bot.markExpired();
            UUID owner = bot.getOwnerUUID();
            activeBotCounts.computeIfPresent(owner, (k, v) -> v > 1 ? v - 1 : null);
            plugin.getLogger().info("Bot '" + botName + "' expired");
        }
    }
    
    /**
     * Gets a bot by name.
     */
    public Optional<RentableBot> getBot(String botName) {
        return Optional.ofNullable(bots.get(botName.toLowerCase()));
    }
    
    /**
     * Gets all bots owned by a player (all states).
     */
    public Collection<RentableBot> getPlayerBots(UUID playerUUID) {
        return bots.values().stream()
            .filter(bot -> bot.getOwnerUUID().equals(playerUUID))
            .toList();
    }
    
    /**
     * Gets all active bots owned by a player.
     */
    public Collection<RentableBot> getPlayerActiveBots(UUID playerUUID) {
        return bots.values().stream()
            .filter(bot -> bot.getOwnerUUID().equals(playerUUID))
            .filter(bot -> bot.getStatus() == BotStatus.ACTIVE)
            .toList();
    }
    
    /**
     * Gets all reserved (stopped/expired) bots owned by a player.
     */
    public Collection<RentableBot> getPlayerReservedBots(UUID playerUUID) {
        return bots.values().stream()
            .filter(bot -> bot.getOwnerUUID().equals(playerUUID))
            .filter(bot -> bot.getStatus().isReserved())
            .toList();
    }
    
    /**
     * Gets all bots in memory.
     */
    public Collection<RentableBot> getAllBots() {
        return bots.values();
    }
    
    /**
     * Gets all active (connected) bots.
     */
    public Collection<RentableBot> getAllActiveBots() {
        return bots.values().stream()
            .filter(bot -> bot.getStatus() == BotStatus.ACTIVE)
            .toList();
    }
    
    /**
     * Sanitizes a username to only contain valid Minecraft characters.
     * Valid characters: [a-zA-Z0-9_], max 16 characters.
     * Capitalizes first letter of each word for consistency.
     */
    private String sanitizeUsername(String name) {
        // Remove all non-alphanumeric characters except underscore
        String sanitized = name.replaceAll("[^a-zA-Z0-9_]", "");
        
        // Ensure it's not empty
        if (sanitized.isEmpty()) {
            sanitized = "Bot_" + System.currentTimeMillis() % 10000;
        }
        
        // Capitalize first letter after each underscore for consistency (AuthMe is case-sensitive)
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : sanitized.toCharArray()) {
            if (c == '_') {
                result.append(c);
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }
        sanitized = result.toString();
        
        // Minecraft usernames max 16 characters
        if (sanitized.length() > 16) {
            sanitized = sanitized.substring(0, 16);
        }
        
        // Ensure minimum 3 characters
        if (sanitized.length() < 3) {
            sanitized = sanitized + "_Bot";
        }
        
        return sanitized;
    }
    
    /**
     * Gets the number of ACTIVE bots a player owns.
     */
    public int getPlayerBotCount(UUID playerUUID) {
        return activeBotCounts.getOrDefault(playerUUID, 0);
    }
    
    /**
     * Gets the number of ACTIVE bots a player owns (alias).
     */
    public int getPlayerActiveBotCount(UUID playerUUID) {
        return activeBotCounts.getOrDefault(playerUUID, 0);
    }
    
    /**
     * Gets the number of reserved (stopped/expired) bots a player owns.
     */
    public int getPlayerReservedBotCount(UUID playerUUID) {
        return (int) bots.values().stream()
            .filter(bot -> bot.getOwnerUUID().equals(playerUUID))
            .filter(bot -> bot.getStatus().isReserved())
            .count();
    }
    
    /**
     * Gets total bot count (all players) for a specific status.
     */
    public int getTotalBotCountByStatus(BotStatus status) {
        return (int) bots.values().stream()
            .filter(bot -> bot.getStatus() == status)
            .count();
    }
    
    /**
     * Gets total active bot count.
     */
    public int getTotalBotCount() {
        return getTotalBotCountByStatus(BotStatus.ACTIVE);
    }
    
    /**
     * Gets total bot count (all states).
     */
    public int getTotalAllBotsCount() {
        return bots.size();
    }
    
    /**
     * Checks if a bot name is available.
     * A name is NOT available if any bot (active or reserved) has it.
     */
    public boolean isNameAvailable(String botName) {
        return !bots.containsKey(botName.toLowerCase());
    }
    
    /**
     * Teleports a bot to a location.
     */
    public boolean teleportBot(String botName, Location location) {
        RentableBot bot = bots.get(botName.toLowerCase());
        if (bot != null && bot.isConnected()) {
            // Bots teleport via server command since they're real connections
            Bukkit.getScheduler().runTask(plugin, () -> {
                String command = String.format("tp %s %s %d %d %d",
                    bot.getDisplayName(),
                    location.getWorld().getName(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
                );
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            });
            return true;
        }
        return false;
    }
    
    /**
     * Renames a bot - disconnects and reconnects with new name.
     */
    public boolean renameBot(String oldName, String newName) {
        RentableBot bot = bots.remove(oldName.toLowerCase());
        if (bot != null) {
            // Disconnect the bot first (mark as manual so no reconnect message)
            bot.disconnect("Renaming bot");
            
            // Update both internal name and display name
            bot.setInternalName(newName);
            bot.setDisplayName(sanitizeUsername(plugin.getConfig().getString("bots.naming.prefix", "Bot_") 
                + newName + plugin.getConfig().getString("bots.naming.suffix", "")));
            
            // Update database: delete old entry and save with new name
            plugin.getStorageManager().deleteRental(oldName);
            plugin.getStorageManager().saveRental(bot);
            
            // Add back to bots map with new name
            bots.put(newName.toLowerCase(), bot);
            
            // Reconnect with new name after longer delay to ensure old connection is fully closed
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                bot.resetForReconnect(); // Reset manuallyStopped flag
                if (bot.connect()) {
                    plugin.getLogger().info("Bot reconnecting with new name: " + bot.getDisplayName());
                }
            }, 60L); // 3 second delay to ensure clean disconnect
            
            return true;
        }
        return false;
    }
    
    /**
     * Checks status of all bots and handles disconnections.
     */
    public void checkBotStatus() {
        for (RentableBot bot : bots.values()) {
            // Only try to reconnect active bots
            if (bot.getStatus() == BotStatus.ACTIVE && !bot.isConnected() && bot.shouldReconnect()) {
                plugin.debug("Attempting to reconnect bot: " + bot.getInternalName());
                bot.reconnect();
            }
        }
    }
    
    /**
     * Disconnects all active bots (used on plugin disable).
     * Stopped/expired bots remain in memory for saving.
     */
    public void disconnectAll() {
        for (RentableBot bot : bots.values()) {
            if (bot.isConnected()) {
                bot.disconnect("Server shutdown");
            }
        }
        // Don't clear bots map - let RentalManager save them first
        activeBotCounts.clear();
    }
    
    /**
     * Clears all bots from memory (call after saving).
     */
    public void clearAll() {
        bots.clear();
        activeBotCounts.clear();
    }
    
    /**
     * Registers an existing bot (used when loading from storage).
     * Only increments active count if bot is ACTIVE.
     */
    public void registerBot(RentableBot bot) {
        bots.put(bot.getInternalName().toLowerCase(), bot);
        if (bot.getStatus() == BotStatus.ACTIVE) {
            activeBotCounts.merge(bot.getOwnerUUID(), 1, (a, b) -> a + b);
        }
    }
    
    /**
     * Registers a bot without connecting (for stopped/expired bots from storage).
     */
    public void registerBotWithoutConnect(RentableBot bot) {
        bots.put(bot.getInternalName().toLowerCase(), bot);
        // Don't increment active counts for non-active bots
    }
}