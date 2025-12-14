package com.ximpify.rentabot.storage;

import com.ximpify.rentabot.RentABot;
import com.ximpify.rentabot.bot.RentableBot;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages persistent storage for rentals.
 * Supports SQLite and MySQL.
 */
public class StorageManager {
    
    private final RentABot plugin;
    private HikariDataSource dataSource;
    private String tablePrefix;
    
    public StorageManager(RentABot plugin) {
        this.plugin = plugin;
        this.tablePrefix = "rentabot_";
    }
    
    /**
     * Initializes the database connection and creates tables.
     */
    public boolean initialize() {
        String type = plugin.getConfig().getString("storage.type", "sqlite").toLowerCase();
        
        try {
            if (type.equals("mysql")) {
                initializeMySQL();
            } else {
                initializeSQLite();
            }
            
            createTables();
            plugin.getLogger().info("Database initialized successfully (" + type + ")");
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    private void initializeSQLite() {
        String fileName = plugin.getConfig().getString("storage.sqlite.file", "rentals.db");
        File dbFile = new File(plugin.getDataFolder(), fileName);
        
        HikariConfig config = new HikariConfig();
        config.setPoolName("RentABot-SQLite");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setMaximumPoolSize(1); // SQLite doesn't support multiple connections well
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30000);
        
        dataSource = new HikariDataSource(config);
    }
    
    private void initializeMySQL() {
        String host = plugin.getConfig().getString("storage.mysql.host", "localhost");
        int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
        String database = plugin.getConfig().getString("storage.mysql.database", "rentabot");
        String username = plugin.getConfig().getString("storage.mysql.username", "root");
        String password = plugin.getConfig().getString("storage.mysql.password", "");
        
        HikariConfig config = new HikariConfig();
        config.setPoolName("RentABot-MySQL");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + 
            "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true");
        config.setUsername(username);
        config.setPassword(password);
        
        int maxPoolSize = plugin.getConfig().getInt("storage.mysql.pool.maximum-pool-size", 10);
        int minIdle = plugin.getConfig().getInt("storage.mysql.pool.minimum-idle", 2);
        long timeout = plugin.getConfig().getLong("storage.mysql.pool.connection-timeout", 30000);
        
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(timeout);
        
        dataSource = new HikariDataSource(config);
    }
    
    private void createTables() throws SQLException {
        String createRentalsTable = """
            CREATE TABLE IF NOT EXISTS %srentals (
                id INTEGER PRIMARY KEY %s,
                bot_name VARCHAR(64) NOT NULL UNIQUE,
                display_name VARCHAR(64) NOT NULL,
                owner_uuid VARCHAR(36) NOT NULL,
                owner_name VARCHAR(16) NOT NULL,
                status VARCHAR(16) DEFAULT 'ACTIVE',
                remaining_seconds BIGINT DEFAULT 0,
                last_active BIGINT,
                created_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL,
                world VARCHAR(64),
                x DOUBLE DEFAULT 0,
                y DOUBLE DEFAULT 0,
                z DOUBLE DEFAULT 0,
                yaw FLOAT DEFAULT 0,
                pitch FLOAT DEFAULT 0,
                spawn_world VARCHAR(64),
                spawn_x DOUBLE DEFAULT 0,
                spawn_y DOUBLE DEFAULT 0,
                spawn_z DOUBLE DEFAULT 0,
                spawn_yaw FLOAT DEFAULT 0,
                spawn_pitch FLOAT DEFAULT 0
            )
            """.formatted(tablePrefix, 
                plugin.getConfig().getString("storage.type", "sqlite").equalsIgnoreCase("mysql") 
                    ? "AUTO_INCREMENT" : "AUTOINCREMENT");
        
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createRentalsTable);
            
            // Try to add columns if they don't exist (for existing databases)
            tryAddColumn(stmt, "spawn_world", "VARCHAR(64)");
            tryAddColumn(stmt, "spawn_x", "DOUBLE DEFAULT 0");
            tryAddColumn(stmt, "spawn_y", "DOUBLE DEFAULT 0");
            tryAddColumn(stmt, "spawn_z", "DOUBLE DEFAULT 0");
            tryAddColumn(stmt, "spawn_yaw", "FLOAT DEFAULT 0");
            tryAddColumn(stmt, "spawn_pitch", "FLOAT DEFAULT 0");
            // New columns for bot lifecycle
            tryAddColumn(stmt, "status", "VARCHAR(16) DEFAULT 'ACTIVE'");
            tryAddColumn(stmt, "remaining_seconds", "BIGINT DEFAULT 0");
            tryAddColumn(stmt, "last_active", "BIGINT");
        }
    }
    
    /**
     * Helper method to add a column if it doesn't exist.
     */
    private void tryAddColumn(Statement stmt, String columnName, String columnDef) {
        try {
            stmt.execute("ALTER TABLE " + tablePrefix + "rentals ADD COLUMN " + columnName + " " + columnDef);
        } catch (SQLException ignored) {
            // Column already exists
        }
    }
    
    /**
     * Saves a rental to the database.
     */
    public void saveRental(RentableBot bot) {
        String sql = """
            INSERT OR REPLACE INTO %srentals 
            (bot_name, display_name, owner_uuid, owner_name, status, remaining_seconds, last_active,
             created_at, expires_at, world, x, y, z, yaw, pitch,
             spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.formatted(tablePrefix);
        
        // MySQL uses different syntax
        if (plugin.getConfig().getString("storage.type", "sqlite").equalsIgnoreCase("mysql")) {
            sql = """
                INSERT INTO %srentals 
                (bot_name, display_name, owner_uuid, owner_name, status, remaining_seconds, last_active,
                 created_at, expires_at, world, x, y, z, yaw, pitch,
                 spawn_world, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                status = VALUES(status),
                remaining_seconds = VALUES(remaining_seconds),
                last_active = VALUES(last_active),
                expires_at = VALUES(expires_at),
                world = VALUES(world),
                x = VALUES(x),
                y = VALUES(y),
                z = VALUES(z),
                yaw = VALUES(yaw),
                pitch = VALUES(pitch),
                spawn_world = VALUES(spawn_world),
                spawn_x = VALUES(spawn_x),
                spawn_y = VALUES(spawn_y),
                spawn_z = VALUES(spawn_z),
                spawn_yaw = VALUES(spawn_yaw),
                spawn_pitch = VALUES(spawn_pitch)
                """.formatted(tablePrefix);
        }
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, bot.getInternalName());
            stmt.setString(2, bot.getDisplayName());
            stmt.setString(3, bot.getOwnerUUID().toString());
            stmt.setString(4, bot.getOwnerName());
            stmt.setString(5, bot.getStatus().name());
            stmt.setLong(6, bot.getRemainingSeconds());
            stmt.setLong(7, bot.getLastActiveAt() != null ? bot.getLastActiveAt().toEpochMilli() : System.currentTimeMillis());
            stmt.setLong(8, bot.getCreatedAt().toEpochMilli());
            stmt.setLong(9, bot.getExpiresAt().toEpochMilli());
            stmt.setString(10, bot.getWorld());
            stmt.setDouble(11, bot.getX());
            stmt.setDouble(12, bot.getY());
            stmt.setDouble(13, bot.getZ());
            stmt.setFloat(14, bot.getYaw());
            stmt.setFloat(15, bot.getPitch());
            
            // Spawn point data
            if (bot.hasSpawnPoint()) {
                stmt.setString(16, bot.getSavedWorld());
                stmt.setDouble(17, bot.getSavedX());
                stmt.setDouble(18, bot.getSavedY());
                stmt.setDouble(19, bot.getSavedZ());
                stmt.setFloat(20, bot.getSavedYaw());
                stmt.setFloat(21, bot.getSavedPitch());
            } else {
                stmt.setNull(16, java.sql.Types.VARCHAR);
                stmt.setDouble(17, 0);
                stmt.setDouble(18, 0);
                stmt.setDouble(19, 0);
                stmt.setFloat(20, 0);
                stmt.setFloat(21, 0);
            }
            
            stmt.executeUpdate();
            plugin.debug("Saved rental: " + bot.getInternalName() + " (status: " + bot.getStatus() + ")");
            
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to save rental: " + e.getMessage());
        }
    }
    
    /**
     * Loads all rentals from the database.
     */
    public List<RentableBot> loadRentals() {
        List<RentableBot> rentals = new ArrayList<>();
        
        String sql = "SELECT * FROM %srentals".formatted(tablePrefix);
        
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                String internalName = rs.getString("bot_name");
                String displayName = rs.getString("display_name");
                UUID ownerUUID = UUID.fromString(rs.getString("owner_uuid"));
                String ownerName = rs.getString("owner_name");
                Instant createdAt = Instant.ofEpochMilli(rs.getLong("created_at"));
                Instant expiresAt = Instant.ofEpochMilli(rs.getLong("expires_at"));
                
                // Load status and remaining time
                String statusStr = rs.getString("status");
                com.ximpify.rentabot.bot.BotStatus status = com.ximpify.rentabot.bot.BotStatus.fromString(statusStr);
                long remainingSeconds = rs.getLong("remaining_seconds");
                long lastActiveMillis = rs.getLong("last_active");
                
                // Calculate remaining hours (minimum 1 to properly reconstruct)
                long remainingHours = Math.max(1, remainingSeconds / 3600);
                if (status == com.ximpify.rentabot.bot.BotStatus.ACTIVE) {
                    remainingHours = Math.max(1, (expiresAt.toEpochMilli() - System.currentTimeMillis()) / 3600000);
                }
                
                RentableBot bot = new RentableBot(plugin, displayName, internalName, 
                    ownerUUID, ownerName, (int) remainingHours);
                
                bot.setCreatedAt(createdAt);
                bot.setExpiresAt(expiresAt);
                bot.setStatus(status);
                bot.setRemainingSeconds(remainingSeconds);
                if (lastActiveMillis > 0) {
                    bot.setLastActiveAt(Instant.ofEpochMilli(lastActiveMillis));
                }
                
                String world = rs.getString("world");
                if (world != null) {
                    bot.setPosition(
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getFloat("pitch"),
                        world
                    );
                }
                
                // Load spawn point
                String spawnWorld = rs.getString("spawn_world");
                if (spawnWorld != null) {
                    bot.setSpawnPoint(
                        rs.getDouble("spawn_x"),
                        rs.getDouble("spawn_y"),
                        rs.getDouble("spawn_z"),
                        rs.getFloat("spawn_yaw"),
                        rs.getFloat("spawn_pitch"),
                        spawnWorld
                    );
                }
                
                rentals.add(bot);
            }
            
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to load rentals: " + e.getMessage());
        }
        
        return rentals;
    }
    
    /**
     * Deletes a rental from the database.
     */
    public void deleteRental(String botName) {
        String sql = "DELETE FROM %srentals WHERE bot_name = ?".formatted(tablePrefix);
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, botName);
            stmt.executeUpdate();
            plugin.debug("Deleted rental: " + botName);
            
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to delete rental: " + e.getMessage());
        }
    }
    
    /**
     * Closes the database connection.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection closed");
        }
    }
}
