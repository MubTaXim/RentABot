package com.ximpify.rentabot.listeners;

import com.ximpify.rentabot.RentABot;
import com.ximpify.rentabot.bot.RentableBot;
import com.ximpify.rentabot.gui.GUIListener;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Optional;

/**
 * Handles player-related events for RentABot.
 */
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
    }
    
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String botName = guiListener.getRenamingBot().get(player.getUniqueId());
        
        if (botName != null) {
            event.setCancelled(true);
            guiListener.getRenamingBot().remove(player.getUniqueId());
            
            String message = PlainTextComponentSerializer.plainText().serialize(event.message());
            
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
                
                // Check if name is available
                if (!plugin.getBotManager().isNameAvailable(newName) && !newName.equalsIgnoreCase(botName)) {
                    plugin.getMessageUtil().send(player, "create.name-taken");
                    return;
                }
                
                // Rename the bot
                String oldName = bot.getInternalName();
                plugin.getBotManager().renameBot(oldName, newName);
                plugin.getMessageUtil().send(player, "rename.success", "old", oldName, "bot", newName);
            });
        }
    }
}
