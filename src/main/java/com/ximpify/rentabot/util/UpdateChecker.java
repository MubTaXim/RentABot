package com.ximpify.rentabot.util;

import com.ximpify.rentabot.RentABot;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks for plugin updates from GitHub releases and handles auto-download.
 */
public class UpdateChecker {
    
    private final RentABot plugin;
    private final String githubOwner;
    private final String githubRepo;
    private final String currentVersion;
    
    private String latestVersion = null;
    private String downloadUrl = null;
    private String jarDownloadUrl = null;
    private boolean updateAvailable = false;
    private boolean downloadInProgress = false;
    private boolean downloadCompleted = false;
    private String downloadedFilePath = null;
    
    public UpdateChecker(RentABot plugin, String githubOwner, String githubRepo) {
        this.plugin = plugin;
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;
        this.currentVersion = plugin.getPluginMeta().getVersion();
    }
    
    /**
     * Result of an update operation.
     */
    public static class UpdateResult {
        private final boolean success;
        private final String message;
        private final String filePath;
        
        public UpdateResult(boolean success, String message, String filePath) {
            this.success = success;
            this.message = message;
            this.filePath = filePath;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getFilePath() { return filePath; }
    }
    
    /**
     * Check for updates asynchronously.
     */
    public void checkForUpdates() {
        checkForUpdates(null);
    }
    
    /**
     * Check for updates asynchronously with optional notification target.
     */
    public void checkForUpdates(CommandSender notifyTarget) {
        if (!plugin.getConfig().getBoolean("updates.check-for-updates", true) && notifyTarget == null) {
            return;
        }
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String apiUrl = String.format("https://api.github.com/repos/%s/%s/releases/latest", 
                        githubOwner, githubRepo);
                
                HttpURLConnection connection = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
                connection.setRequestProperty("User-Agent", "RentABot-UpdateChecker");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                
                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    plugin.debug("Update check failed: HTTP " + responseCode);
                    if (notifyTarget != null) {
                        notifyOnMainThread(notifyTarget, "§c[RentABot] Update check failed: HTTP " + responseCode);
                    }
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
                
                // Extract JAR download URL from assets
                jarDownloadUrl = extractJarDownloadUrl(json);
                
                if (latestVersion == null) {
                    plugin.debug("Could not parse latest version from GitHub");
                    if (notifyTarget != null) {
                        notifyOnMainThread(notifyTarget, "§c[RentABot] Could not parse version from GitHub");
                    }
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
                    plugin.getLogger().warning("  Use: /rabadmin update download");
                    plugin.getLogger().warning("═══════════════════════════════════════════════════════");
                    
                    // Notify target if specified
                    if (notifyTarget != null) {
                        notifyOnMainThread(notifyTarget, "");
                        notifyOnMainThread(notifyTarget, "§6§l[RentABot] §aUpdate available!");
                        notifyOnMainThread(notifyTarget, "§7Current: §cv" + cleanCurrent + " §7→ Latest: §av" + cleanLatest);
                        notifyOnMainThread(notifyTarget, "§7Use §e/rabadmin update download §7to download");
                        notifyOnMainThread(notifyTarget, "");
                    }
                    // Notify online admins
                    else if (plugin.getConfig().getBoolean("updates.notify-admins", true)) {
                        notifyAdmins();
                    }
                } else {
                    plugin.debug("Plugin is up to date (v" + cleanCurrent + ")");
                    if (notifyTarget != null) {
                        notifyOnMainThread(notifyTarget, "§a[RentABot] Plugin is up to date! (v" + cleanCurrent + ")");
                    }
                }
                
            } catch (Exception e) {
                plugin.debug("Update check failed: " + e.getMessage());
                if (notifyTarget != null) {
                    notifyOnMainThread(notifyTarget, "§c[RentABot] Update check failed: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * Downloads the latest version asynchronously.
     * @return CompletableFuture with the result
     */
    public CompletableFuture<UpdateResult> downloadUpdate(CommandSender notifyTarget) {
        CompletableFuture<UpdateResult> future = new CompletableFuture<>();
        
        if (downloadInProgress) {
            future.complete(new UpdateResult(false, "Download already in progress", null));
            return future;
        }
        
        if (downloadCompleted && downloadedFilePath != null) {
            future.complete(new UpdateResult(true, 
                "Update already downloaded to: " + downloadedFilePath + " - Restart server to apply", 
                downloadedFilePath));
            return future;
        }
        
        // If we don't have version info yet, check first
        if (latestVersion == null || jarDownloadUrl == null) {
            if (notifyTarget != null) {
                notifyOnMainThread(notifyTarget, "§e[RentABot] Checking for updates first...");
            }
            
            // Check for updates first, then download
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    // Fetch release info synchronously
                    fetchReleaseInfo();
                    
                    if (jarDownloadUrl == null) {
                        future.complete(new UpdateResult(false, "Could not find JAR download URL", null));
                        return;
                    }
                    
                    if (!updateAvailable) {
                        future.complete(new UpdateResult(false, "Plugin is already up to date", null));
                        return;
                    }
                    
                    // Now download
                    performDownload(notifyTarget, future);
                    
                } catch (Exception e) {
                    future.complete(new UpdateResult(false, "Error: " + e.getMessage(), null));
                }
            });
        } else if (!updateAvailable) {
            future.complete(new UpdateResult(false, "Plugin is already up to date", null));
        } else {
            // We have the info, proceed with download
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                performDownload(notifyTarget, future);
            });
        }
        
        return future;
    }
    
    /**
     * Fetches release info synchronously (called from async thread).
     */
    private void fetchReleaseInfo() throws Exception {
        String apiUrl = String.format("https://api.github.com/repos/%s/%s/releases/latest", 
                githubOwner, githubRepo);
        
        HttpURLConnection connection = (HttpURLConnection) URI.create(apiUrl).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
        connection.setRequestProperty("User-Agent", "RentABot-UpdateChecker");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("GitHub API returned HTTP " + responseCode);
        }
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        
        String json = response.toString();
        latestVersion = extractJsonValue(json, "tag_name");
        downloadUrl = extractJsonValue(json, "html_url");
        jarDownloadUrl = extractJarDownloadUrl(json);
        
        if (latestVersion != null) {
            String cleanLatest = latestVersion.startsWith("v") ? latestVersion.substring(1) : latestVersion;
            String cleanCurrent = currentVersion.startsWith("v") ? currentVersion.substring(1) : currentVersion;
            updateAvailable = isNewerVersion(cleanLatest, cleanCurrent);
        }
    }
    
    /**
     * Performs the actual download (called from async thread).
     */
    private void performDownload(CommandSender notifyTarget, CompletableFuture<UpdateResult> future) {
        downloadInProgress = true;
        
        try {
            if (notifyTarget != null) {
                notifyOnMainThread(notifyTarget, "§e[RentABot] Downloading update...");
            }
            
            plugin.getLogger().info("Downloading update from: " + jarDownloadUrl);
            
            // Create update directory
            File updateDir = new File(plugin.getDataFolder().getParentFile(), "update");
            if (!updateDir.exists()) {
                updateDir.mkdirs();
            }
            
            // Download file
            String fileName = "RentABot-" + latestVersion + ".jar";
            File targetFile = new File(updateDir, fileName);
            
            HttpURLConnection connection = (HttpURLConnection) URI.create(jarDownloadUrl).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "RentABot-UpdateChecker");
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            
            // Handle redirects manually for GitHub releases
            int responseCode = connection.getResponseCode();
            if (responseCode == 302 || responseCode == 301) {
                String redirectUrl = connection.getHeaderField("Location");
                connection = (HttpURLConnection) URI.create(redirectUrl).toURL().openConnection();
                connection.setRequestProperty("User-Agent", "RentABot-UpdateChecker");
                responseCode = connection.getResponseCode();
            }
            
            if (responseCode != 200) {
                throw new IOException("Download failed: HTTP " + responseCode);
            }
            
            long fileSize = connection.getContentLengthLong();
            plugin.debug("Download size: " + (fileSize > 0 ? (fileSize / 1024) + " KB" : "unknown"));
            
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            
            // Verify file was downloaded
            if (!targetFile.exists() || targetFile.length() == 0) {
                throw new IOException("Downloaded file is empty or missing");
            }
            
            downloadCompleted = true;
            downloadedFilePath = targetFile.getAbsolutePath();
            
            String successMsg = "Update downloaded successfully! File: " + fileName;
            plugin.getLogger().info("═══════════════════════════════════════════════════════");
            plugin.getLogger().info("  " + successMsg);
            plugin.getLogger().info("  Location: " + targetFile.getAbsolutePath());
            plugin.getLogger().info("  Restart the server to apply the update!");
            plugin.getLogger().info("═══════════════════════════════════════════════════════");
            
            if (notifyTarget != null) {
                notifyOnMainThread(notifyTarget, "");
                notifyOnMainThread(notifyTarget, "§a§l[RentABot] Update downloaded successfully!");
                notifyOnMainThread(notifyTarget, "§7File: §f" + fileName);
                notifyOnMainThread(notifyTarget, "§7Location: §f" + updateDir.getPath());
                notifyOnMainThread(notifyTarget, "§e§lRestart the server to apply the update!");
                notifyOnMainThread(notifyTarget, "");
            }
            
            future.complete(new UpdateResult(true, successMsg, targetFile.getAbsolutePath()));
            
        } catch (Exception e) {
            String errorMsg = "Download failed: " + e.getMessage();
            plugin.getLogger().severe(errorMsg);
            e.printStackTrace();
            
            if (notifyTarget != null) {
                notifyOnMainThread(notifyTarget, "§c[RentABot] " + errorMsg);
            }
            
            future.complete(new UpdateResult(false, errorMsg, null));
        } finally {
            downloadInProgress = false;
        }
    }
    
    /**
     * Extracts the JAR download URL from GitHub API response.
     */
    private String extractJarDownloadUrl(String json) {
        // Look for browser_download_url ending in .jar
        Pattern pattern = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.jar)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // Fallback: construct URL from tag
        if (latestVersion != null) {
            return String.format("https://github.com/%s/%s/releases/download/%s/RentABot-%s.jar",
                    githubOwner, githubRepo, latestVersion, latestVersion);
        }
        
        return null;
    }
    
    /**
     * Helper to send messages on main thread.
     */
    private void notifyOnMainThread(CommandSender target, String message) {
        if (target == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> target.sendMessage(message));
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
        player.sendMessage("§7Use: §e/rabadmin update download");
        player.sendMessage("");
    }
    
    /**
     * Resets update state (for re-checking).
     */
    public void reset() {
        latestVersion = null;
        downloadUrl = null;
        jarDownloadUrl = null;
        updateAvailable = false;
        downloadInProgress = false;
        downloadCompleted = false;
        downloadedFilePath = null;
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
    
    public boolean isDownloadInProgress() {
        return downloadInProgress;
    }
    
    public boolean isDownloadCompleted() {
        return downloadCompleted;
    }
    
    public String getDownloadedFilePath() {
        return downloadedFilePath;
    }
}
