package com.ximpify.rentabot.util;

import com.ximpify.rentabot.RentABot;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for handling messages with color codes and placeholders.
 */
public class MessageUtil {
    
    private final RentABot plugin;
    private FileConfiguration messagesConfig;
    private String prefix;
    
    // Hex color pattern: &#RRGGBB
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    
    public MessageUtil(RentABot plugin) {
        this.plugin = plugin;
        reload();
    }
    
    /**
     * Reloads the messages configuration.
     */
    public void reload() {
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        prefix = translateColors(messagesConfig.getString("prefix", "&7[RentABot] "));
    }
    
    /**
     * Sends a message from the config to a sender.
     */
    public void send(CommandSender sender, String path, String... replacements) {
        String message = getMessage(path, replacements);
        if (message != null && !message.isEmpty()) {
            sender.sendMessage(Component.text(prefix + message));
        }
    }
    
    /**
     * Sends a raw message (with color codes but no prefix).
     */
    public void sendRaw(CommandSender sender, String message, String... replacements) {
        message = applyReplacements(message, replacements);
        sender.sendMessage(Component.text(translateColors(message)));
    }
    
    /**
     * Gets a message from config with replacements applied.
     */
    public String getMessage(String path, String... replacements) {
        String message = messagesConfig.getString(path);
        if (message == null) {
            plugin.debug("Missing message: " + path);
            return "";
        }
        
        message = applyReplacements(message, replacements);
        return translateColors(message);
    }
    
    /**
     * Gets a raw message without color translation.
     */
    public String getRaw(String path) {
        String message = messagesConfig.getString(path);
        if (message == null) return "";
        return translateColors(message);
    }
    
    /**
     * Gets a list of strings from the config.
     */
    public List<String> getStringList(String path) {
        return messagesConfig.getStringList(path);
    }
    
    /**
     * Applies placeholder replacements to a message.
     */
    private String applyReplacements(String message, String... replacements) {
        if (replacements.length % 2 != 0) {
            plugin.debug("Invalid replacements array (must be key-value pairs)");
            return message;
        }
        
        for (int i = 0; i < replacements.length; i += 2) {
            String key = "%" + replacements[i] + "%";
            String value = replacements[i + 1];
            message = message.replace(key, value);
        }
        
        return message;
    }
    
    /**
     * Translates color codes including hex colors.
     */
    public String translateColors(String message) {
        if (message == null) return "";
        
        // Translate hex colors (&#RRGGBB -> §x§R§R§G§G§B§B)
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuilder sb = new StringBuilder();
        
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append("§").append(c);
            }
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        message = sb.toString();
        
        // Translate standard color codes (& -> §)
        message = message.replace("&", "§");
        
        return message;
    }
    
    /**
     * Strips color codes from a message.
     */
    public String stripColors(String message) {
        if (message == null) return "";
        return message.replaceAll("(?i)§[0-9A-FK-OR]|&#[A-Fa-f0-9]{6}", "");
    }
    
    /**
     * Plays a sound effect to a player.
     * @param player The player to play the sound to
     * @param soundKey The config key under notifications.sounds (e.g., "on-create")
     */
    public void playSound(Player player, String soundKey) {
        if (player == null) return;
        if (!plugin.getConfig().getBoolean("notifications.sounds.enabled", true)) return;
        
        String soundName = plugin.getConfig().getString("notifications.sounds." + soundKey);
        if (soundName == null || soundName.isEmpty()) return;
        
        try {
            Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(soundName.toLowerCase()));
            if (sound != null) {
                float volume = (float) plugin.getConfig().getDouble("notifications.sounds.volume", 1.0);
                float pitch = (float) plugin.getConfig().getDouble("notifications.sounds.pitch", 1.0);
                player.playSound(player.getLocation(), sound, volume, pitch);
            }
        } catch (Exception e) {
            plugin.debug("Failed to play sound '" + soundKey + "': " + e.getMessage());
        }
    }
    
    /**
     * Plays a sound effect to a player with custom pitch.
     */
    public void playSound(Player player, String soundKey, float pitch) {
        if (player == null) return;
        if (!plugin.getConfig().getBoolean("notifications.sounds.enabled", true)) return;
        
        String soundName = plugin.getConfig().getString("notifications.sounds." + soundKey);
        if (soundName == null || soundName.isEmpty()) return;
        
        try {
            Sound sound = Registry.SOUNDS.get(NamespacedKey.minecraft(soundName.toLowerCase()));
            if (sound != null) {
                float volume = (float) plugin.getConfig().getDouble("notifications.sounds.volume", 1.0);
                player.playSound(player.getLocation(), sound, volume, pitch);
            }
        } catch (Exception e) {
            plugin.debug("Failed to play sound '" + soundKey + "': " + e.getMessage());
        }
    }
}
