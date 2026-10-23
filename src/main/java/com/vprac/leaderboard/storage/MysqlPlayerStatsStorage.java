package com.vprac.leaderboard.storage;

import com.vprac.VpracPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** MySQL/MariaDB-compatible normalized storage with atomic counter updates. */
public final class MysqlPlayerStatsStorage extends AbstractPlayerStatsStorage {
    private final String url;
    private final String username;
    private final String password;
    private final String statsTable;
    private final String periodsTable;

    public MysqlPlayerStatsStorage(VpracPlugin plugin) throws Exception {
        super(plugin, "NexPractice-MySQL-Stats");
        String host = plugin.getConfig().getString("storage.mysql.host", "127.0.0.1").trim();
        int port = Math.max(1, Math.min(65535, plugin.getConfig().getInt("storage.mysql.port", 3306)));
        String database = plugin.getConfig().getString("storage.mysql.database", "vprac").trim();
        if (!database.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("storage.mysql.database must contain only letters, numbers, and underscores");
        boolean ssl = plugin.getConfig().getBoolean("storage.mysql.use-ssl", false);
        this.url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + ssl + "&serverTimezone=UTC&connectTimeout=5000&socketTimeout=5000";
        this.username = plugin.getConfig().getString("storage.mysql.username", "vprac");
        this.password = plugin.getConfig().getString("storage.mysql.password", "change-me");
        String prefix = plugin.getConfig().getString("storage.mysql.table-prefix", "vprac_").trim();
        if (!prefix.matches("[A-Za-z0-9_]{1,32}")) throw new IllegalArgumentException("storage.mysql.table-prefix must contain only letters, numbers, and underscores (max 32)");
        this.statsTable = prefix + "player_stats";
        this.periodsTable = prefix + "player_stat_periods";
        Class.forName("com.mysql.cj.jdbc.Driver");
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + statsTable + "` ("
                    + "player_uuid CHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL, kit_id VARCHAR(32) NOT NULL, "
                    + "played BOOLEAN NOT NULL DEFAULT TRUE, wins BIGINT NOT NULL DEFAULT 0, losses BIGINT NOT NULL DEFAULT 0, "
                    + "kills BIGINT NOT NULL DEFAULT 0, deaths BIGINT NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (player_uuid, kit_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + periodsTable + "` ("
                    + "player_uuid CHAR(36) NOT NULL, kit_id VARCHAR(32) NOT NULL, period_type VARCHAR(8) NOT NULL, "
                    + "period_bucket VARCHAR(16) NOT NULL, wins BIGINT NOT NULL DEFAULT 0, kills BIGINT NOT NULL DEFAULT 0, "
                    + "deaths BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (player_uuid, kit_id, period_type, period_bucket)) "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    @Override protected Map<String, String> loadAllNow() throws Exception {
        Map<String, YamlConfiguration> documents = new LinkedHashMap<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT player_uuid, player_name, kit_id, played, wins, losses, kills, deaths FROM `" + statsTable + "`")) {
                while (rows.next()) {
                    String uuid = rows.getString("player_uuid");
                    String kit = rows.getString("kit_id").toLowerCase(Locale.ROOT);
                    YamlConfiguration yaml = documents.computeIfAbsent(uuid, ignored -> new YamlConfiguration());
                    String root = "player.kits." + kit;
                    yaml.set("player.name", rows.getString("player_name"));
                    yaml.set(root + ".played", rows.getBoolean("played"));
                    yaml.set(root + ".wins", rows.getLong("wins"));
                    yaml.set(root + ".losses", rows.getLong("losses"));
                    yaml.set(root + ".kills", rows.getLong("kills"));
                    yaml.set(root + ".deaths", rows.getLong("deaths"));
                }
            }
            try (ResultSet rows = statement.executeQuery("SELECT player_uuid, kit_id, period_type, period_bucket, wins, kills, deaths FROM `" + periodsTable + "`")) {
                while (rows.next()) {
                    String uuid = rows.getString("player_uuid");
                    YamlConfiguration yaml = documents.get(uuid);
                    if (yaml == null) continue;
                    String root = "player.kits." + rows.getString("kit_id").toLowerCase(Locale.ROOT)
                            + ".periods." + rows.getString("period_type") + "." + rows.getString("period_bucket");
                    yaml.set(root + ".wins", rows.getLong("wins"));
                    yaml.set(root + ".kills", rows.getLong("kills"));
                    yaml.set(root + ".deaths", rows.getLong("deaths"));
                }
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        documents.forEach((uuid, yaml) -> result.put(uuid, yaml.saveToString()));
        return result;
    }

    @Override protected void incrementStatsNow(UUID uuid, String playerName, String kitId,
                                               Map<String, Integer> deltas,
                                               Map<String, Map<String, Integer>> periodDeltas) throws Exception {
        String normalizedKit = kitId.toLowerCase(Locale.ROOT);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                String sql = "INSERT INTO `" + statsTable + "` (player_uuid, player_name, kit_id, played, wins, losses, kills, deaths) "
                        + "VALUES (?, ?, ?, TRUE, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                        + "player_name=VALUES(player_name), played=TRUE, wins=wins+VALUES(wins), losses=losses+VALUES(losses), "
                        + "kills=kills+VALUES(kills), deaths=deaths+VALUES(deaths)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, uuid.toString());
                    statement.setString(2, safeName(playerName, uuid));
                    statement.setString(3, normalizedKit);
                    statement.setLong(4, positive(deltas.get("wins")));
                    statement.setLong(5, positive(deltas.get("losses")));
                    statement.setLong(6, positive(deltas.get("kills")));
                    statement.setLong(7, positive(deltas.get("deaths")));
                    statement.executeUpdate();
                }
                for (Map.Entry<String, Map<String, Integer>> periodEntry : periodDeltas.entrySet()) {
                    String[] key = periodEntry.getKey().split(":", 2);
                    if (key.length != 2 || !(key[0].equals("daily") || key[0].equals("weekly") || key[0].equals("monthly"))) continue;
                    Map<String, Integer> counts = periodEntry.getValue();
                    String periodSql = "INSERT INTO `" + periodsTable + "` (player_uuid, kit_id, period_type, period_bucket, wins, kills, deaths) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                            + "wins=wins+VALUES(wins), kills=kills+VALUES(kills), deaths=deaths+VALUES(deaths)";
                    try (PreparedStatement statement = connection.prepareStatement(periodSql)) {
                        statement.setString(1, uuid.toString());
                        statement.setString(2, normalizedKit);
                        statement.setString(3, key[0]);
                        statement.setString(4, key[1]);
                        statement.setLong(5, positive(counts.get("wins")));
                        statement.setLong(6, positive(counts.get("kills")));
                        statement.setLong(7, positive(counts.get("deaths")));
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override protected void updateNameNow(UUID uuid, String playerName) throws Exception {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE `" + statsTable + "` SET player_name=? WHERE player_uuid=?")) {
            statement.setString(1, playerName);
            statement.setString(2, uuid.toString());
            statement.executeUpdate();
        }
    }

    @Override protected void replacePlayerNow(UUID uuid, String serializedPlayerYaml) throws Exception {
        YamlConfiguration yaml = PlayerStatsYamlCodec.parse(serializedPlayerYaml);
        ConfigurationSection player = yaml.getConfigurationSection("player");
        ConfigurationSection kits = player == null ? null : player.getConfigurationSection("kits");
        String playerName = player == null ? uuid.toString().substring(0, 8) : safeName(player.getString("name"), uuid);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement deleteStats = connection.prepareStatement("DELETE FROM `" + statsTable + "` WHERE player_uuid=?");
                     PreparedStatement deletePeriods = connection.prepareStatement("DELETE FROM `" + periodsTable + "` WHERE player_uuid=?")) {
                    deleteStats.setString(1, uuid.toString()); deleteStats.executeUpdate();
                    deletePeriods.setString(1, uuid.toString()); deletePeriods.executeUpdate();
                }
                if (kits != null) {
                    for (String kit : kits.getKeys(false)) {
                        ConfigurationSection data = kits.getConfigurationSection(kit);
                        if (data == null) continue;
                        String sql = "INSERT INTO `" + statsTable + "` (player_uuid, player_name, kit_id, played, wins, losses, kills, deaths) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            statement.setString(1, uuid.toString()); statement.setString(2, playerName); statement.setString(3, kit.toLowerCase(Locale.ROOT));
                            statement.setBoolean(4, data.getBoolean("played", true));
                            statement.setLong(5, data.getLong("wins", 0)); statement.setLong(6, data.getLong("losses", 0));
                            statement.setLong(7, data.getLong("kills", 0)); statement.setLong(8, data.getLong("deaths", 0));
                            statement.executeUpdate();
                        }
                        ConfigurationSection periods = data.getConfigurationSection("periods");
                        if (periods == null) continue;
                        for (String period : periods.getKeys(false)) {
                            ConfigurationSection buckets = periods.getConfigurationSection(period);
                            if (buckets == null) continue;
                            for (String bucket : buckets.getKeys(false)) {
                                ConfigurationSection counts = buckets.getConfigurationSection(bucket);
                                if (counts == null) continue;
                                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO `" + periodsTable + "` (player_uuid, kit_id, period_type, period_bucket, wins, kills, deaths) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                                    statement.setString(1, uuid.toString()); statement.setString(2, kit.toLowerCase(Locale.ROOT));
                                    statement.setString(3, period); statement.setString(4, bucket);
                                    statement.setLong(5, counts.getLong("wins", 0)); statement.setLong(6, counts.getLong("kills", 0));
                                    statement.setLong(7, counts.getLong("deaths", 0)); statement.executeUpdate();
                                }
                            }
                        }
                    }
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override protected void removeKitNow(String kitId) throws Exception {
        String kit = kitId.toLowerCase(Locale.ROOT);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement periods = connection.prepareStatement("DELETE FROM `" + periodsTable + "` WHERE kit_id=?");
                 PreparedStatement stats = connection.prepareStatement("DELETE FROM `" + statsTable + "` WHERE kit_id=?")) {
                periods.setString(1, kit); periods.executeUpdate();
                stats.setString(1, kit); stats.executeUpdate();
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    @Override public String label() { return "MySQL"; }
    @Override protected void closeResources() { }

    private static long positive(Integer value) { return value == null ? 0L : Math.max(0, value); }
    private static String safeName(String name, UUID uuid) {
        return name == null || name.isBlank() ? uuid.toString().substring(0, 8) : name;
    }
}
