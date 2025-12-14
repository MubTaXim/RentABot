package com.ximpify.rentabot.gui;

import com.ximpify.rentabot.RentABot;
import com.ximpify.rentabot.bot.BotStatus;
import com.ximpify.rentabot.bot.RentableBot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Manages all GUI menus for RentABot.
 */
public class GUIManager {
    
    private final RentABot plugin;
    
    // GUI titles (used for identification)
    public static final String MAIN_MENU_TITLE = "§8§lRentABot §8- §7Main Menu";
    public static final String MY_BOTS_TITLE = "§8§lRentABot §8- §7My Bots";
    public static final String BOT_MANAGE_TITLE = "§8§lRentABot §8- §7Manage: ";
    public static final String CREATE_BOT_TITLE = "§8§lRentABot §8- §7Create Bot";
    public static final String SHOP_TITLE = "§8§lRentABot §8- §7Rent Duration";
    public static final String CONFIRM_TITLE = "§8§lRentABot §8- §7Confirm";
    
    // Track pending actions
    private final Map<UUID, PendingAction> pendingActions;
    
    public GUIManager(RentABot plugin) {
        this.plugin = plugin;
        this.pendingActions = new HashMap<>();
    }
    
    /**
     * Opens the main menu for a player.
     */
    public void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, net.kyori.adventure.text.Component.text(MAIN_MENU_TITLE));
        
        // Fill background
        fillBackground(inv, Material.GRAY_STAINED_GLASS_PANE);
        
        // My Bots button (slot 11)
        int activeCount = plugin.getBotManager().getPlayerActiveBotCount(player.getUniqueId());
        int reservedCount = plugin.getBotManager().getPlayerReservedBotCount(player.getUniqueId());
        int maxActive = plugin.getConfig().getInt("limits.max-active-bots", 3);
        int maxReserved = plugin.getConfig().getInt("limits.max-reserved-bots", 5);
        inv.setItem(11, createItem(Material.PLAYER_HEAD, 
            "§a§lMy Bots",
            "§7View and manage your bots",
            "",
            "§7Active: §a" + activeCount + "§7/§f" + maxActive,
            "§7Reserved: §e" + reservedCount + "§7/§f" + maxReserved,
            "",
            "§e▶ Click to view"
        ));
        
        // Create Bot button (slot 13)
        double pricePerHour = plugin.getConfig().getDouble("economy.price-per-hour", 5000);
        String priceDisplay = plugin.isEconomyEnabled() 
            ? plugin.getEconomyHandler().formatMoney(pricePerHour) + "/hour"
            : "Free";
        inv.setItem(13, createItem(Material.EMERALD,
            "§a§lRent a Bot",
            "§7Create a new AFK bot",
            "",
            "§7Price: §f" + priceDisplay,
            "§7Your Balance: §f" + getPlayerBalance(player),
            "",
            "§e▶ Click to rent"
        ));
        
        // Statistics button (slot 15)
        inv.setItem(15, createItem(Material.BOOK,
            "§b§lStatistics",
            "§7View your rental stats",
            "",
            "§7Total Bots Created: §f" + getPlayerTotalBots(player),
            "§7Currently Active: §f" + activeCount,
            "",
            "§e▶ Click to view"
        ));
        
        // Help button (slot 22)
        inv.setItem(22, createItem(Material.OAK_SIGN,
            "§e§lHelp",
            "§7Learn how to use RentABot",
            "",
            "§71. Rent a bot from the shop",
            "§72. Use §f/tpahere <bot> §7to summon",
            "§73. Bot will stay AFK at location",
            "§74. Bot respawns if killed",
            "",
            "§e▶ Click for more info"
        ));
        
        // Close button (slot 26)
        inv.setItem(26, createItem(Material.BARRIER,
            "§c§lClose",
            "§7Close this menu"
        ));
        
        player.openInventory(inv);
    }
    
    /**
     * Opens the "My Bots" menu showing all player's bots.
     */
    public void openMyBotsMenu(Player player) {
        Collection<RentableBot> bots = plugin.getBotManager().getPlayerBots(player.getUniqueId());
        
        // Calculate inventory size (min 27, max 54)
        int size = Math.min(54, Math.max(27, ((bots.size() / 7) + 1) * 9 + 18));
        Inventory inv = Bukkit.createInventory(null, size, net.kyori.adventure.text.Component.text(MY_BOTS_TITLE));
        
        // Fill background
        fillBackground(inv, Material.GRAY_STAINED_GLASS_PANE);
        
        // Add bot items
        int slot = 10;
        for (RentableBot bot : bots) {
            if (slot % 9 == 8) slot += 2; // Skip edges
            if (slot >= size - 9) break; // Leave room for navigation
            
            // Calculate time based on status
            String timeLeft;
            String statusLine;
            
            switch (bot.getStatus()) {
                case ACTIVE -> {
                    Duration remaining = Duration.between(Instant.now(), bot.getExpiresAt());
                    timeLeft = plugin.getRentalManager().formatTime(Math.max(0, remaining.toSeconds()));
                    statusLine = bot.isConnected() ? "§a● ACTIVE (Online)" : "§a● ACTIVE (Offline)";
                }
                case STOPPED -> {
                    timeLeft = plugin.getRentalManager().formatTime(bot.getRemainingSeconds());
                    statusLine = "§e● PAUSED";
                }
                case EXPIRED -> {
                    timeLeft = "0s";
                    statusLine = "§c● EXPIRED";
                }
                default -> {
                    timeLeft = "?";
                    statusLine = "§7● UNKNOWN";
                }
            }
            
            String health = String.format("%.1f", bot.getHealth());
            
            List<String> lore = new ArrayList<>();
            lore.add("§7Status: " + statusLine);
            if (bot.getStatus() == BotStatus.ACTIVE) {
                lore.add("§7Health: §c" + health + " ❤");
            }
            lore.add("§7Time Left: §e" + timeLeft);
            lore.add("");
            if (bot.hasSpawnPoint()) {
                lore.add("§7Spawn: §a✓ Set");
            } else {
                lore.add("§7Spawn: §c✗ Not set");
            }
            lore.add("");
            lore.add("§e▶ Click to manage");
            
            ItemStack botItem = createPlayerHead(bot.getDisplayName(),
                "§a§l" + bot.getInternalName(),
                lore.toArray(new String[0])
            );
            
            // Add status indicator glass pane next to bot if space allows
            inv.setItem(slot, botItem);
            slot++;
        }
        
        // No bots message
        if (bots.isEmpty()) {
            inv.setItem(13, createItem(Material.BARRIER,
                "§c§lNo Bots",
                "§7You don't have any active bots!",
                "",
                "§7Click §aRent a Bot §7to create one"
            ));
        }
        
        // Back button
        inv.setItem(size - 5, createItem(Material.ARROW,
            "§7§lBack",
            "§7Return to main menu"
        ));
        
        player.openInventory(inv);
    }
    
    /**
     * Opens the bot management menu for a specific bot.
     */
    public void openBotManageMenu(Player player, RentableBot bot) {
        Inventory inv = Bukkit.createInventory(null, 45, net.kyori.adventure.text.Component.text(BOT_MANAGE_TITLE + bot.getInternalName()));
        
        // Fill background
        fillBackground(inv, Material.GRAY_STAINED_GLASS_PANE);
        
        // Bot info head (slot 4)
        String timeLeft;
        String statusLine;
        
        switch (bot.getStatus()) {
            case ACTIVE -> {
                Duration remaining = Duration.between(Instant.now(), bot.getExpiresAt());
                timeLeft = plugin.getRentalManager().formatTime(Math.max(0, remaining.toSeconds()));
                statusLine = bot.isConnected() ? "§a● ACTIVE (Online)" : "§a● ACTIVE (Offline)";
            }
            case STOPPED -> {
                timeLeft = plugin.getRentalManager().formatTime(bot.getRemainingSeconds());
                statusLine = "§e● PAUSED";
            }
            case EXPIRED -> {
                timeLeft = "0s";
                statusLine = "§c● EXPIRED";
            }
            default -> {
                timeLeft = "?";
                statusLine = "§7● UNKNOWN";
            }
        }
        
        List<String> infoLore = new ArrayList<>();
        infoLore.add("");
        infoLore.add("§7Status: " + statusLine);
        if (bot.getStatus() == BotStatus.ACTIVE) {
            infoLore.add("§7Health: §c" + String.format("%.1f", bot.getHealth()) + " ❤");
            infoLore.add("§7Food: §e" + bot.getFood() + " 🍖");
        }
        infoLore.add("");
        infoLore.add("§7Time Remaining: §e" + timeLeft);
        if (bot.getStatus() == BotStatus.ACTIVE) {
            infoLore.add("§7Uptime: §f" + bot.getUptime());
        }
        infoLore.add("");
        if (bot.isPositionInitialized() && bot.getStatus() == BotStatus.ACTIVE) {
            infoLore.add("§7Position:");
            infoLore.add("§8  X: §f" + String.format("%.1f", bot.getX()));
            infoLore.add("§8  Y: §f" + String.format("%.1f", bot.getY()));
            infoLore.add("§8  Z: §f" + String.format("%.1f", bot.getZ()));
        }
        
        inv.setItem(4, createPlayerHead(bot.getDisplayName(),
            "§a§l" + bot.getInternalName(),
            infoLore.toArray(new String[0])
        ));
        
        // Different buttons based on bot status
        if (bot.getStatus() == BotStatus.ACTIVE) {
            // Active bot management buttons
            
            // Teleport Here button (slot 20)
            inv.setItem(20, createItem(Material.ENDER_PEARL,
                "§b§lTeleport to Me",
                "§7Teleport this bot to your location",
                "",
                "§7This will send a TPAHere request",
                "§7and the bot will accept it.",
                "",
                "§e▶ Click to teleport"
            ));
            
            // Extend Rental button (slot 22)
            double extendPrice = plugin.getConfig().getDouble("economy.price-per-hour", 5000);
            inv.setItem(22, createItem(Material.CLOCK,
                "§e§lExtend Rental",
                "§7Add more time to this bot",
                "",
                "§7Price: §f" + (plugin.isEconomyEnabled() 
                    ? plugin.getEconomyHandler().formatMoney(extendPrice) + "/hour"
                    : "Free"),
                "",
                "§e▶ Click to extend"
            ));
            
            // Spawn Point Info (slot 24)
            if (bot.hasSpawnPoint()) {
                inv.setItem(24, createItem(Material.RESPAWN_ANCHOR,
                    "§a§lSpawn Point",
                    "§7Bot will return here after death",
                    "",
                    "§7Location:",
                    "§8  World: §f" + (bot.getSavedWorld() != null ? bot.getSavedWorld() : "Unknown"),
                    "§8  X: §f" + String.format("%.1f", bot.getSavedX()),
                    "§8  Y: §f" + String.format("%.1f", bot.getSavedY()),
                    "§8  Z: §f" + String.format("%.1f", bot.getSavedZ()),
                    "",
                    "§7Status: §a✓ Active"
                ));
            } else {
                inv.setItem(24, createItem(Material.GRAY_BED,
                    "§c§lNo Spawn Point",
                    "§7Bot has no saved location",
                    "",
                    "§7Use §f/tpahere " + bot.getInternalName(),
                    "§7to set the spawn point.",
                    "",
                    "§7Status: §c✗ Not Set"
                ));
            }
            
            // Rename button (slot 29)
            inv.setItem(29, createItem(Material.NAME_TAG,
                "§6§lRename Bot",
                "§7Change this bot's name",
                "",
                "§7Current: §f" + bot.getInternalName(),
                "",
                "§e▶ Click to rename"
            ));
            
            // Reconnect button (slot 31) - only if disconnected
            if (!bot.isConnected()) {
                inv.setItem(31, createItem(Material.REDSTONE,
                    "§e§lReconnect",
                    "§7Force reconnect this bot",
                    "",
                    "§cBot is currently offline",
                    "",
                    "§e▶ Click to reconnect"
                ));
            } else {
                inv.setItem(31, createItem(Material.LIME_DYE,
                    "§a§lConnected",
                    "§7Bot is online and working",
                    "",
                    "§aNo action needed"
                ));
            }
            
            // Pause Bot button (slot 33)
            inv.setItem(33, createItem(Material.ORANGE_DYE,
                "§e§lPause Bot",
                "§7Pause this bot (saves time)",
                "",
                "§7Time will be frozen and saved.",
                "§7You can resume anytime.",
                "",
                "§e▶ Click to pause"
            ));
            
        } else if (bot.getStatus() == BotStatus.STOPPED || bot.getStatus() == BotStatus.EXPIRED) {
            // Stopped/Expired bot management buttons
            
            // Resume button (slot 20)
            boolean hasTime = bot.hasTimeRemaining();
            if (hasTime) {
                inv.setItem(20, createItem(Material.LIME_DYE,
                    "§a§lResume Bot",
                    "§7Continue your rental",
                    "",
                    "§7Time Remaining: §e" + timeLeft,
                    "§7Cost: §aFREE",
                    "",
                    "§a▶ Click to resume"
                ));
            } else {
                double resumePrice = plugin.getConfig().getDouble("economy.price-per-hour", 5000);
                inv.setItem(20, createItem(Material.GOLD_INGOT,
                    "§e§lResume with Hours",
                    "§7Add time to resume the bot",
                    "",
                    "§cBot has no time remaining!",
                    "§7Price: §f" + (plugin.isEconomyEnabled() 
                        ? plugin.getEconomyHandler().formatMoney(resumePrice) + "/hour"
                        : "Free"),
                    "",
                    "§e▶ Click to buy hours"
                ));
            }
            
            // Rename button (slot 22)
            inv.setItem(22, createItem(Material.NAME_TAG,
                "§6§lRename Bot",
                "§7Change this bot's name",
                "",
                "§7Current: §f" + bot.getInternalName(),
                "",
                "§e▶ Click to rename"
            ));
            
            // Spawn Point Info (slot 24)
            if (bot.hasSpawnPoint()) {
                inv.setItem(24, createItem(Material.RESPAWN_ANCHOR,
                    "§a§lSpawn Point",
                    "§7Bot will spawn here when resumed",
                    "",
                    "§7Location:",
                    "§8  World: §f" + (bot.getSavedWorld() != null ? bot.getSavedWorld() : "Unknown"),
                    "§8  X: §f" + String.format("%.1f", bot.getSavedX()),
                    "§8  Y: §f" + String.format("%.1f", bot.getSavedY()),
                    "§8  Z: §f" + String.format("%.1f", bot.getSavedZ()),
                    "",
                    "§7Status: §a✓ Saved"
                ));
            } else {
                inv.setItem(24, createItem(Material.GRAY_BED,
                    "§c§lNo Spawn Point",
                    "§7Bot has no saved location",
                    "",
                    "§7You can set it after resuming."
                ));
            }
            
            // Delete Bot button (slot 33)
            inv.setItem(33, createItem(Material.TNT,
                "§c§lDelete Bot",
                "§7Permanently delete this bot",
                "",
                "§c⚠ This cannot be undone!",
                "§cAll saved data will be lost.",
                "",
                "§c▶ Click to delete"
            ));
        }
        
        // Back button (slot 40)
        inv.setItem(40, createItem(Material.ARROW,
            "§7§lBack",
            "§7Return to bot list"
        ));
        
        player.openInventory(inv);
    }
    
    /**
     * Opens the shop/duration selection menu.
     */
    public void openShopMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 45, net.kyori.adventure.text.Component.text(SHOP_TITLE));
        
        // Fill background
        fillBackground(inv, Material.GRAY_STAINED_GLASS_PANE);
        
        // Info item at top
        String balance = getPlayerBalance(player);
        inv.setItem(4, createItem(Material.GOLD_INGOT,
            "§e§lRent a Bot",
            "§7Select rental duration below",
            "",
            "§7Your Balance: §f" + balance
        ));
        
        // Duration options
        int[] hours = {1, 3, 6, 12, 24, 48, 72, 168};
        String[] names = {"1 Hour", "3 Hours", "6 Hours", "12 Hours", "1 Day", "2 Days", "3 Days", "1 Week"};
        Material[] materials = {
            Material.COAL, Material.IRON_INGOT, Material.COPPER_INGOT, Material.GOLD_INGOT,
            Material.DIAMOND, Material.EMERALD, Material.NETHERITE_INGOT, Material.NETHER_STAR
        };
        
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 31};
        
        for (int i = 0; i < hours.length; i++) {
            double price = calculatePrice(hours[i]);
            String priceStr = plugin.isEconomyEnabled() 
                ? plugin.getEconomyHandler().formatMoney(price)
                : "Free";
            
            boolean canAfford = !plugin.isEconomyEnabled() || 
                plugin.getEconomyHandler().hasBalance(player, price);
            
            String affordStr = canAfford ? "§a✓ Can afford" : "§c✗ Not enough money";
            
            inv.setItem(slots[i], createItem(materials[i],
                (canAfford ? "§a" : "§c") + "§l" + names[i],
                "§7Rent a bot for " + names[i].toLowerCase(),
                "",
                "§7Price: §f" + priceStr,
                affordStr,
                "",
                canAfford ? "§e▶ Click to purchase" : "§c▶ Insufficient funds"
            ));
        }
        
        // Custom duration (slot 37)
        inv.setItem(37, createItem(Material.ANVIL,
            "§d§lCustom Duration",
            "§7Enter a custom rental time",
            "",
            "§7Use command:",
            "§f/rentabot create <hours> [name]",
            "",
            "§e▶ Click for help"
        ));
        
        // Back button (slot 40)
        inv.setItem(40, createItem(Material.ARROW,
            "§7§lBack",
            "§7Return to main menu"
        ));
        
        player.openInventory(inv);
    }
    
    /**
     * Opens the extend rental menu.
     */
    public void openExtendMenu(Player player, RentableBot bot) {
        Inventory inv = Bukkit.createInventory(null, 36, net.kyori.adventure.text.Component.text("§8§lExtend: " + bot.getInternalName()));
        
        // Fill background
        fillBackground(inv, Material.GRAY_STAINED_GLASS_PANE);
        
        // Current status
        Duration remaining = Duration.between(Instant.now(), bot.getExpiresAt());
        String timeLeft = plugin.getRentalManager().formatTime(Math.max(0, remaining.toSeconds()));
        
        inv.setItem(4, createItem(Material.CLOCK,
            "§e§lExtend Rental",
            "§7Current time remaining: §f" + timeLeft,
            "",
            "§7Select additional time below"
        ));
        
        // Extension options
        int[] hours = {1, 6, 12, 24, 48};
        String[] names = {"+1 Hour", "+6 Hours", "+12 Hours", "+1 Day", "+2 Days"};
        int[] slots = {19, 20, 21, 22, 23};
        
        for (int i = 0; i < hours.length; i++) {
            double price = calculatePrice(hours[i]);
            String priceStr = plugin.isEconomyEnabled() 
                ? plugin.getEconomyHandler().formatMoney(price)
                : "Free";
            
            boolean canAfford = !plugin.isEconomyEnabled() || 
                plugin.getEconomyHandler().hasBalance(player, price);
            
            inv.setItem(slots[i], createItem(Material.EXPERIENCE_BOTTLE,
                (canAfford ? "§a" : "§c") + "§l" + names[i],
                "§7Add " + hours[i] + " hour(s)",
                "",
                "§7Price: §f" + priceStr,
                canAfford ? "§a✓ Can afford" : "§c✗ Not enough money",
                "",
                canAfford ? "§e▶ Click to extend" : "§c▶ Insufficient funds"
            ));
        }
        
        // Back button
        inv.setItem(31, createItem(Material.ARROW,
            "§7§lBack",
            "§7Return to bot management"
        ));
        
        player.openInventory(inv);
    }
    
    /**
     * Opens the resume hours selection menu for expired/stopped bots without time.
     */
    public void openResumeHoursMenu(Player player, RentableBot bot) {
        Inventory inv = Bukkit.createInventory(null, 36, net.kyori.adventure.text.Component.text("§8§lResume: " + bot.getInternalName()));
        
        // Fill background
        fillBackground(inv, Material.GRAY_STAINED_GLASS_PANE);
        
        // Info
        inv.setItem(4, createItem(Material.CLOCK,
            "§e§lResume Bot",
            "§cBot has no time remaining!",
            "",
            "§7Select rental hours to resume:"
        ));
        
        // Hour options
        int[] hours = {1, 6, 12, 24, 48};
        String[] names = {"1 Hour", "6 Hours", "12 Hours", "1 Day", "2 Days"};
        int[] slots = {19, 20, 21, 22, 23};
        
        for (int i = 0; i < hours.length; i++) {
            double price = calculatePrice(hours[i]);
            String priceStr = plugin.isEconomyEnabled() 
                ? plugin.getEconomyHandler().formatMoney(price)
                : "Free";
            
            boolean canAfford = !plugin.isEconomyEnabled() || 
                plugin.getEconomyHandler().hasBalance(player, price);
            
            inv.setItem(slots[i], createItem(Material.EXPERIENCE_BOTTLE,
                (canAfford ? "§a" : "§c") + "§l" + names[i],
                "§7Resume with " + hours[i] + " hour(s)",
                "",
                "§7Price: §f" + priceStr,
                canAfford ? "§a✓ Can afford" : "§c✗ Not enough money",
                "",
                canAfford ? "§e▶ Click to resume" : "§c▶ Insufficient funds"
            ));
        }
        
        // Store bot name for handler
        pendingActions.put(player.getUniqueId(), new PendingAction("resume", bot.getInternalName(), null));
        
        // Back button
        inv.setItem(31, createItem(Material.ARROW,
            "§7§lBack",
            "§7Return to bot management"
        ));
        
        player.openInventory(inv);
    }
    
    /**
     * Opens a confirmation dialog.
     */
    public void openConfirmMenu(Player player, String action, String target, Runnable onConfirm) {
        Inventory inv = Bukkit.createInventory(null, 27, net.kyori.adventure.text.Component.text(CONFIRM_TITLE));
        
        // Fill background
        fillBackground(inv, Material.GRAY_STAINED_GLASS_PANE);
        
        // Info
        inv.setItem(4, createItem(Material.PAPER,
            "§e§lConfirm Action",
            "§7" + action,
            "§7Target: §f" + target
        ));
        
        // Confirm button
        inv.setItem(11, createItem(Material.LIME_WOOL,
            "§a§lConfirm",
            "§7Click to proceed"
        ));
        
        // Cancel button
        inv.setItem(15, createItem(Material.RED_WOOL,
            "§c§lCancel",
            "§7Click to cancel"
        ));
        
        // Store pending action
        pendingActions.put(player.getUniqueId(), new PendingAction(action, target, onConfirm));
        
        player.openInventory(inv);
    }
    
    // ==================== Helper Methods ====================
    
    private void fillBackground(Inventory inv, Material material) {
        ItemStack filler = createItem(material, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }
    
    private ItemStack createItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(name));
            if (lore.length > 0) {
                meta.lore(Arrays.stream(lore).map(net.kyori.adventure.text.Component::text).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }
    
    private ItemStack createPlayerHead(String playerName, String displayName, String... lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerName));
            meta.displayName(net.kyori.adventure.text.Component.text(displayName));
            if (lore.length > 0) {
                meta.lore(Arrays.stream(lore).map(net.kyori.adventure.text.Component::text).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }
    
    private String getPlayerBalance(Player player) {
        if (!plugin.isEconomyEnabled()) return "Economy Disabled";
        return plugin.getEconomyHandler().formatMoney(plugin.getEconomyHandler().getBalance(player));
    }
    
    private double calculatePrice(int hours) {
        return plugin.getConfig().getDouble("economy.price-per-hour", 5000) * hours;
    }
    
    private int getPlayerTotalBots(Player player) {
        // This could be tracked in database, for now return current count
        return plugin.getBotManager().getPlayerBotCount(player.getUniqueId());
    }
    
    public PendingAction getPendingAction(UUID uuid) {
        return pendingActions.get(uuid);
    }
    
    public void removePendingAction(UUID uuid) {
        pendingActions.remove(uuid);
    }
    
    /**
     * Represents a pending confirmation action.
     */
    public static class PendingAction {
        public final String action;
        public final String target;
        public final Runnable onConfirm;
        
        public PendingAction(String action, String target, Runnable onConfirm) {
            this.action = action;
            this.target = target;
            this.onConfirm = onConfirm;
        }
    }
}
