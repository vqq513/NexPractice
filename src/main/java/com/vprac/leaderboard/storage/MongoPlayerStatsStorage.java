package com.vprac.leaderboard.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.vprac.VpracPlugin;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.set;
import static com.mongodb.client.model.Updates.unset;

/** MongoDB player-document storage with atomic $inc updates for shared-server safety. */
public final class MongoPlayerStatsStorage extends AbstractPlayerStatsStorage {
    private final MongoClient client;
    private final MongoCollection<Document> collection;

    public MongoPlayerStatsStorage(VpracPlugin plugin) {
        super(plugin, "NexPractice-Mongo-Stats");
        String uri = plugin.getConfig().getString("storage.mongodb.uri", "mongodb://127.0.0.1:27017");
        String database = plugin.getConfig().getString("storage.mongodb.database", "vprac").trim();
        String collectionName = plugin.getConfig().getString("storage.mongodb.collection", "player_stats").trim();
        if (!database.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("storage.mongodb.database has an invalid name");
        if (!collectionName.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("storage.mongodb.collection has an invalid name");
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(builder -> builder.serverSelectionTimeout(5, TimeUnit.SECONDS))
                .build();
        this.client = MongoClients.create(settings);
        MongoDatabase mongoDatabase = client.getDatabase(database);
        this.collection = mongoDatabase.getCollection(collectionName);
        // Force connection validation before NexPractice reports the backend as initialized.
        try {
            mongoDatabase.runCommand(new Document("ping", 1));
        } catch (RuntimeException exception) {
            client.close();
            throw exception;
        }
    }

    @Override protected Map<String, String> loadAllNow() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Document document : collection.find()) {
            Object rawId = document.get("_id");
            if (rawId == null) continue;
            String uuid = rawId.toString();
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("player.name", document.getString("name"));
            Object rawKits = document.get("kits");
            if (!(rawKits instanceof Map<?, ?> map) || map.isEmpty()) continue;
            @SuppressWarnings("unchecked") Map<String, Object> kits = (Map<String, Object>) map;
            PlayerStatsYamlCodec.copyMapToYaml(kits, yaml, "player.kits");
            result.put(uuid, yaml.saveToString());
        }
        return result;
    }

    @Override protected void incrementStatsNow(UUID uuid, String playerName, String kitId,
                                               Map<String, Integer> deltas,
                                               Map<String, Map<String, Integer>> periodDeltas) {
        String kit = kitId.toLowerCase(Locale.ROOT);
        List<Bson> updates = new ArrayList<>();
        updates.add(set("name", safeName(playerName, uuid)));
        updates.add(set("kits." + kit + ".played", true));
        for (String stat : List.of("wins", "losses", "kills", "deaths")) {
            long delta = positive(deltas.get(stat));
            if (delta > 0) updates.add(inc("kits." + kit + "." + stat, delta));
        }
        for (Map.Entry<String, Map<String, Integer>> periodEntry : periodDeltas.entrySet()) {
            String[] key = periodEntry.getKey().split(":", 2);
            if (key.length != 2 || !(key[0].equals("daily") || key[0].equals("weekly") || key[0].equals("monthly"))) continue;
            for (String stat : List.of("wins", "kills", "deaths")) {
                long delta = positive(periodEntry.getValue().get(stat));
                if (delta > 0) {
                    updates.add(inc("kits." + kit + ".periods." + key[0] + "." + key[1] + "." + stat, delta));
                }
            }
        }
        collection.updateOne(eq("_id", uuid.toString()), combine(updates), new UpdateOptions().upsert(true));
    }

    @Override protected void updateNameNow(UUID uuid, String playerName) {
        collection.updateOne(eq("_id", uuid.toString()), set("name", playerName));
    }

    @Override protected void replacePlayerNow(UUID uuid, String serializedPlayerYaml) {
        YamlConfiguration yaml = PlayerStatsYamlCodec.parse(serializedPlayerYaml);
        ConfigurationSection player = yaml.getConfigurationSection("player");
        String playerName = player == null ? uuid.toString().substring(0, 8) : safeName(player.getString("name"), uuid);
        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("_id", uuid.toString());
        replacement.put("name", playerName);
        ConfigurationSection kits = player == null ? null : player.getConfigurationSection("kits");
        if (kits != null) {
            Map<String, Object> kitDocuments = new LinkedHashMap<>();
            for (String kit : kits.getKeys(false)) {
                ConfigurationSection section = kits.getConfigurationSection(kit);
                if (section != null) kitDocuments.put(kit.toLowerCase(Locale.ROOT), sectionToMap(section));
            }
            replacement.put("kits", kitDocuments);
        }
        collection.replaceOne(eq("_id", uuid.toString()), new Document(replacement), new ReplaceOptions().upsert(true));
    }

    @Override protected void removeKitNow(String kitId) {
        collection.updateMany(exists("kits." + kitId.toLowerCase(Locale.ROOT)),
                unset("kits." + kitId.toLowerCase(Locale.ROOT)));
    }

    @Override public String label() { return "MongoDB"; }
    @Override protected void closeResources() { client.close(); }

    private static Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) result.put(key, sectionToMap(child));
            else result.put(key, value);
        }
        return result;
    }

    private static long positive(Integer value) { return value == null ? 0L : Math.max(0, value); }
    private static String safeName(String name, UUID uuid) {
        return name == null || name.isBlank() ? uuid.toString().substring(0, 8) : name;
    }
}
