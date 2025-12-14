package com.ximpify.rentabot.hooks;

import com.ximpify.rentabot.RentABot;
import com.ximpify.rentabot.bot.RentableBot;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * PlaceholderAPI expansion for RentABot.
 * 
 * Available placeholders:
 * - %rentabot_count% - Number of bots owned by player
 * - %rentabot_max% - Maximum bots allowed per player
 * - %rentabot_total% - Total bots on server
 * - %rentabot_total_max% - Maximum total bots allowed
 * - %rentabot_active% - "true" if player has active bots
 * - %rentabot_price_hour% - Price per hour
 * - %rentabot_bots% - Comma-separated list of bot names
 */
public class PlaceholderAPIHook extends PlaceholderExpansion {
    
    private final RentABot plugin;
    
    public PlaceholderAPIHook(RentABot plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public @NotNull String getIdentifier() {
        return "rentabot";
    }
    
    @Override
    public @NotNull String getAuthor() {
        return plugin.getDescription().getAuthors().toString();
    }
    
    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }
    
    @Override
    public boolean persist() {
        return true;
    }
    
    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        // Global placeholders (no player required)
        switch (params.toLowerCase()) {
            case "total" -> {
                return String.valueOf(plugin.getBotManager().getTotalBotCount());
            }
            case "total_max" -> {
                int max = plugin.getConfig().getInt("limits.max-total-bots", 50);
                return max > 0 ? String.valueOf(max) : "∞";
            }
            case "price_hour" -> {
                if (plugin.isEconomyEnabled()) {
                    return plugin.getEconomyHandler().formatMoney(
                        plugin.getConfig().getDouble("economy.price-per-hour", 5000));
                }
                return "Free";
            }
        }
        
        // Player-specific placeholders
        if (player == null) {
            return "";
        }
        
        switch (params.toLowerCase()) {
            case "count" -> {
                return String.valueOf(plugin.getBotManager().getPlayerBotCount(player.getUniqueId()));
            }
            case "max" -> {
                if (player.isOnline() && player.getPlayer().hasPermission("rentabot.bypass.limit")) {
                    return "∞";
                }
                int max = plugin.getConfig().getInt("limits.max-bots-per-player", 3);
                return max > 0 ? String.valueOf(max) : "∞";
            }
            case "active" -> {
                return String.valueOf(plugin.getBotManager().getPlayerBotCount(player.getUniqueId()) > 0);
            }
            case "bots" -> {
                Collection<RentableBot> bots = plugin.getBotManager().getPlayerBots(player.getUniqueId());
                if (bots.isEmpty()) return "None";
                return String.join(", ", bots.stream()
                    .map(RentableBot::getInternalName)
                    .toList());
            }
            case "bots_online" -> {
                Collection<RentableBot> bots = plugin.getBotManager().getPlayerBots(player.getUniqueId());
                long online = bots.stream().filter(RentableBot::isConnected).count();
                return String.valueOf(online);
            }
        }
        
        // Dynamic placeholders: %rentabot_bot_<name>_<property>%
        if (params.startsWith("bot_")) {
            String[] parts = params.substring(4).split("_", 2);
            if (parts.length == 2) {
                String botName = parts[0];
                String property = parts[1];
                
                return plugin.getBotManager().getBot(botName)
                    .map(bot -> getBotProperty(bot, property))
                    .orElse("");
            }
        }
        
        return null;
    }
    
    private String getBotProperty(RentableBot bot, String property) {
        return switch (property.toLowerCase()) {
            case "owner" -> bot.getOwnerName();
            case "status" -> bot.isConnected() ? "Online" : "Offline";
            case "time" -> {
                Duration remaining = Duration.between(Instant.now(), bot.getExpiresAt());
                yield plugin.getRentalManager().formatTime(Math.max(0, remaining.toSeconds()));
            }
            case "created" -> bot.getCreatedAt().toString();
            case "expires" -> bot.getExpiresAt().toString();
            case "world" -> bot.getWorld() != null ? bot.getWorld() : "Unknown";
            case "x" -> String.valueOf((int) bot.getX());
            case "y" -> String.valueOf((int) bot.getY());
            case "z" -> String.valueOf((int) bot.getZ());
            default -> "";
        };
    }
}
