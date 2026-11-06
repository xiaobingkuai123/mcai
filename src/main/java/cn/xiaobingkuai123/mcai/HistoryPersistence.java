package cn.xiaobingkuai123.mcai;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Persists the canonical normalized conversation model as one YAML file per UUID. */
public final class HistoryPersistence {
    private static final int VERSION = 1;
    private final File directory;

    public HistoryPersistence(File dataFolder) {
        directory = new File(dataFolder, "history");
    }

    public ConversationHistory load(UUID id, ConversationHistory.Mode mode, int maxRounds) {
        File file = fileFor(id);
        if (!file.isFile()) return new ConversationHistory(mode, maxRounds);
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            if (yaml.getInt("version", -1) != VERSION) {
                return new ConversationHistory(mode, maxRounds);
            }

            List<ConversationHistory.Message> messages = new ArrayList<>();
            ConfigurationSection section = yaml.getConfigurationSection("messages");
            if (section != null) {
                TreeMap<Integer, String> orderedKeys = new TreeMap<>();
                for (String key : section.getKeys(false)) {
                    try {
                        int index = Integer.parseInt(key);
                        if (index >= 0 && key.equals(Integer.toString(index))) orderedKeys.put(index, key);
                    } catch (NumberFormatException ignored) {
                        // Ignore malformed entries; persisted message keys are zero-based integers.
                    }
                }
                for (String key : orderedKeys.values()) {
                    String path = "messages." + key;
                    String role = yaml.getString(path + ".role", "");
                    if (!Set.of("user", "assistant", "tool").contains(role)) continue;
                    messages.add(new ConversationHistory.Message(
                            role,
                            yaml.getString(path + ".content", ""),
                            yaml.getLong(path + ".time", 0),
                            yaml.getString(path + ".blockType", "text"),
                            yaml.getString(path + ".signature", null)));
                }
            }
            return ConversationHistory.fromMessages(mode, maxRounds, messages);
        } catch (Exception ignored) {
            return new ConversationHistory(mode, maxRounds);
        }
    }

    public void save(UUID id, ConversationHistory history) throws IOException {
        saveMessages(id, history.persistedMessages());
    }

    public void saveMessages(UUID id, List<ConversationHistory.Message> snapshot) throws IOException {
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("history directory unavailable");
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("version", VERSION);
        yaml.set("uuid", id.toString());
        for (int i = 0; i < snapshot.size(); i++) {
            ConversationHistory.Message message = snapshot.get(i);
            String path = "messages." + i;
            yaml.set(path + ".role", message.role());
            yaml.set(path + ".content", message.content());
            yaml.set(path + ".time", message.time());
            if (message.blockType() != null && !"text".equals(message.blockType())) {
                yaml.set(path + ".blockType", message.blockType());
            }
            if (message.signature() != null) {
                yaml.set(path + ".signature", message.signature());
            }
        }

        File target = fileFor(id);
        File temporary = new File(target.getPath() + ".tmp");
        yaml.save(temporary);
        try {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void delete(UUID id) {
        try {
            Files.deleteIfExists(fileFor(id).toPath());
        } catch (IOException ignored) {
        }
    }

    private File fileFor(UUID id) {
        return new File(directory, id + ".yml");
    }
}
