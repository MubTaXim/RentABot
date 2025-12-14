package com.ximpify.rentabot.rental;

import com.ximpify.rentabot.RentABot;
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
        
        // Check bot limit
        if (!player.hasPermission("rentabot.bypass.limit")) {
            int maxBots = plugin.getConfig().getInt("limits.max-bots-per-player", 3);
            int currentBots = plugin.getBotManager().getPlayerBotCount(playerUUID);
            if (maxBots > 0 && currentBots >= maxBots) {
                return new RentalResult(false, "limit-reached", 
                    String.valueOf(currentBots), String.valueOf(maxBots));
            }
        }
        
        // Check server limit
        int maxTotal = plugin.getConfig().getInt("limits.max-total-bots", 50);
        if (maxTotal > 0 && plugin.getBotManager().getTotalBotCount() >= maxTotal) {
            return new RentalResult(false, "server-limit");
        }
        
        // Check name availability
        if (!plugin.getBotManager().isNameAvailable(botName)) {
            return new RentalResult(false, "name-taken");
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
     * Stops a rental.
     */
    public RentalResult stopRental(Player player, String botName, boolean isAdmin) {
        Optional<RentableBot> optBot = plugin.getBotManager().getBot(botName);
        if (optBot.isEmpty()) {
            return new RentalResult(false, "not-found");
        }
        
        RentableBot bot = optBot.get();
        
        // Check ownership (unless admin)
        if (!isAdmin && !bot.getOwnerUUID().equals(player.getUniqueId())) {
            return new RentalResult(false, "not-owner");
        }
        
        // Calculate refund
        double refund = 0;
        if (plugin.isEconomyEnabled()) {
            int refundPercent = plugin.getConfig().getInt("economy.refund-percentage", 50);
            if (refundPercent > 0) {
                Duration remaining = Duration.between(Instant.now(), bot.getExpiresAt());
                if (!remaining.isNegative()) {
                    double hoursRemaining = remaining.toMinutes() / 60.0;
                    double pricePerHour = plugin.getConfig().getDouble("economy.price-per-hour", 5000);
                    refund = (hoursRemaining * pricePerHour * refundPercent) / 100;
                    plugin.getEconomyHandler().deposit(player, refund);
                }
            }
        }
        
        // Stop the bot
        plugin.getBotManager().stopBot(botName);
        plugin.getStorageManager().deleteRental(botName);
        
        return new RentalResult(true, "stopped", botName, 
            plugin.isEconomyEnabled() ? plugin.getEconomyHandler().formatMoney(refund) : "0");
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
        
        for (RentableBot bot : plugin.getBotManager().getAllBots()) {
            Duration remaining = Duration.between(now, bot.getExpiresAt());
            long minutesLeft = remaining.toMinutes();
            
            // Check for expiry
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
    }
    
    /**
     * Handles an expired rental.
     */
    private void handleExpiredRental(RentableBot bot) {
        String botName = bot.getInternalName();
        UUID ownerUUID = bot.getOwnerUUID();
        
        plugin.getLogger().info("Rental expired for bot: " + botName);
        
        // Notify owner
        if (plugin.getConfig().getBoolean("rentals.on-expiry.notify-owner", true)) {
            Player owner = Bukkit.getPlayer(ownerUUID);
            if (owner != null) {
                plugin.getMessageUtil().send(owner, "notifications.expired", "bot", botName);
            }
        }
        
        // Remove bot
        if (plugin.getConfig().getBoolean("rentals.on-expiry.kick-bot", true)) {
            plugin.getBotManager().stopBot(botName);
            plugin.getStorageManager().deleteRental(botName);
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
            
            // Play warning sound
            if (plugin.getConfig().getBoolean("notifications.sounds.enabled", true)) {
                String soundName = plugin.getConfig().getString("notifications.sounds.on-warning", "BLOCK_NOTE_BLOCK_PLING");
                try {
                    owner.playSound(owner.getLocation(), 
                        org.bukkit.Sound.valueOf(soundName), 1.0f, 1.0f);
                } catch (Exception ignored) {}
            }
        }
    }
    
    /**
     * Loads rentals from storage.
     */
    public void loadRentals() {
        List<RentableBot> rentals = plugin.getStorageManager().loadRentals();
        for (RentableBot bot : rentals) {
            // Skip expired rentals
            if (Instant.now().isAfter(bot.getExpiresAt())) {
                plugin.getStorageManager().deleteRental(bot.getInternalName());
                continue;
            }
            
            // Reconnect bot
            if (bot.connect()) {
                plugin.getBotManager().registerBot(bot);
                plugin.getLogger().info("Resumed rental: " + bot.getInternalName() + 
                    " (owner: " + bot.getOwnerName() + ")");
            }
        }
        plugin.getLogger().info("Loaded " + plugin.getBotManager().getTotalBotCount() + " active rentals");
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
        int maxLength = plugin.getConfig().getInt("bots.naming.max-length", 16); // Changed default to 16 (MC max)
        
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
