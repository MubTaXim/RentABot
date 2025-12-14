package com.ximpify.rentabot;

import com.ximpify.rentabot.bot.BotManager;
import com.ximpify.rentabot.bot.RentableBot;
import com.ximpify.rentabot.commands.RentCommand;
import com.ximpify.rentabot.commands.AdminCommand;
import com.ximpify.rentabot.gui.GUIListener;
import com.ximpify.rentabot.gui.GUIManager;
import com.ximpify.rentabot.hooks.EconomyHandler;
import com.ximpify.rentabot.hooks.PlaceholderAPIHook;
import com.ximpify.rentabot.listeners.PlayerListener;
import com.ximpify.rentabot.rental.RentalManager;
import com.ximpify.rentabot.storage.StorageManager;
import com.ximpify.rentabot.util.ConfigMigrator;
import com.ximpify.rentabot.util.MessageUtil;
import com.ximpify.rentabot.util.ReloadManager;
import com.ximpify.rentabot.util.UpdateChecker;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class RentABot extends JavaPlugin {
    
    private static RentABot instance;
    
    private BotManager botManager;
    private RentalManager rentalManager;
    private StorageManager storageManager;
    private EconomyHandler economyHandler;
    private MessageUtil messageUtil;
    private GUIManager guiManager;
    private GUIListener guiListener;
    private UpdateChecker updateChecker;
    private ReloadManager reloadManager;
    
    private boolean economyEnabled = false;
    private boolean placeholderAPIEnabled = false;
    private boolean essentialsEnabled = false;
    
    @Override
    public void onEnable() {
        instance = this;
        
        // Save default configs
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("shopguiplus-example.yml", false);
        
        // Migrate configs if needed (handles version updates)
        ConfigMigrator configMigrator = new ConfigMigrator(this);
        configMigrator.migrateIfNeeded();
        configMigrator.migrateMessages();
        
        // Initialize utilities
        this.messageUtil = new MessageUtil(this);
        this.reloadManager = new ReloadManager(this);
        
        // Setup storage
        this.storageManager = new StorageManager(this);
        if (!storageManager.initialize()) {
            getLogger().severe("Failed to initialize storage! Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Setup economy
        setupEconomy();
        
        // Setup hooks
        setupHooks();
        
        // Initialize managers
        this.botManager = new BotManager(this);
        this.rentalManager = new RentalManager(this);
        this.guiManager = new GUIManager(this);
        this.guiListener = new GUIListener(this, guiManager);
        
        // Register commands
        registerCommands();
        
        // Register listeners
        registerListeners();
        
        // Load existing rentals
        rentalManager.loadRentals();
        
        // Start tasks
        startTasks();
        
        // Check for updates
        this.updateChecker = new UpdateChecker(this, "MubTaXim", "RentABot");
        updateChecker.checkForUpdates();
        
        // Startup message
        getLogger().info("╔════════════════════════════════════════╗");
        getLogger().info("║          RentABot v" + getPluginMeta().getVersion() + " Enabled          ║");
        getLogger().info("║      Pure Java AFK Bot Rental System    ║");
        getLogger().info("╠════════════════════════════════════════╣");
        getLogger().info("║  Economy: " + (economyEnabled ? "§aEnabled" : "§cDisabled") + "                       ║");
        getLogger().info("║  PlaceholderAPI: " + (placeholderAPIEnabled ? "§aEnabled" : "§cDisabled") + "               ║");
        getLogger().info("║  Essentials: " + (essentialsEnabled ? "§aEnabled" : "§cDisabled") + "                   ║");
        getLogger().info("╚════════════════════════════════════════╝");
    }
    
    @Override
    public void onDisable() {
        // Disconnect all bots gracefully
        if (botManager != null) {
            getLogger().info("Disconnecting all bots...");
            botManager.disconnectAll();
        }
        
        // Save rental data
        if (rentalManager != null) {
            rentalManager.saveRentals();
        }
        
        // Close storage
        if (storageManager != null) {
            storageManager.close();
        }
        
        getLogger().info("RentABot disabled!");
    }
    
    private void setupEconomy() {
        if (!getConfig().getBoolean("economy.enabled", true)) {
            getLogger().info("Economy is disabled in config.");
            return;
        }
        
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found! Economy features will be disabled.");
            return;
        }
        
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().warning("No economy plugin found! Economy features will be disabled.");
            return;
        }
        
        Economy economy = rsp.getProvider();
        this.economyHandler = new EconomyHandler(this, economy);
        this.economyEnabled = true;
        getLogger().info("Economy hooked into: " + economy.getName());
    }
    
    private void setupHooks() {
        // PlaceholderAPI
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            if (getConfig().getBoolean("hooks.placeholderapi.enabled", true)) {
                new PlaceholderAPIHook(this).register();
                placeholderAPIEnabled = true;
                getLogger().info("PlaceholderAPI hook enabled!");
            }
        }
        
        // Essentials
        if (getServer().getPluginManager().getPlugin("Essentials") != null) {
            if (getConfig().getBoolean("hooks.essentials.enabled", true)) {
                essentialsEnabled = true;
                getLogger().info("Essentials hook enabled!");
            }
        }
    }
    
    private void registerCommands() {
        RentCommand rentCommand = new RentCommand(this);
        getCommand("rentabot").setExecutor(rentCommand);
        getCommand("rentabot").setTabCompleter(rentCommand);
        
        AdminCommand adminCommand = new AdminCommand(this);
        getCommand("rentabotadmin").setExecutor(adminCommand);
        getCommand("rentabotadmin").setTabCompleter(adminCommand);
    }
    
    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerListener(this, guiListener), this);
        getServer().getPluginManager().registerEvents(guiListener, this);
    }
    
    private void startTasks() {
        // Rental check task
        int checkInterval = getConfig().getInt("advanced.check-interval", 30) * 20;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            rentalManager.checkExpiredRentals();
            botManager.checkBotStatus();
        }, checkInterval, checkInterval);
        
        // Anti-AFK task with randomized intervals
        if (getConfig().getBoolean("bots.behavior.anti-afk.enabled", true)) {
            int baseInterval = getConfig().getInt("bots.behavior.anti-afk.interval", 45) * 20;
            double randomness = getConfig().getDouble("bots.behavior.anti-afk.interval-randomness", 0.4);
            
            // Schedule individual random interval tasks per bot instead of fixed global interval
            scheduleAntiAFKTask(baseInterval, randomness);
            debug("Anti-AFK task started with base interval: " + baseInterval / 20 + " seconds, randomness: " + (randomness * 100) + "%");
        }
    }
    
    private void scheduleAntiAFKTask(int baseInterval, double randomness) {
        // Calculate randomized interval: base ± (base * randomness)
        int minInterval = (int) (baseInterval * (1.0 - randomness));
        int maxInterval = (int) (baseInterval * (1.0 + randomness));
        int actualInterval = minInterval + (int) (Math.random() * (maxInterval - minInterval));
        
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            // Perform anti-AFK for all connected bots
            for (RentableBot bot : botManager.getAllBots()) {
                if (bot.isConnected()) {
                    bot.performAntiAFK();
                }
            }
            // Schedule next with new random interval
            scheduleAntiAFKTask(baseInterval, randomness);
        }, actualInterval);
    }
    
    public void reload() {
        // Use ReloadManager for comprehensive reload
        reloadManager.reloadAll();
    }
    
    /**
     * Gets the reload manager for advanced reload operations.
     */
    public ReloadManager getReloadManager() {
        return reloadManager;
    }
    
    // Getters
    public static RentABot getInstance() {
        return instance;
    }
    
    public BotManager getBotManager() {
        return botManager;
    }
    
    public RentalManager getRentalManager() {
        return rentalManager;
    }
    
    public StorageManager getStorageManager() {
        return storageManager;
    }
    
    public EconomyHandler getEconomyHandler() {
        return economyHandler;
    }
    
    public MessageUtil getMessageUtil() {
        return messageUtil;
    }
    
    public GUIManager getGUIManager() {
        return guiManager;
    }
    
    public GUIListener getGUIListener() {
        return guiListener;
    }
    
    public boolean isEconomyEnabled() {
        return economyEnabled;
    }
    
    public boolean isPlaceholderAPIEnabled() {
        return placeholderAPIEnabled;
    }
    
    public boolean isEssentialsEnabled() {
        return essentialsEnabled;
    }
    
    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }
    
    public void debug(String message) {
        if (getConfig().getBoolean("advanced.debug", false)) {
            getLogger().info("[DEBUG] " + message);
        }
    }
}
