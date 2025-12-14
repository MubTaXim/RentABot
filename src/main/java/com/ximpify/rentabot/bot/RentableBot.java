package com.ximpify.rentabot.bot;

import com.ximpify.rentabot.RentABot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.*;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a rentable bot that connects to the server as a real player.
 */
public class RentableBot {
    
    private final RentABot plugin;
    private String displayName; // Not final - can change on rename
    private String internalName;
    private final UUID ownerUUID;
    private final String ownerName;
    
    private ClientSession session;
    private final AtomicBoolean connected;
    private final AtomicInteger reconnectAttempts;
    private final AtomicBoolean manuallyStopped;
    
    private Instant createdAt;
    private Instant expiresAt;
    
    // Position tracking
    private double x, y, z;
    private float yaw, pitch;
    private String world;
    private boolean positionInitialized;
    
    // Saved spawn point (last TPA location)
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;
    private String savedWorld;
    private boolean hasSpawnPoint;
    
    // Entity tracking
    private int entityId;
    
    // Health tracking
    private float health;
    private int food;
    
    // Anti-AFK
    private long lastMovement;
    
    // Connection tracking
    private Instant connectedAt;
    
    public RentableBot(RentABot plugin, String displayName, String internalName, 
                       UUID ownerUUID, String ownerName, int hours) {
        this.plugin = plugin;
        this.displayName = displayName;
        this.internalName = internalName;
        this.ownerUUID = ownerUUID;
        this.ownerName = ownerName;
        this.connected = new AtomicBoolean(false);
        this.reconnectAttempts = new AtomicInteger(0);
        this.manuallyStopped = new AtomicBoolean(false);
        this.createdAt = Instant.now();
        this.expiresAt = createdAt.plusSeconds(hours * 3600L);
        this.lastMovement = System.currentTimeMillis();
        this.positionInitialized = false;
        this.health = 20.0f;
        this.food = 20;
        this.hasSpawnPoint = false;
    }
    
    /**
     * Connects the bot to the server.
     */
    public boolean connect() {
        try {
            // Auto-detect server host and port, with config override option
            String host = plugin.getConfig().getString("server.host", "localhost");
            int port = plugin.getConfig().getInt("server.port", -1);
            
            // If port is not manually set, auto-detect from server
            if (port == -1) {
                port = plugin.getServer().getPort();
                plugin.debug("Auto-detected server port: " + port);
            }
            
            // Create offline mode protocol (bot account)
            MinecraftProtocol protocol = new MinecraftProtocol(displayName);
            
            // Create session using factory
            session = ClientNetworkSessionFactory.factory()
                .setAddress(host, port)
                .setProtocol(protocol)
                .create();
            
            // Add session listener
            session.addListener(new BotSessionListener());
            
            // Connect
            session.connect();
            
            plugin.debug("Bot '" + internalName + "' connecting to " + host + ":" + port);
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to connect bot '" + internalName + "': " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Disconnects the bot from the server.
     */
    public void disconnect(String reason) {
        // Mark as manually stopped to prevent auto-reconnect
        manuallyStopped.set(true);
        if (session != null) {
            if (session.isConnected()) {
                session.disconnect(Component.text(reason));
            }
            // Clear the session reference to prevent reuse
            session = null;
        }
        connected.set(false);
    }
    
    /**
     * Resets the bot state for reconnection after rename.
     */
    public void resetForReconnect() {
        manuallyStopped.set(false);
        reconnectAttempts.set(0);
    }
    
    /**
     * Attempts to reconnect the bot.
     */
    public void reconnect() {
        if (!shouldReconnect()) {
            return;
        }
        
        int delay = plugin.getConfig().getInt("bots.behavior.auto-reconnect.delay", 10);
        
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            reconnectAttempts.incrementAndGet();
            plugin.debug("Reconnect attempt #" + reconnectAttempts.get() + " for bot: " + internalName);
            connect();
        }, delay * 20L);
    }
    
    /**
     * Checks if the bot should attempt to reconnect.
     */
    public boolean shouldReconnect() {
        // Don't reconnect if manually stopped by user/admin
        if (manuallyStopped.get()) {
            plugin.debug("Bot '" + internalName + "' not reconnecting - was manually stopped");
            return false;
        }
        
        if (!plugin.getConfig().getBoolean("bots.behavior.auto-reconnect.enabled", true)) {
            return false;
        }
        
        int maxAttempts = plugin.getConfig().getInt("bots.behavior.auto-reconnect.max-attempts", 5);
        if (maxAttempts > 0 && reconnectAttempts.get() >= maxAttempts) {
            return false;
        }
        
        // Don't reconnect if rental expired
        if (Instant.now().isAfter(expiresAt)) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Sends a chat command.
     */
    public void sendCommand(String command) {
        if (session != null && session.isConnected()) {
            session.send(new ServerboundChatCommandPacket(command));
        }
    }
    
    /**
     * Performs anti-AFK movement.
     */
    public void performAntiAFK() {
        if (!connected.get() || session == null) return;
        
        // Don't perform anti-AFK if we don't have a valid position yet
        if (!positionInitialized) {
            plugin.debug("Bot '" + internalName + "' skipping anti-AFK - position not yet initialized");
            return;
        }
        
        String typeConfig = plugin.getConfig().getString("bots.behavior.anti-afk.type", "look");
        
        // Parse comma-separated types and pick one randomly
        String[] types = typeConfig.split(",");
        String type = types[(int) (Math.random() * types.length)].trim().toLowerCase();
        
        plugin.debug("Bot '" + internalName + "' performing anti-AFK (" + type + ") at position: " + 
            String.format("%.2f, %.2f, %.2f", x, y, z));
        
        switch (type) {
            case "look" -> {
                // Random head movement
                float newYaw = yaw + (float) (Math.random() * 20 - 10);
                float newPitch = pitch + (float) (Math.random() * 10 - 5);
                // Clamp pitch to valid range
                newPitch = Math.max(-90, Math.min(90, newPitch));
                // Constructor: (onGround, horizontalCollision, x, y, z, yaw, pitch)
                session.send(new ServerboundMovePlayerPosRotPacket(true, false, x, y, z, newYaw, newPitch));
                this.yaw = newYaw;
                this.pitch = newPitch;
            }
            case "sneak" -> {
                // Toggle sneak
                sendCommand("togglesneak");
            }
            case "jump" -> {
                // Small jump - not on ground during jump
                session.send(new ServerboundMovePlayerPosRotPacket(false, false, x, y + 0.1, z, yaw, pitch));
            }
            case "move" -> {
                // Small movement
                double newX = x + (Math.random() * 0.2 - 0.1);
                double newZ = z + (Math.random() * 0.2 - 0.1);
                session.send(new ServerboundMovePlayerPosRotPacket(true, false, newX, y, newZ, yaw, pitch));
            }
        }
        
        lastMovement = System.currentTimeMillis();
    }
    
    /**
     * Handles AuthMe login/register when bot joins.
     */
    private void handleAuthMeLogin() {
        if (!plugin.getConfig().getBoolean("hooks.authme.enabled", true)) {
            return;
        }
        
        String mode = plugin.getConfig().getString("hooks.authme.mode", "auto-register");
        String password = plugin.getConfig().getString("hooks.authme.default-password", "RentABot2024!");
        int delay = plugin.getConfig().getInt("hooks.authme.login-delay", 40);
        
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            if (!connected.get() || session == null) return;
            
            switch (mode.toLowerCase()) {
                case "auto-register" -> {
                    // First try to login, if that fails AuthMe will prompt to register
                    sendCommand("login " + password);
                    plugin.debug("Bot '" + internalName + "' attempting AuthMe login");
                    
                    // Schedule a register attempt in case not registered
                    plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                        if (connected.get()) {
                            sendCommand("register " + password + " " + password);
                            plugin.debug("Bot '" + internalName + "' attempting AuthMe register");
                        }
                    }, 40L); // 2 seconds later
                }
                case "pre-registered" -> {
                    sendCommand("login " + password);
                    plugin.debug("Bot '" + internalName + "' sent AuthMe login (pre-registered mode)");
                }
                // "disabled" - do nothing
            }
        }, delay);
    }
    
    /**
     * Accepts a TPA request (responds with /tpaccept).
     */
    public void acceptTPA() {
        sendCommand("tpaccept");
    }
    
    /**
     * Denies a TPA request (responds with /tpdeny).
     */
    public void denyTPA() {
        sendCommand("tpdeny");
    }
    
    /**
     * Extends the rental duration.
     */
    public void extendRental(int hours) {
        this.expiresAt = expiresAt.plusSeconds(hours * 3600L);
    }
    
    // Session listener for handling packets
    private class BotSessionListener implements SessionListener {
        
        @Override
        public void packetReceived(Session session, Packet packet) {
            // Handle login packet (initial spawn info + entity ID)
            if (packet instanceof ClientboundLoginPacket loginPacket) {
                handleLogin(loginPacket);
            }
            // Handle position updates from server
            else if (packet instanceof ClientboundPlayerPositionPacket posPacket) {
                handlePositionUpdate(posPacket);
            }
            // Handle respawn (dimension change, death respawn)
            else if (packet instanceof ClientboundRespawnPacket respawnPacket) {
                handleRespawn(respawnPacket);
            }
            // Handle health updates (detect death)
            else if (packet instanceof ClientboundSetHealthPacket healthPacket) {
                handleHealthUpdate(healthPacket);
            }
            // Handle system chat packets for TPA detection
            else if (packet instanceof ClientboundSystemChatPacket chatPacket) {
                handleChatMessage(chatPacket.getContent());
            }
            // Handle player chat packets for TPA detection
            else if (packet instanceof ClientboundPlayerChatPacket playerChatPacket) {
                Component content = playerChatPacket.getUnsignedContent() != null 
                    ? playerChatPacket.getUnsignedContent() 
                    : Component.text(playerChatPacket.getContent());
                handleChatMessage(content);
            }
        }
        
        @Override
        public void packetSending(PacketSendingEvent event) {}
        
        @Override
        public void packetSent(Session session, Packet packet) {}
        
        @Override
        public void packetError(PacketErrorEvent event) {
            plugin.debug("Packet error for bot " + internalName + ": " + event.getCause().getMessage());
        }
        
        @Override
        public void connected(ConnectedEvent event) {
            connected.set(true);
            reconnectAttempts.set(0);
            connectedAt = Instant.now();
            plugin.getLogger().info("Bot '" + internalName + "' connected successfully!");
            
            // Handle AuthMe login
            handleAuthMeLogin();
            
            // Notify owner if online
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                var owner = plugin.getServer().getPlayer(ownerUUID);
                if (owner != null) {
                    plugin.getMessageUtil().send(owner, "notifications.reconnected", 
                        "bot", internalName);
                }
            });
        }
        
        @Override
        public void disconnecting(DisconnectingEvent event) {
            String reason = event.getReason() != null 
                ? PlainTextComponentSerializer.plainText().serialize(event.getReason()) 
                : "Unknown";
            plugin.debug("Bot '" + internalName + "' disconnecting: " + reason);
        }
        
        @Override
        public void disconnected(DisconnectedEvent event) {
            connected.set(false);
            positionInitialized = false;
            String reason = event.getReason() != null 
                ? PlainTextComponentSerializer.plainText().serialize(event.getReason()) 
                : "Unknown";
            plugin.getLogger().info("Bot '" + internalName + "' disconnected: " + reason);
            
            // Check for permanent failures that should not trigger reconnect
            boolean permanentFailure = reason.contains("should join using username") 
                || reason.contains("name is already taken")
                || reason.contains("Invalid username")
                || reason.contains("Kicked for spamming");
            
            if (permanentFailure) {
                manuallyStopped.set(true); // Prevent reconnect attempts
                plugin.getLogger().warning("Bot '" + internalName + "' has a permanent issue and will not reconnect: " + reason);
            }
            
            // Only notify owner and attempt reconnect if NOT manually stopped
            // (Manual stop = user stopped it or rental expired)
            if (!manuallyStopped.get()) {
                // Notify owner if online about unexpected disconnect
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    var owner = plugin.getServer().getPlayer(ownerUUID);
                    if (owner != null) {
                        plugin.getMessageUtil().send(owner, "notifications.disconnected", 
                            "bot", internalName, "reason", reason);
                    }
                });
                
                // Attempt reconnect if enabled
                if (shouldReconnect()) {
                    reconnect();
                }
            }
        }
    }
    
    /**
     * Handles login packet - stores entity ID and initial spawn info.
     */
    private void handleLogin(ClientboundLoginPacket packet) {
        this.entityId = packet.getEntityId();
        plugin.debug("Bot '" + internalName + "' logged in with entity ID: " + entityId);
    }
    
    /**
     * Handles position updates from server - CRITICAL for anti-AFK to work.
     */
    private void handlePositionUpdate(ClientboundPlayerPositionPacket packet) {
        // Update position
        this.x = packet.getPosition().getX();
        this.y = packet.getPosition().getY();
        this.z = packet.getPosition().getZ();
        this.yaw = packet.getYRot();
        this.pitch = packet.getXRot();
        this.positionInitialized = true;
        
        plugin.debug("Bot '" + internalName + "' position updated: " + 
            String.format("%.2f, %.2f, %.2f (yaw: %.1f, pitch: %.1f)", x, y, z, yaw, pitch));
        
        // CRITICAL: Confirm teleport to server, otherwise server thinks we're desynced
        session.send(new ServerboundAcceptTeleportationPacket(packet.getId()));
        plugin.debug("Bot '" + internalName + "' confirmed teleport ID: " + packet.getId());
    }
    
    /**
     * Handles respawn packet (dimension change or death respawn).
     */
    private void handleRespawn(ClientboundRespawnPacket packet) {
        plugin.debug("Bot '" + internalName + "' received respawn packet");
        // Position will be reset - wait for new position packet
        positionInitialized = false;
    }
    
    /**
     * Handles health updates - detects death and auto-respawns.
     */
    private void handleHealthUpdate(ClientboundSetHealthPacket packet) {
        float oldHealth = this.health;
        this.health = packet.getHealth();
        this.food = packet.getFood();
        
        plugin.debug("Bot '" + internalName + "' health: " + health + ", food: " + food);
        
        // Check if bot died (health <= 0)
        if (health <= 0 && oldHealth > 0) {
            plugin.getLogger().info("Bot '" + internalName + "' died! Auto-respawning...");
            
            // Notify owner
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                var owner = plugin.getServer().getPlayer(ownerUUID);
                if (owner != null) {
                    plugin.getMessageUtil().send(owner, "notifications.bot-died", 
                        "bot", internalName);
                }
            });
            
            // Auto-respawn after short delay (like a player clicking respawn)
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                if (connected.get() && session != null) {
                    session.send(new ServerboundClientCommandPacket(ClientCommand.RESPAWN));
                    plugin.debug("Bot '" + internalName + "' sent respawn packet");
                    
                    // After respawn, attempt to return to saved spawn point
                    if (hasSpawnPoint && plugin.getConfig().getBoolean("bots.behavior.return-after-death", true)) {
                        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                            returnToSpawnPoint();
                        }, 60L); // 3 seconds after respawn
                    }
                }
            }, 20L); // 1 second delay
        }
    }
    
    /**
     * Handles incoming chat messages for TPA detection.
     */
    private void handleChatMessage(Component content) {
        // Get the raw message content using PlainTextComponentSerializer
        String message = PlainTextComponentSerializer.plainText().serialize(content).toLowerCase();
        
        plugin.debug("Bot '" + internalName + "' received chat: " + message);
        
        // Get configurable TPA patterns (or use defaults)
        List<String> tpaPatterns = plugin.getConfig().getStringList("bots.behavior.tpa-patterns");
        List<String> tpaHerePatterns = plugin.getConfig().getStringList("bots.behavior.tpahere-patterns");
        
        // Default patterns if not configured
        if (tpaPatterns == null || tpaPatterns.isEmpty()) {
            tpaPatterns = Arrays.asList(
                "teleport to you",
                "teleport request",
                "has requested to teleport",
                "wants to teleport to you",
                "sent you a teleport request"
            );
        }
        if (tpaHerePatterns == null || tpaHerePatterns.isEmpty()) {
            tpaHerePatterns = Arrays.asList(
                "teleport to them",
                "you teleport to",
                "requests that you teleport",
                "wants you to teleport"
            );
        }
        
        // Check for TPA requests using configurable patterns
        boolean isTpaRequest = false;
        for (String pattern : tpaPatterns) {
            if (message.contains(pattern.toLowerCase())) {
                isTpaRequest = true;
                break;
            }
        }
        
        // Check for TPAHere requests using configurable patterns
        boolean isTpaHereRequest = false;
        if (!isTpaRequest) { // Only check if not already a TPA
            for (String pattern : tpaHerePatterns) {
                if (message.contains(pattern.toLowerCase())) {
                    isTpaHereRequest = true;
                    break;
                }
            }
        }
        
        if (isTpaRequest || isTpaHereRequest) {
            // Extract player name from message (usually first word)
            String[] words = message.split(" ");
            if (words.length > 0) {
                String requester = words[0].replaceAll("[^a-zA-Z0-9_]", "");
                
                boolean acceptOwnerTPA = plugin.getConfig().getBoolean("bots.behavior.accept-owner-tpa", true);
                boolean acceptOwnerTPAHere = plugin.getConfig().getBoolean("bots.behavior.accept-owner-tpahere", true);
                boolean denyOthersTPA = plugin.getConfig().getBoolean("bots.behavior.deny-others-tpa", true);
                
                // Check if requester is owner
                if (requester.equalsIgnoreCase(ownerName)) {
                    if (isTpaRequest && acceptOwnerTPA) {
                        plugin.debug("Bot '" + internalName + "' accepting TPA from owner: " + requester);
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            acceptTPA();
                            // Save spawn point after TPA (owner teleports to bot's location)
                            saveCurrentAsSpawnPoint();
                        }, 20L);
                    } else if (isTpaHereRequest && acceptOwnerTPAHere) {
                        plugin.debug("Bot '" + internalName + "' accepting TPAHere from owner: " + requester);
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            acceptTPA();
                            // Save spawn point after teleport completes
                            plugin.getServer().getScheduler().runTaskLater(plugin, this::saveCurrentAsSpawnPoint, 40L);
                        }, 20L);
                    }
                } else if (denyOthersTPA) {
                    plugin.debug("Bot '" + internalName + "' denying TPA/TPAHere from: " + requester);
                    plugin.getServer().getScheduler().runTaskLater(plugin, this::denyTPA, 20L);
                }
            }
        }
    }
    
    // Getters and setters
    public String getDisplayName() {
        return displayName;
    }
    
    public String getInternalName() {
        return internalName;
    }
    
    public void setInternalName(String name) {
        this.internalName = name;
    }
    
    public void setDisplayName(String name) {
        this.displayName = name;
    }
    
    public UUID getOwnerUUID() {
        return ownerUUID;
    }
    
    public String getOwnerName() {
        return ownerName;
    }
    
    public boolean isConnected() {
        return connected.get();
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getExpiresAt() {
        return expiresAt;
    }
    
    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
    
    public ClientSession getSession() {
        return session;
    }
    
    public void setPosition(double x, double y, double z, float yaw, float pitch, String world) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.world = world;
    }
    
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public String getWorld() { return world; }
    public boolean isPositionInitialized() { return positionInitialized; }
    public int getEntityId() { return entityId; }
    public float getHealth() { return health; }
    public int getFood() { return food; }
    public Instant getConnectedAt() { return connectedAt; }
    public boolean hasSpawnPoint() { return hasSpawnPoint; }
    
    // Spawn point getters
    public double getSavedX() { return savedX; }
    public double getSavedY() { return savedY; }
    public double getSavedZ() { return savedZ; }
    public float getSavedYaw() { return savedYaw; }
    public float getSavedPitch() { return savedPitch; }
    public String getSavedWorld() { return savedWorld; }
    
    /**
     * Saves the current position as the spawn point.
     */
    public void saveCurrentAsSpawnPoint() {
        if (positionInitialized) {
            this.savedX = x;
            this.savedY = y;
            this.savedZ = z;
            this.savedYaw = yaw;
            this.savedPitch = pitch;
            this.savedWorld = world;
            this.hasSpawnPoint = true;
            plugin.debug("Bot '" + internalName + "' saved spawn point: " + 
                String.format("%.2f, %.2f, %.2f in %s", savedX, savedY, savedZ, savedWorld));
            
            // Also persist to storage
            plugin.getStorageManager().saveRental(this);
        }
    }
    
    /**
     * Sets the spawn point manually (from storage).
     */
    public void setSpawnPoint(double x, double y, double z, float yaw, float pitch, String world) {
        this.savedX = x;
        this.savedY = y;
        this.savedZ = z;
        this.savedYaw = yaw;
        this.savedPitch = pitch;
        this.savedWorld = world;
        this.hasSpawnPoint = world != null;
    }
    
    /**
     * Returns the bot to its saved spawn point using /tpa to owner.
     */
    private void returnToSpawnPoint() {
        if (!hasSpawnPoint || !connected.get()) return;
        
        plugin.getLogger().info("Bot '" + internalName + "' attempting to return to spawn point...");
        
        // Use server command to teleport (runs on main thread)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String command = String.format("tp %s %.2f %.2f %.2f",
                displayName, savedX, savedY, savedZ);
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
            plugin.debug("Bot '" + internalName + "' teleported back to spawn point");
        });
    }
    
    /**
     * Gets uptime as a formatted string.
     */
    public String getUptime() {
        if (connectedAt == null) return "Not connected";
        long seconds = java.time.Duration.between(connectedAt, Instant.now()).toSeconds();
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }
}
