package com.ximpify.rentabot.hooks;

import com.ximpify.rentabot.RentABot;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;

/**
 * Handles economy operations via Vault.
 */
public class EconomyHandler {
    
    private final RentABot plugin;
    private final Economy economy;
    
    public EconomyHandler(RentABot plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
    }
    
    /**
     * Checks if a player has enough balance.
     */
    public boolean hasBalance(Player player, double amount) {
        return economy.has(player, amount);
    }
    
    /**
     * Gets a player's balance.
     */
    public double getBalance(Player player) {
        return economy.getBalance(player);
    }
    
    /**
     * Withdraws money from a player.
     */
    public boolean withdraw(Player player, double amount) {
        if (amount <= 0) return true;
        
        var response = economy.withdrawPlayer(player, amount);
        if (response.transactionSuccess()) {
            plugin.debug("Withdrew " + amount + " from " + player.getName());
            return true;
        }
        
        plugin.getLogger().warning("Failed to withdraw " + amount + " from " + player.getName() + 
            ": " + response.errorMessage);
        return false;
    }
    
    /**
     * Deposits money to a player.
     */
    public boolean deposit(Player player, double amount) {
        if (amount <= 0) return true;
        
        var response = economy.depositPlayer(player, amount);
        if (response.transactionSuccess()) {
            plugin.debug("Deposited " + amount + " to " + player.getName());
            return true;
        }
        
        plugin.getLogger().warning("Failed to deposit " + amount + " to " + player.getName() + 
            ": " + response.errorMessage);
        return false;
    }
    
    /**
     * Formats a money amount.
     */
    public String formatMoney(double amount) {
        String symbol = plugin.getConfig().getString("economy.currency-symbol", "$");
        return symbol + String.format("%,.2f", amount);
    }
    
    /**
     * Gets the economy instance.
     */
    public Economy getEconomy() {
        return economy;
    }
}
