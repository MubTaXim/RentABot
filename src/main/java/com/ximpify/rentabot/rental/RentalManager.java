package com.ximpify.rentabot.rental;

import com.ximpify.rentabot.RentABot;
import com.ximpify.rentabot.bot.BotStatus;
import com.ximpify.rentabot.bot.RentableBot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Manages bot rentals, expiry, and persistence.
 */
public class RentalManager {
    
    private final RentABot plugin;
    private final Map<UUID, Long> lastCreationTime;
    private final Set<String> warningsSent;
    
    public RentalManager(RentABot plugin) {
        this.plugin = plugin;
        this.lastCreationTime = new HashMap<>();
        this.warningsSent = new HashSet<>();
    }
    
    /**
     * Creates a new rental.
     */
    public RentalResult createRental(Player player, String botName, int hours) {
        UUID playerUUID = player.getUniqueId();
        
        // Check cooldown
        if (!player.hasPermission("rentabot.bypass.cooldown")) {
            long cooldown = plugin.getConfig().getLong("limits.creation-cooldown", 60) * 1000;
            Long lastTime = lastCreationTime.get(playerUUID);
            if (lastTime != null && System.currentTimeMillis() - lastTime < cooldown) {
                long remaining = (cooldown - (System.currentTimeMillis() - lastTime)) / 1000;
                return new RentalResult(false, "cooldown", formatTime(remaining));
            }
        }
        
        // Check hours validity
        int minHours = plugin.getConfig().getInt("economy.min-hours", 1);
        int maxHours = plugin.getConfig().getInt("economy.max-hours", 168);
        if (hours < minHours || hours > maxHours) {
            return new RentalResult(false, "invalid-hours", String.valueOf(minHours), String.valueOf(maxHours));
        }
        
        // Check ACTIVE bot limit
        if (!player.hasPermission("rentabot.bypass.limit")) {
            int maxActiveBots = plugin.getConfig().getInt("limits.max-active-bots", 3);
            int currentActiveBots = plugin.getBotManager().getPlayerActiveBotCount(playerUUID);
            if (maxActiveBots > 0 && currentActiveBots >= maxActiveBots) {
                return new RentalResult(false, "limit-reached", 
                    String.valueOf(currentActiveBots), String.valueOf(maxActiveBots));
            }
        }
        
        // Check server-wide active limit
        int maxTotal = plugin.getConfig().getInt("limits.max-total-bots", 50);
        if (maxTotal > 0 && plugin.getBotManager().getTotalBotCount() >= maxTotal) {
            return new RentalResult(false, "server-limit");
        }
        
        // Check name availability (includes reserved bots)
        if (!plugin.getBotManager().isNameAvailable(botName)) {
            return new RentalResult(false, "name-taken");
        }
        
        // Check reserved bot limit (for total bots owned)
        if (!player.hasPermission("rentabot.bypass.limit")) {
            int maxReserved = plugin.getConfig().getInt("limits.max-reserved-bots", 5);
            int currentReserved = plugin.getBotManager().getPlayerReservedBotCount(playerUUID);
            int currentActive = plugin.getBotManager().getPlayerActiveBotCount(playerUUID);
            int totalOwned = currentReserved + currentActive;
            int maxTotalOwned = plugin.getConfig().getInt("limits.max-active-bots", 3) + maxReserved;
            if (maxReserved > 0 && totalOwned >= maxTotalOwned) {
                return new RentalResult(false, "total-limit-reached", 
                    String.valueOf(totalOwned), String.valueOf(maxTotalOwned));
            }
        }
        
        // Validate name
        String nameError = validateBotName(botName);
        if (nameError != null) {
            return new RentalResult(false, "invalid-name", nameError);
        }
        
        // Check economy
        if (plugin.isEconomyEnabled()) {
            double price = calculatePrice(hours);
            if (!plugin.getEconomyHandler().hasBalance(player, price)) {
                String balance = plugin.getEconomyHandler().formatMoney(
                    plugin.getEconomyHandler().getBalance(player));
                String priceStr = plugin.getEconomyHandler().formatMoney(price);
                return new RentalResult(false, "not-enough-money", priceStr, balance);
            }
            
            // Charge player
            plugin.getEconomyHandler().withdraw(player, price);
        }
        
        // Create the bot
        RentableBot bot = plugin.getBotManager().createBot(botName, playerUUID, player.getName(), hours);
        if (bot == null) {
            // Refund if bot creation failed
            if (plugin.isEconomyEnabled()) {
                plugin.getEconomyHandler().deposit(player, calculatePrice(hours));
            }
            return new RentalResult(false, "create-failed");
        }
        
        // Save to storage
        plugin.getStorageManager().saveRental(bot);
        
        // Update cooldown
        lastCreationTime.put(playerUUID, System.currentTimeMillis());
        
        // Return success
        String priceStr = plugin.isEconomyEnabled() 
            ? plugin.getEconomyHandler().formatMoney(calculatePrice(hours))
            : "Free";
        
        return new RentalResult(true, "success", botName, String.valueOf(hours), priceStr);
    }
    
    /**
     * Stops (pauses) a rental. Time is frozen and bot can be resumed later.
     */
    public RentalResult stopRental(Player player, String botName, boolean isAdmin) {
        Optional<RentableBot> optBot = plugin.getBotManager().getBot(botName);
        if (optBot.isEmpty()) {
            return new RentalResult(false, "not-found");
        }
        
        RentableBot bot = optBot.get();
        
        // Check if already stopped
        if (bot.getStatus() != BotStatus.ACTIVE) {
            return new RentalResult(false, "already-stopped");
        }
        
        // Check ownership (unless admin)
        if (!isAdmin && !bot.getOwnerUUID().equals(player.getUniqueId())) {
            return new RentalResult(false, "not-owner");
        }
        
        // Stop the bot (time is frozen, no refund needed)
        plugin.getBotManager().stopBot(botName);
        
        // Save to storage (with STOPPED status and remaining time)
        plugin.getStorageManager().saveRental(bot);
        
        String timeRemaining = formatTime(bot.getRemainingSeconds());
        return new RentalResult(true, "stopped", botName, timeRemaining);
    }
    
    /**
     * Permanently deletes a rental.
     */
    public RentalResult deleteRental(Player player, String botName, boolean isAdmin) {
        Optional<RentableBot> optBot = plugin.getBotManager().getBot(botName);
        if (optBot.isEmpty()) {
            return new RentalResult(false, "not-found");
        }
        
        RentableBot bot = optBot.get();
        
        // Check ownership (unless admin)
        if (!isAdmin && !bot.getOwnerUUID().equals(player.getUniqueId())) {
            return new RentalResult(false, "not-owner");
        }
        
        // Delete the bot permanently
        plugin.getBotManager().deleteBot(botName);
        plugin.getStorageManager().deleteRental(botName);
        
        return new RentalResult(true, "deleted", botName);
    }
    
    /**
     * Resumes a stopped bot.
     */
    public RentalResult resumeRental(Player player, String botName, int additionalHours) {
        Optional<RentableBot> optBot = plugin.getBotManager().getBot(botName);
        if (optBot.isEmpty()) {
            return new RentalResult(false, "not-found");
        }
        
        RentableBot bot = optBot.get();
        
        // Check ownership
        if (!bot.getOwnerUUID().equals(player.getUniqueId())) {
            return new RentalResult(false, "not-owner");
        }
        
        // Check if can be resumed
        if (bot.getStatus() == BotStatus.ACTIVE) {
            return new RentalResult(false, "already-active");
        }
        
        // Check active bot limit before resuming
        if (!player.hasPermission("rentabot.bypass.limit")) {
            int maxActiveBots = plugin.getConfig().getInt("limits.max-active-bots", 3);
            int currentActiveBots = plugin.getBotManager().getPlayerActiveBotCount(player.getUniqueId());
            if (maxActiveBots > 0 && currentActiveBots >= maxActiveBots) {
                return new RentalResult(false, "max-active-reached", 
                    String.valueOf(currentActiveBots), String.valueOf(maxActiveBots));
            }
        }
        
        // Check server-wide active limit
        int maxTotal = plugin.getConfig().getInt("limits.max-total-bots", 50);
        if (maxTotal > 0 && plugin.getBotManager().getTotalBotCount() >= maxTotal) {
            return new RentalResult(false, "server-limit");
        }
        
        // Handle based on whether bot has time remaining
        if (bot.getStatus() == BotStatus.STOPPED && bot.hasTimeRemaining()) {
            // Resume with existing time (free)
            if (plugin.getBotManager().resumeBot(botName)) {
                plugin.getStorageManager().saveRental(bot);
                String timeRemaining = formatTime(bot.getRemainingSeconds());
                return new RentalResult(true, "resumed", botName, timeRemaining);
            }
            return new RentalResult(false, "resume-failed");
        } else {
            // Expired or no time - need to pay for new hours
            if (additionalHours <= 0) {
                return new RentalResult(false, "no-time", botName);
            }
            
            // Check hours validity
            int minHours = plugin.getConfig().getInt("economy.min-hours", 1);
            int maxHours = plugin.getConfig().getInt("economy.max-hours", 168);
            if (additionalHours < minHours || additionalHours > maxHours) {
                return new RentalResult(false, "invalid-hours", String.valueOf(minHours), String.valueOf(maxHours));
            }
            
            // Check economy
            if (plugin.isEconomyEnabled()) {
                double price = calculatePrice(additionalHours);
                if (!plugin.getEconomyHandler().hasBalance(player, price)) {
                    String balance = plugin.getEconomyHandler().formatMoney(
                        plugin.getEconomyHandler().getBalance(player));
                    String priceStr = plugin.getEconomyHandler().formatMoney(price);
                    return new RentalResult(false, "not-enough-money", priceStr, balance);
                }
                plugin.getEconomyHandler().withdraw(player, price);
            }
            
            // Resume with new hours
            if (plugin.getBotManager().resumeBotWithHours(botName, additionalHours)) {
                plugin.getStorageManager().saveRental(bot);
                String priceStr = plugin.isEconomyEnabled() 
                    ? plugin.getEconomyHandler().formatMoney(calculatePrice(additionalHours))
                    : "Free";
                return new RentalResult(true, "resumed-paid", botName, 
                    String.valueOf(additionalHours), priceStr);
            }
            
            // Refund on failure
            if (plugin.isEconomyEnabled()) {
                plugin.getEconomyHandler().deposit(player, calculatePrice(additionalHours));
            }
            return new RentalResult(false, "resume-failed");
        }
    }
    
    /**
     * Extends a rental.
     */
    public RentalResult extendRental(Player player, String botName, int hours) {
        Optional<RentableBot> optBot = plugin.getBotManager().getBot(botName);
        if (optBot.isEmpty()) {
            return new RentalResult(false, "not-found");
        }
        
        RentableBot bot = optBot.get();
        
        // Check ownership
        if (!bot.getOwnerUUID().equals(player.getUniqueId())) {
            return new RentalResult(false, "not-owner");
        }
        
        // Check max duration
        int maxHours = plugin.getConfig().getInt("economy.max-hours", 168);
        Duration currentRemaining = Duration.between(Instant.now(), bot.getExpiresAt());
        long totalHours = currentRemaining.toHours() + hours;
        if (totalHours > maxHours) {
            return new RentalResult(false, "max-reached");
        }
        
        // Check economy
        if (plugin.isEconomyEnabled()) {
            double price = calculatePrice(hours);
            if (!plugin.getEconomyHandler().hasBalance(player, price)) {
                return new RentalResult(false, "not-enough-money");
            }
            plugin.getEconomyHandler().withdraw(player, price);
        }
        
        // Extend rental
        bot.extendRental(hours);
        plugin.getStorageManager().saveRental(bot);
        
        return new RentalResult(true, "extended", String.valueOf(hours), 
            formatTime(Duration.between(Instant.now(), bot.getExpiresAt()).toSeconds()));
    }
    
    /**
     * Checks for expired rentals and sends warnings.
     */
    public void checkExpiredRentals() {
        Instant now = Instant.now();
        List<Integer> warningTimes = plugin.getConfig().getIntegerList("rentals.expiry-warnings.times");
        boolean warningsEnabled = plugin.getConfig().getBoolean("rentals.expiry-warnings.enabled", true);
        int gracePeriod = plugin.getConfig().getInt("rentals.on-expiry.grace-period", 60);
        
        // Only check ACTIVE bots for expiry
        for (RentableBot bot : plugin.getBotManager().getAllActiveBots()) {
            Duration remaining = Duration.between(now, bot.getExpiresAt());
            long minutesLeft = remaining.toMinutes();
            
            // Check for expiry (only active bots)
            if (remaining.isNegative() && remaining.abs().toSeconds() > gracePeriod) {
                handleExpiredRental(bot);
                continue;
            }
            
            // Send warnings
            if (warningsEnabled) {
                for (int warningTime : warningTimes) {
                    String warningKey = bot.getInternalName() + "-" + warningTime;
                    if (minutesLeft <= warningTime && minutesLeft > warningTime - 1 
                            && !warningsSent.contains(warningKey)) {
                        sendExpiryWarning(bot, minutesLeft);
                        warningsSent.add(warningKey);
                    }
                }
            }
        }
        
        // Check for auto-cleanup of old expired bots
        checkAutoCleanup();
    }
    
    /**
     * Checks and performs auto-cleanup of old expired bots.
     */
    private void checkAutoCleanup() {
        if (!plugin.getConfig().getBoolean("cleanup.enabled", true)) {
            return;
        }
        
        int cleanupDays = plugin.getConfig().getInt("cleanup.delete-expired-after-days", 30);
        if (cleanupDays <= 0) return;
        
        Instant cutoffTime = Instant.now().minusSeconds(cleanupDays * 86400L);
        
        for (RentableBot bot : plugin.getBotManager().getAllBots()) {
            // Only cleanup EXPIRED or STOPPED bots
            if (!bot.getStatus().isReserved()) continue;
            
            // Check if last active is before cutoff
            if (bot.getLastActiveAt() != null && bot.getLastActiveAt().isBefore(cutoffTime)) {
                String botName = bot.getInternalName();
                UUID ownerUUID = bot.getOwnerUUID();
                
                // Notify owner if online
                if (plugin.getConfig().getBoolean("cleanup.notify-before-cleanup", true)) {
                    Player owner = Bukkit.getPlayer(ownerUUID);
                    if (owner != null) {
                        plugin.getMessageUtil().send(owner, "notifications.cleanup", "bot", botName);
                        plugin.getMessageUtil().playSound(owner, "on-cleanup");
                    }
                }
                
                // Delete the bot
                plugin.getBotManager().deleteBot(botName);
                plugin.getStorageManager().deleteRental(botName);
                plugin.getLogger().info("Auto-cleanup: Deleted expired bot '" + botName + "'");
            }
        }
    }
    
    /**
     * Handles an expired rental - marks as EXPIRED instead of deleting.
     */
    private void handleExpiredRental(RentableBot bot) {
        String botName = bot.getInternalName();
        UUID ownerUUID = bot.getOwnerUUID();
        
        plugin.getLogger().info("Rental expired for bot: " + botName);
        
        // Mark bot as expired (does not delete)
        plugin.getBotManager().expireBot(botName);
        
        // Save the updated status
        plugin.getStorageManager().saveRental(bot);
        
        // Notify owner
        if (plugin.getConfig().getBoolean("rentals.on-expiry.notify-owner", true)) {
            Player owner = Bukkit.getPlayer(ownerUUID);
            if (owner != null) {
                plugin.getMessageUtil().send(owner, "notifications.expired", "bot", botName);
                plugin.getMessageUtil().playSound(owner, "on-expired");
            }
        }
        
        // Clear warnings for this bot
        warningsSent.removeIf(key -> key.startsWith(botName + "-"));
    }
    
    /**
     * Sends an expiry warning to the bot owner.
     */
    private void sendExpiryWarning(RentableBot bot, long minutesLeft) {
        Player owner = Bukkit.getPlayer(bot.getOwnerUUID());
        if (owner != null) {
            String timeStr = minutesLeft >= 60 
                ? (minutesLeft / 60) + " hour(s)" 
                : minutesLeft + " minute(s)";
            plugin.getMessageUtil().send(owner, "notifications.expiry-warning", 
                "bot", bot.getInternalName(), "time", timeStr);
            
            // Play warning sound using centralized method
            plugin.getMessageUtil().playSound(owner, "on-warning");
        }
    }
    
    /**
     * Loads rentals from storage.
     */
    public void loadRentals() {
        List<RentableBot> rentals = plugin.getStorageManager().loadRentals();
        int activeCount = 0;
        int stoppedCount = 0;
        int expiredCount = 0;
        
        for (RentableBot bot : rentals) {
            switch (bot.getStatus()) {
                case ACTIVE -> {
                    // Check if should have expired while offline
                    if (Instant.now().isAfter(bot.getExpiresAt())) {
                        // Mark as expired instead of deleting
                        bot.setStatus(BotStatus.EXPIRED);
                        bot.setRemainingSeconds(0);
                        plugin.getBotManager().registerBotWithoutConnect(bot);
                        plugin.getStorageManager().saveRental(bot);
                        expiredCount++;
                        plugin.getLogger().info("Bot '" + bot.getInternalName() + "' expired while offline");
                    } else {
                        // Reconnect active bot
                        if (bot.connect()) {
                            plugin.getBotManager().registerBot(bot);
                            activeCount++;
                            plugin.getLogger().info("Resumed rental: " + bot.getInternalName() + 
                                " (owner: " + bot.getOwnerName() + ")");
                        } else {
                            // Failed to connect - still register but as failed
                            plugin.getBotManager().registerBot(bot);
                            plugin.getLogger().warning("Failed to reconnect bot: " + bot.getInternalName());
                        }
                    }
                }
                case STOPPED -> {
                    // Register stopped bot without connecting
                    plugin.getBotManager().registerBotWithoutConnect(bot);
                    stoppedCount++;
                    plugin.debug("Loaded stopped bot: " + bot.getInternalName() + 
                        " (" + formatTime(bot.getRemainingSeconds()) + " remaining)");
                }
                case EXPIRED -> {
                    // Register expired bot without connecting
                    plugin.getBotManager().registerBotWithoutConnect(bot);
                    expiredCount++;
                    plugin.debug("Loaded expired bot: " + bot.getInternalName());
                }
            }
        }
        
        plugin.getLogger().info("Loaded rentals - Active: " + activeCount + 
            ", Stopped: " + stoppedCount + ", Expired: " + expiredCount);
    }
    
    /**
     * Saves all rentals to storage.
     */
    public void saveRentals() {
        for (RentableBot bot : plugin.getBotManager().getAllBots()) {
            plugin.getStorageManager().saveRental(bot);
        }
    }
    
    /**
     * Calculates the price for a rental.
     */
    public double calculatePrice(int hours) {
        double pricePerHour = plugin.getConfig().getDouble("economy.price-per-hour", 5000);
        return pricePerHour * hours;
    }
    
    /**
     * Validates a bot name.
     */
    private String validateBotName(String name) {
        // Note: min-length and max-length only apply to custom names, not auto-generated
        // Auto-generated names like "PlayerName_Bot1" can be longer
        int minLength = plugin.getConfig().getInt("bots.naming.min-length", 3);
        
        if (name.length() < minLength) {
            return "Name too short (min: " + minLength + ")";
        }
        // Max 16 for Minecraft username limit, but allow longer internal names
        if (name.length() > 32) {
            return "Name too long (max: 32)";
        }
        if (!name.matches("^[a-zA-Z0-9_]+$")) {
            return "Name can only contain letters, numbers, and underscores";
        }
        
        // Check blocked words
        List<String> blockedWords = plugin.getConfig().getStringList("bots.naming.blocked-words");
        for (String blocked : blockedWords) {
            if (name.toLowerCase().contains(blocked.toLowerCase())) {
                return "Name contains blocked word";
            }
        }
        
        return null;
    }
    
    /**
     * Formats time in a human-readable format.
     */
    public String formatTime(long seconds) {
        if (seconds < 60) {
            return seconds + " second(s)";
        } else if (seconds < 3600) {
            return (seconds / 60) + " minute(s)";
        } else if (seconds < 86400) {
            long hours = seconds / 3600;
            long mins = (seconds % 3600) / 60;
            return hours + " hour(s)" + (mins > 0 ? " " + mins + " min" : "");
        } else {
            long days = seconds / 86400;
            long hours = (seconds % 86400) / 3600;
            return days + " day(s)" + (hours > 0 ? " " + hours + " hour(s)" : "");
        }
    }
    
    /**
     * Result of a rental operation.
     */
    public record RentalResult(boolean success, String messageKey, String... args) {}
}
