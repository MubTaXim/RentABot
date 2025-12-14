package com.ximpify.rentabot.gui;

import com.ximpify.rentabot.RentABot;
import com.ximpify.rentabot.bot.BotStatus;
import com.ximpify.rentabot.bot.RentableBot;
import com.ximpify.rentabot.rental.RentalManager.RentalResult;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles all GUI click events.
 */
public class GUIListener implements Listener {
    
    private final RentABot plugin;
    private final GUIManager guiManager;
    
    // Track which bot player is managing
    private final Map<UUID, String> managingBot;
    // Track rename mode
    private final Map<UUID, String> renamingBot;
    
    public GUIListener(RentABot plugin, GUIManager guiManager) {
        this.plugin = plugin;
        this.guiManager = guiManager;
        this.managingBot = new HashMap<>();
        this.renamingBot = new HashMap<>();
    }
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        
        // Check if it's one of our GUIs
        if (!isRentABotGUI(title)) return;
        
        event.setCancelled(true);
        
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;
        
        int slot = event.getRawSlot();
        
        // Route to appropriate handler
        if (title.equals(GUIManager.MAIN_MENU_TITLE)) {
            handleMainMenu(player, slot, clicked);
        } else if (title.equals(GUIManager.MY_BOTS_TITLE)) {
            handleMyBots(player, slot, clicked);
        } else if (title.startsWith(GUIManager.BOT_MANAGE_TITLE)) {
            String botName = title.replace(GUIManager.BOT_MANAGE_TITLE, "");
            managingBot.put(player.getUniqueId(), botName);
            handleBotManage(player, slot, clicked, botName);
        } else if (title.equals(GUIManager.SHOP_TITLE)) {
            handleShop(player, slot, clicked);
        } else if (title.startsWith("§8§lExtend: ")) {
            String botName = title.replace("§8§lExtend: ", "");
            handleExtend(player, slot, clicked, botName);
        } else if (title.startsWith("§8§lResume: ")) {
            String botName = title.replace("§8§lResume: ", "");
            handleResume(player, slot, clicked, botName);
        } else if (title.equals(GUIManager.CONFIRM_TITLE)) {
            handleConfirm(player, slot);
        }
    }
    
    private boolean isRentABotGUI(String title) {
        return title.equals(GUIManager.MAIN_MENU_TITLE) ||
               title.equals(GUIManager.MY_BOTS_TITLE) ||
               title.startsWith(GUIManager.BOT_MANAGE_TITLE) ||
               title.equals(GUIManager.SHOP_TITLE) ||
               title.startsWith("§8§lExtend: ") ||
               title.startsWith("§8§lResume: ") ||
               title.equals(GUIManager.CONFIRM_TITLE);
    }
    
    private void handleMainMenu(Player player, int slot, ItemStack clicked) {
        switch (slot) {
            case 11 -> { // My Bots
                player.closeInventory();
                guiManager.openMyBotsMenu(player);
            }
            case 13 -> { // Create Bot / Shop
                player.closeInventory();
                guiManager.openShopMenu(player);
            }
            case 15 -> { // Statistics
                // For now, just show message
                player.closeInventory();
                plugin.getMessageUtil().sendRaw(player, "&7&m-----------&r &b&lYour Stats &7&m-----------");
                plugin.getMessageUtil().sendRaw(player, "&7Active Bots: &f" + 
                    plugin.getBotManager().getPlayerBotCount(player.getUniqueId()));
                plugin.getMessageUtil().sendRaw(player, "&7&m--------------------------------");
            }
            case 22 -> { // Help
                player.closeInventory();
                player.performCommand("rentabot help");
            }
            case 26 -> { // Close
                player.closeInventory();
            }
        }
    }
    
    private void handleMyBots(Player player, int slot, ItemStack clicked) {
        // Back button
        if (clicked.getType() == Material.ARROW) {
            player.closeInventory();
            guiManager.openMainMenu(player);
            return;
        }
        
        // Bot head clicked
        if (clicked.getType() == Material.PLAYER_HEAD) {
            String displayName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(clicked.getItemMeta().displayName());
            // Extract bot name - strip color codes and formatting codes (§ followed by any char)
            String botName = displayName.replaceAll("§[0-9a-fklmnor]", "").replaceAll("[§&][0-9a-fklmnor]", "").trim();
            
            // Try to find bot - first try exact match, then try searching through player's bots
            Optional<RentableBot> optBot = plugin.getBotManager().getBot(botName);
            
            // If not found, search through player's bots by internal name
            if (optBot.isEmpty()) {
                for (RentableBot bot : plugin.getBotManager().getPlayerBots(player.getUniqueId())) {
                    if (bot.getInternalName().equalsIgnoreCase(botName) || 
                        bot.getDisplayName().equalsIgnoreCase(botName) ||
                        bot.getInternalName().toLowerCase().contains(botName.toLowerCase())) {
                        optBot = Optional.of(bot);
                        break;
                    }
                }
            }
            
            if (optBot.isPresent()) {
                player.closeInventory();
                guiManager.openBotManageMenu(player, optBot.get());
            } else {
                plugin.getMessageUtil().send(player, "general.bot-not-found", "bot", botName);
            }
        }
    }
    
    private void handleBotManage(Player player, int slot, ItemStack clicked, String botName) {
        Optional<RentableBot> optBot = plugin.getBotManager().getBot(botName);
        if (optBot.isEmpty()) {
            player.closeInventory();
            plugin.getMessageUtil().send(player, "general.bot-not-found", "bot", botName);
            return;
        }
        
        RentableBot bot = optBot.get();
        BotStatus status = bot.getStatus();
        
        // Handle common back button
        if (slot == 40) {
            player.closeInventory();
            guiManager.openMyBotsMenu(player);
            return;
        }
        
        // Handle based on bot status
        if (status == BotStatus.ACTIVE) {
            // Active bot buttons
            switch (slot) {
                case 20 -> { // Teleport Here
                    player.closeInventory();
                    plugin.getMessageUtil().sendRaw(player, "&aSending TPAHere request to &f" + botName + "&a...");
                    player.performCommand("tpahere " + bot.getDisplayName());
                }
                case 22 -> { // Extend Rental
                    player.closeInventory();
                    guiManager.openExtendMenu(player, bot);
                }
                case 29 -> { // Rename
                    player.closeInventory();
                    renamingBot.put(player.getUniqueId(), botName);
                    plugin.getMessageUtil().sendRaw(player, "&eType the new name in chat:");
                    plugin.getMessageUtil().sendRaw(player, "&7Type &ccancel &7to cancel.");
                }
                case 31 -> { // Reconnect (if offline)
                    if (!bot.isConnected()) {
                        player.closeInventory();
                        plugin.getMessageUtil().sendRaw(player, "&eAttempting to reconnect &f" + botName + "&e...");
                        bot.reconnect();
                    }
                }
                case 33 -> { // Pause Bot
                    player.closeInventory();
                    guiManager.openConfirmMenu(player, "Pause Bot", botName, () -> {
                        RentalResult result = plugin.getRentalManager().stopRental(player, botName, false);
                        if (result.success()) {
                            String timeLeft = plugin.getRentalManager().formatTime(bot.getRemainingSeconds());
                            plugin.getMessageUtil().send(player, "stop.success", "bot", botName, "time", timeLeft);
                        }
                    });
                }
            }
        } else {
            // Stopped/Expired bot buttons
            switch (slot) {
                case 20 -> { // Resume Bot
                    player.closeInventory();
                    if (bot.hasTimeRemaining()) {
                        // Free resume - just resume
                        RentalResult result = plugin.getRentalManager().resumeRental(player, botName, 0);
                        if (result.success()) {
                            plugin.getMessageUtil().send(player, "resume.success", "bot", botName, 
                                "time", plugin.getRentalManager().formatTime(bot.getRemainingSeconds()));
                        } else {
                            String messageKey = switch (result.messageKey()) {
                                case "max-active-reached" -> "resume.max-active";
                                case "already-active" -> "resume.already-active";
                                default -> "resume." + result.messageKey();
                            };
                            plugin.getMessageUtil().send(player, messageKey, "bot", botName);
                        }
                    } else {
                        // Need to buy hours - open shop for resume
                        guiManager.openResumeHoursMenu(player, bot);
                    }
                }
                case 22 -> { // Rename
                    player.closeInventory();
                    renamingBot.put(player.getUniqueId(), botName);
                    plugin.getMessageUtil().sendRaw(player, "&eType the new name in chat:");
                    plugin.getMessageUtil().sendRaw(player, "&7Type &ccancel &7to cancel.");
                }
                case 33 -> { // Delete Bot
                    player.closeInventory();
                    guiManager.openConfirmMenu(player, "Delete Bot", botName, () -> {
                        RentalResult result = plugin.getRentalManager().deleteRental(player, botName, false);
                        if (result.success()) {
                            plugin.getMessageUtil().send(player, "delete.success", "bot", botName);
                        }
                    });
                }
            }
        }
    }
    
    private void handleShop(Player player, int slot, ItemStack clicked) {
        // Back button
        if (slot == 40) {
            player.closeInventory();
            guiManager.openMainMenu(player);
            return;
        }
        
        // Custom duration
        if (slot == 37) {
            player.closeInventory();
            plugin.getMessageUtil().sendRaw(player, "&eUse: &f/rentabot create <hours> [name]");
            return;
        }
        
        // Duration buttons
        int[] hours = {1, 3, 6, 12, 24, 48, 72, 168};
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 31};
        
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i]) {
                int duration = hours[i];
                double price = plugin.getConfig().getDouble("economy.price-per-hour", 5000) * duration;
                
                // Check if can afford
                if (plugin.isEconomyEnabled() && !plugin.getEconomyHandler().hasBalance(player, price)) {
                    plugin.getMessageUtil().sendRaw(player, "&cYou don't have enough money!");
                    return;
                }
                
                player.closeInventory();
                
                // Generate bot name
                String botName = player.getName() + "_Bot" + 
                    (plugin.getBotManager().getPlayerBotCount(player.getUniqueId()) + 1);
                
                // Create the bot
                RentalResult result = plugin.getRentalManager().createRental(player, botName, duration);
                
                if (result.success()) {
                    String priceStr = result.args().length > 2 ? result.args()[2] : "Free";
                    plugin.getMessageUtil().send(player, "create.success",
                        "bot", botName,
                        "hours", String.valueOf(duration),
                        "price", priceStr);
                } else {
                    String messageKey = "create." + result.messageKey();
                    // Pass reason if available (for invalid-name errors)
                    if (result.args().length > 0) {
                        plugin.getMessageUtil().send(player, messageKey, "reason", result.args()[0]);
                    } else {
                        plugin.getMessageUtil().send(player, messageKey);
                    }
                }
                return;
            }
        }
    }
    
    private void handleExtend(Player player, int slot, ItemStack clicked, String botName) {
        // Back button
        if (slot == 31) {
            Optional<RentableBot> optBot = plugin.getBotManager().getBot(botName);
            if (optBot.isPresent()) {
                player.closeInventory();
                guiManager.openBotManageMenu(player, optBot.get());
            }
            return;
        }
        
        // Extension buttons
        int[] hours = {1, 6, 12, 24, 48};
        int[] slots = {19, 20, 21, 22, 23};
        
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i]) {
                int duration = hours[i];
                
                player.closeInventory();
                RentalResult result = plugin.getRentalManager().extendRental(player, botName, duration);
                
                if (result.success()) {
                    plugin.getMessageUtil().send(player, "extend.success",
                        "hours", String.valueOf(duration),
                        "time", result.args()[1]);
                } else {
                    plugin.getMessageUtil().send(player, "extend.failed", "reason", result.messageKey());
                }
                return;
            }
        }
    }
    
    private void handleResume(Player player, int slot, ItemStack clicked, String botName) {
        // Back button
        if (slot == 31) {
            Optional<RentableBot> optBot = plugin.getBotManager().getBot(botName);
            if (optBot.isPresent()) {
                player.closeInventory();
                guiManager.openBotManageMenu(player, optBot.get());
            }
            return;
        }
        
        // Resume hour buttons
        int[] hours = {1, 6, 12, 24, 48};
        int[] slots = {19, 20, 21, 22, 23};
        
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i]) {
                int duration = hours[i];
                
                player.closeInventory();
                guiManager.removePendingAction(player.getUniqueId());
                
                RentalResult result = plugin.getRentalManager().resumeRental(player, botName, duration);
                
                if (result.success()) {
                    Optional<RentableBot> optBot = plugin.getBotManager().getBot(botName);
                    String timeLeft = optBot.isPresent() 
                        ? plugin.getRentalManager().formatTime(duration * 3600L)
                        : duration + "h";
                    plugin.getMessageUtil().send(player, "resume.success",
                        "bot", botName,
                        "time", timeLeft);
                } else {
                    String messageKey = switch (result.messageKey()) {
                        case "max-active-reached" -> "resume.max-active";
                        case "insufficient-funds" -> "economy.insufficient-funds";
                        default -> "resume." + result.messageKey();
                    };
                    if (result.args() != null && result.args().length > 0) {
                        plugin.getMessageUtil().send(player, messageKey, "price", result.args()[0]);
                    } else {
                        plugin.getMessageUtil().send(player, messageKey, "bot", botName);
                    }
                }
                return;
            }
        }
    }
    
    private void handleConfirm(Player player, int slot) {
        GUIManager.PendingAction action = guiManager.getPendingAction(player.getUniqueId());
        if (action == null) {
            player.closeInventory();
            return;
        }
        
        if (slot == 11) { // Confirm
            player.closeInventory();
            guiManager.removePendingAction(player.getUniqueId());
            // Check for null onConfirm (can be null for resume actions)
            if (action.onConfirm != null) {
                action.onConfirm.run();
            }
        } else if (slot == 15) { // Cancel
            player.closeInventory();
            guiManager.removePendingAction(player.getUniqueId());
            plugin.getMessageUtil().sendRaw(player, "&cAction cancelled.");
        }
    }
    
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        
        // Clean up pending actions after a delay (in case reopening)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            String currentTitle = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(player.getOpenInventory().title());
            if (currentTitle == null || currentTitle.isEmpty() || !isRentABotGUI(currentTitle)) {
                guiManager.removePendingAction(player.getUniqueId());
            }
        }, 5L);
    }
    
    // Expose rename map for chat listener
    public Map<UUID, String> getRenamingBot() {
        return renamingBot;
    }
}
