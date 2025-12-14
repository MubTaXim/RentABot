package com.ximpify.rentabot.util;

import com.ximpify.rentabot.RentABot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks for plugin updates from GitHub releases.
 */
public class UpdateChecker {
    
    private final RentABot plugin;
    private final String githubOwner;
    private final String githubRepo;
    private final String currentVersion;
    
    private String latestVersion = null;
    private String downloadUrl = null;
    private boolean updateAvailable = false;
    
    public UpdateChecker(RentABot plugin, String githubOwner, String githubRepo) {
        this.plugin = plugin;
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;
        this.currentVersion = plugin.getPluginMeta().getVersion();
    }
    
    /**
     * Check for updates asynchronously.
     */
    public void checkForUpdates() {
        if (!plugin.getConfig().getBoolean("updates.check-for-updates", true)) {
            return;
        }
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String apiUrl = String.format("https://api.github.com/repos/%s/%s/releases/latest", 
                        githubOwner, githubRepo);
                
                HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
                connection.setRequestProperty("User-Agent", "RentABot-UpdateChecker");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                
                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    plugin.debug("Update check failed: HTTP " + responseCode);
                    return;
                }
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                // Parse JSON manually (avoiding external dependencies)
                String json = response.toString();
                latestVersion = extractJsonValue(json, "tag_name");
                downloadUrl = extractJsonValue(json, "html_url");
                
                if (latestVersion == null) {
                    plugin.debug("Could not parse latest version from GitHub");
                    return;
                }
                
                // Remove 'v' prefix if present
                String cleanLatest = latestVersion.startsWith("v") ? latestVersion.substring(1) : latestVersion;
                String cleanCurrent = currentVersion.startsWith("v") ? currentVersion.substring(1) : currentVersion;
                
                if (isNewerVersion(cleanLatest, cleanCurrent)) {
                    updateAvailable = true;
                    
                    // Log to console
                    plugin.getLogger().warning("═══════════════════════════════════════════════════════");
                    plugin.getLogger().warning("  A new version of RentABot is available!");
                    plugin.getLogger().warning("  Current: v" + cleanCurrent + " → Latest: v" + cleanLatest);
                    plugin.getLogger().warning("  Download: " + downloadUrl);
                    plugin.getLogger().warning("═══════════════════════════════════════════════════════");
                    
                    // Notify online admins
                    if (plugin.getConfig().getBoolean("updates.notify-admins", true)) {
                        notifyAdmins();
                    }
                } else {
                    plugin.debug("Plugin is up to date (v" + cleanCurrent + ")");
                }
                
            } catch (Exception e) {
                plugin.debug("Update check failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Notify online players with admin permission about the update.
     */
    public void notifyAdmins() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("rentabot.admin.notify")) {
                    notifyPlayer(player);
                }
            }
        });
    }
    
    /**
     * Notify a specific player about the update (called on join).
     */
    public void notifyPlayer(Player player) {
        if (!updateAvailable || latestVersion == null) return;
        if (!plugin.getConfig().getBoolean("updates.notify-on-join", true)) return;
        
        String cleanLatest = latestVersion.startsWith("v") ? latestVersion.substring(1) : latestVersion;
        
        player.sendMessage("");
        player.sendMessage("§6§l[RentABot] §eA new version is available!");
        player.sendMessage("§7Current: §cv" + currentVersion + " §7→ Latest: §av" + cleanLatest);
        player.sendMessage("§7Download: §b" + downloadUrl);
        player.sendMessage("");
    }
    
    /**
     * Compare version strings (e.g., "1.1.0" vs "1.1.1").
     */
    private boolean isNewerVersion(String latest, String current) {
        try {
            String[] latestParts = latest.split("\\.");
            String[] currentParts = current.split("\\.");
            
            int maxLength = Math.max(latestParts.length, currentParts.length);
            
            for (int i = 0; i < maxLength; i++) {
                int latestNum = i < latestParts.length ? parseVersionPart(latestParts[i]) : 0;
                int currentNum = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
                
                if (latestNum > currentNum) return true;
                if (latestNum < currentNum) return false;
            }
            
            return false; // Versions are equal
        } catch (Exception e) {
            plugin.debug("Version comparison failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Parse a version part, handling suffixes like "1-SNAPSHOT".
     */
    private int parseVersionPart(String part) {
        // Remove any suffix like "-SNAPSHOT", "-beta", etc.
        String numOnly = part.split("-")[0];
        return Integer.parseInt(numOnly);
    }
    
    /**
     * Extract a value from JSON string (simple parser for GitHub API).
     */
    private String extractJsonValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    // Getters
    public boolean isUpdateAvailable() {
        return updateAvailable;
    }
    
    public String getLatestVersion() {
        return latestVersion;
    }
    
    public String getDownloadUrl() {
        return downloadUrl;
    }
    
    public String getCurrentVersion() {
        return currentVersion;
    }
}
