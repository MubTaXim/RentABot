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
    private final Map<String, RentableBot> bots;
    private final Map<UUID, Integer> playerBotCounts;
    
    public BotManager(RentABot plugin) {
        this.plugin = plugin;
        this.bots = new ConcurrentHashMap<>();
        this.playerBotCounts = new ConcurrentHashMap<>();
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
            playerBotCounts.merge(ownerUUID, 1, Integer::sum);
            plugin.getLogger().info("Bot '" + botName + "' created for player " + ownerName);
            return bot;
        }
        
        return null;
    }
    
    /**
     * Stops and removes a bot.
     */
    public boolean stopBot(String botName) {
        RentableBot bot = bots.remove(botName.toLowerCase());
        if (bot != null) {
            bot.disconnect("Rental stopped");
            UUID owner = bot.getOwnerUUID();
            playerBotCounts.computeIfPresent(owner, (k, v) -> v > 1 ? v - 1 : null);
            plugin.getLogger().info("Bot '" + botName + "' stopped");
            return true;
        }
        return false;
    }
    
    /**
     * Gets a bot by name.
     */
    public Optional<RentableBot> getBot(String botName) {
        return Optional.ofNullable(bots.get(botName.toLowerCase()));
    }
    
    /**
     * Gets all bots owned by a player.
     */
    public Collection<RentableBot> getPlayerBots(UUID playerUUID) {
        return bots.values().stream()
            .filter(bot -> bot.getOwnerUUID().equals(playerUUID))
            .toList();
    }
    
    /**
     * Gets all active bots.
     */
    public Collection<RentableBot> getAllBots() {
        return bots.values();
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
     * Gets the number of bots a player owns.
     */
    public int getPlayerBotCount(UUID playerUUID) {
        return playerBotCounts.getOrDefault(playerUUID, 0);
    }
    
    /**
     * Gets total bot count.
     */
    public int getTotalBotCount() {
        return bots.size();
    }
    
    /**
     * Checks if a bot name is available.
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
            if (!bot.isConnected() && bot.shouldReconnect()) {
                plugin.debug("Attempting to reconnect bot: " + bot.getInternalName());
                bot.reconnect();
            }
        }
    }
    
    /**
     * Disconnects all bots (used on plugin disable).
     */
    public void disconnectAll() {
        for (RentableBot bot : bots.values()) {
            bot.disconnect("Server shutdown");
        }
        bots.clear();
        playerBotCounts.clear();
    }
    
    /**
     * Registers an existing bot (used when loading from storage).
     */
    public void registerBot(RentableBot bot) {
        bots.put(bot.getInternalName().toLowerCase(), bot);
        playerBotCounts.merge(bot.getOwnerUUID(), 1, Integer::sum);
    }
}
