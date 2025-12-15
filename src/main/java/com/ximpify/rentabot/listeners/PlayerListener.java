package com.ximpify.rentabot.listeners;

import com.ximpify.rentabot.RentABot;
import com.ximpify.rentabot.bot.RentableBot;
import com.ximpify.rentabot.gui.GUIListener;
import com.ximpify.rentabot.rental.RentalManager.RentalResult;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Optional;

/**
 * Handles player-related events for RentABot.
 * Uses AsyncPlayerChatEvent for Spigot compatibility.
 */
@SuppressWarnings("deprecation") // AsyncPlayerChatEvent is deprecated but needed for Spigot support
public class PlayerListener implements Listener {
    
    private final RentABot plugin;
    private final GUIListener guiListener;
    
    public PlayerListener(RentABot plugin, GUIListener guiListener) {
        this.plugin = plugin;
        this.guiListener = guiListener;
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Notify admins about updates
        if (player.hasPermission("rentabot.admin.notify") && plugin.getUpdateChecker() != null) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.getUpdateChecker().notifyPlayer(player);
                }
            }, 60L); // 3 seconds delay
        }
        
        // Check if player has any active bots
        var bots = plugin.getBotManager().getPlayerBots(player.getUniqueId());
        
        if (!bots.isEmpty()) {
            // Notify player about their active bots
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    int botCount = bots.size();
                    int onlineCount = (int) bots.stream().filter(RentableBot::isConnected).count();
                    
                    plugin.getMessageUtil().sendRaw(player, 
                        "&8&m-----------------------------");
                    plugin.getMessageUtil().sendRaw(player, 
                        "&#00D4FF&lRentABot &8» &7Welcome back!");
                    plugin.getMessageUtil().sendRaw(player, 
                        "&7You have &f" + botCount + " &7active bot(s)");
                    plugin.getMessageUtil().sendRaw(player, 
                        "&7Online: &a" + onlineCount + "&7/" + botCount);
                    plugin.getMessageUtil().sendRaw(player, 
                        "&7Use &f/rentabot list &7to view them");
                    plugin.getMessageUtil().sendRaw(player, 
                        "&8&m-----------------------------");
                }
            }, 80L); // 4 seconds delay (after update notification)
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up any pending GUI actions
        guiListener.getRenamingBot().remove(event.getPlayer().getUniqueId());
        guiListener.getPendingCreation().remove(event.getPlayer().getUniqueId());
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        
        // Check for pending bot creation (from shop GUI)
        Integer pendingHours = guiListener.getPendingCreation().get(player.getUniqueId());
        if (pendingHours != null) {
            event.setCancelled(true);
            guiListener.getPendingCreation().remove(player.getUniqueId());
            
            String message = event.getMessage().trim();
            
            // Check for cancel
            if (message.equalsIgnoreCase("cancel")) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getMessageUtil().sendRaw(player, "&cBot purchase cancelled.");
                });
                return;
            }
            
            // Validate the bot name before creating
            String validationError = validateBotName(message);
            if (validationError != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getMessageUtil().sendRaw(player, "&c" + validationError);
                    plugin.getMessageUtil().sendRaw(player, "");
                    plugin.getMessageUtil().sendRaw(player, "&7Try again or type &ccancel&7 to cancel.");
                    plugin.getMessageUtil().playSound(player, "on-error");
                    // Put back in pending state so they can try again
                    guiListener.getPendingCreation().put(player.getUniqueId(), pendingHours);
                });
                return;
            }
            
            // Check if name is already taken
            if (!plugin.getBotManager().isNameAvailable(message)) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getMessageUtil().sendRaw(player, "&cThat name is already taken!");
                    plugin.getMessageUtil().sendRaw(player, "");
                    plugin.getMessageUtil().sendRaw(player, "&7Try again or type &ccancel&7 to cancel.");
                    plugin.getMessageUtil().playSound(player, "on-error");
                    // Put back in pending state
                    guiListener.getPendingCreation().put(player.getUniqueId(), pendingHours);
                });
                return;
            }
            
            // Create bot on main thread
            final String botName = message;
            final int hours = pendingHours;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                RentalResult result = plugin.getRentalManager().createRental(player, botName, hours);
                
                if (result.success()) {
                    String priceStr = result.args().length > 2 ? result.args()[2] : "Free";
                    plugin.getMessageUtil().send(player, "create.success",
                        "bot", botName,
                        "hours", String.valueOf(hours),
                        "price", priceStr);
                    plugin.getMessageUtil().playSound(player, "on-create");
                } else {
                    String messageKey = "create." + result.messageKey();
                    if (result.args().length > 0) {
                        plugin.getMessageUtil().send(player, messageKey, "reason", result.args()[0]);
                    } else {
                        plugin.getMessageUtil().send(player, messageKey);
                    }
                    plugin.getMessageUtil().playSound(player, "on-error");
                }
            });
            return;
        }
        
        // Check for rename mode
        String botName = guiListener.getRenamingBot().get(player.getUniqueId());
        
        if (botName != null) {
            event.setCancelled(true);
            guiListener.getRenamingBot().remove(player.getUniqueId());
            
            String message = event.getMessage();
            
            // Check for cancel
            if (message.equalsIgnoreCase("cancel")) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.getMessageUtil().sendRaw(player, "&cRename cancelled.");
                });
                return;
            }
            
            // Perform rename on main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Optional<RentableBot> optBot = plugin.getBotManager().getBot(botName);
                if (optBot.isEmpty()) {
                    plugin.getMessageUtil().send(player, "general.bot-not-found", "bot", botName);
                    return;
                }
                
                RentableBot bot = optBot.get();
                String newName = message.trim();
                
                // Validate the new name
                String validationError = validateBotName(newName);
                if (validationError != null) {
                    plugin.getMessageUtil().sendRaw(player, "&c" + validationError);
                    plugin.getMessageUtil().playSound(player, "on-error");
                    return;
                }
                
                // Check if name is available
                if (!plugin.getBotManager().isNameAvailable(newName) && !newName.equalsIgnoreCase(botName)) {
                    plugin.getMessageUtil().send(player, "create.name-taken");
                    return;
                }
                
                // Rename the bot
                String oldName = bot.getInternalName();
                plugin.getBotManager().renameBot(oldName, newName);
                plugin.getMessageUtil().send(player, "rename.success", "old", oldName, "bot", newName);
                plugin.getMessageUtil().playSound(player, "on-rename");
            });
        }
    }
    
    /**
     * Validates a bot name for Minecraft username requirements.
     * @param name The name to validate
     * @return Error message if invalid, null if valid
     */
    private String validateBotName(String name) {
        if (name == null || name.isEmpty()) {
            return "Bot name cannot be empty!";
        }
        
        // Check length (considering prefix/suffix will be added)
        String prefix = plugin.getConfig().getString("bots.naming.prefix", "Bot_");
        String suffix = plugin.getConfig().getString("bots.naming.suffix", "");
        int totalLength = prefix.length() + name.length() + suffix.length();
        
        if (totalLength > 16) {
            int maxAllowed = 16 - prefix.length() - suffix.length();
            return "Name too long! Maximum " + maxAllowed + " characters (prefix '" + prefix + "' will be added).";
        }
        
        if (name.length() < 1) {
            return "Name must be at least 1 character!";
        }
        
        // Check for invalid characters
        if (!name.matches("^[a-zA-Z0-9_]+$")) {
            return "Invalid characters! Use only: A-Z, a-z, 0-9, underscore (_)";
        }
        
        // Check that final username is at least 3 chars (Minecraft requirement)
        String finalName = prefix + name + suffix;
        if (finalName.length() < 3) {
            return "Final bot name must be at least 3 characters!";
        }
        
        // Check for blocked words (case-insensitive)
        var blockedWords = plugin.getConfig().getStringList("bots.naming.blocked-words");
        String nameLower = name.toLowerCase();
        for (String blocked : blockedWords) {
            if (nameLower.contains(blocked.toLowerCase())) {
                return "Name contains blocked word: " + blocked;
            }
        }
        
        return null; // Valid
    }
}
