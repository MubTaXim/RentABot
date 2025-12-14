package com.ximpify.rentabot.bot;

/**
 * Represents the lifecycle state of a bot.
 */
public enum BotStatus {
    
    /**
     * Bot is connected and running, time is counting down.
     */
    ACTIVE("Active", "§a"),
    
    /**
     * Bot is paused by owner, time is frozen.
     */
    STOPPED("Stopped", "§e"),
    
    /**
     * Bot's rental time expired, disconnected but kept in database.
     */
    EXPIRED("Expired", "§c");
    
    private final String displayName;
    private final String color;
    
    BotStatus(String displayName, String color) {
        this.displayName = displayName;
        this.color = color;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public String getColor() {
        return color;
    }
    
    public String getColoredName() {
        return color + displayName;
    }
    
    /**
     * Check if the bot is in a "reserved" state (not actively running).
     */
    public boolean isReserved() {
        return this == STOPPED || this == EXPIRED;
    }
    
    /**
     * Check if the bot can be resumed.
     */
    public boolean canResume() {
        return this == STOPPED || this == EXPIRED;
    }
    
    /**
     * Parse status from string (for database).
     */
    public static BotStatus fromString(String status) {
        if (status == null) return ACTIVE;
        try {
            return valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ACTIVE;
        }
    }
}
