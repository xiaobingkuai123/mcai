package cn.xiaobingkuai123.mcai;

import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class LanguageManager {
    private static final String DEFAULT_LANGUAGE = "zh_CN";
    private static final String LANGUAGE_ID_PATTERN = "[A-Za-z0-9_-]+";

    private final McAI plugin;
    private volatile Map<String, FileConfiguration> languageConfigs = Map.of();
    private volatile String currentLanguage = DEFAULT_LANGUAGE;

    public LanguageManager(McAI plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Loads only the configured language file; zh_CN is the fallback for missing or invalid language files. */
    public synchronized void reload() {
        String requestedLanguage = plugin.getConfig().getString("language", DEFAULT_LANGUAGE);
        if (!isValidLanguageId(requestedLanguage)) {
            plugin.getLogger().warning("Invalid language identifier; using " + DEFAULT_LANGUAGE + ".");
            requestedLanguage = DEFAULT_LANGUAGE;
        }

        Map<String, FileConfiguration> loadedConfigs = new HashMap<>();
        FileConfiguration fallback = loadLanguageFile(DEFAULT_LANGUAGE);
        if (fallback != null) {
            loadedConfigs.put(DEFAULT_LANGUAGE, fallback);
        }

        FileConfiguration selected = requestedLanguage.equals(DEFAULT_LANGUAGE)
                ? fallback : loadLanguageFile(requestedLanguage);
        if (selected == null) {
            plugin.getLogger().warning("Language file '" + requestedLanguage
                    + ".yml' is missing or invalid; using " + DEFAULT_LANGUAGE + ".");
            requestedLanguage = DEFAULT_LANGUAGE;
            selected = fallback;
        }
        if (selected != null) {
            loadedConfigs.put(requestedLanguage, selected);
        }

        languageConfigs = Map.copyOf(loadedConfigs);
        currentLanguage = requestedLanguage;
    }

    private FileConfiguration loadLanguageFile(String language) {
        String resourcePath = "lang/" + language + ".yml";
        File languageDirectory = new File(plugin.getDataFolder(), "lang");
        File languageFile = new File(languageDirectory, language + ".yml");

        if (!languageFile.isFile()) {
            try (InputStream resource = plugin.getResource(resourcePath)) {
                if (resource == null) {
                    return null;
                }
                Files.createDirectories(languageDirectory.toPath());
                plugin.saveResource(resourcePath, false);
            } catch (IOException | IllegalArgumentException e) {
                plugin.getLogger().warning("Unable to create language file '" + language
                        + ".yml': " + e.getMessage());
                return null;
            }
        }

        FileConfiguration config;
        try {
            config = parseLanguage(Files.readString(languageFile.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning("Unable to load language file '" + language
                    + ".yml': " + e.getMessage());
            return null;
        }

        // Packaged defaults fill missing keys while retaining server-side edits.
        try (InputStream defaults = plugin.getResource(resourcePath)) {
            if (defaults != null) {
                FileConfiguration defaultsConfig = parseLanguage(
                        new String(defaults.readAllBytes(), StandardCharsets.UTF_8));
                config.setDefaults(defaultsConfig);
            }
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning("Unable to load packaged defaults for language '"
                    + language + "': " + e.getMessage());
        }
        return config;
    }

    static FileConfiguration parseLanguage(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        if (config.getKeys(false).isEmpty()) {
            throw new InvalidConfigurationException("Language file must contain message entries");
        }
        for (String key : config.getKeys(false)) {
            if (!(config.get(key) instanceof String)) {
                throw new InvalidConfigurationException("Language entry '" + key + "' must be a string");
            }
        }
        return config;
    }

    static boolean isValidLanguageId(String language) {
        return language != null && language.matches(LANGUAGE_ID_PATTERN);
    }

    public String get(String key) {
        Map<String, FileConfiguration> configs = languageConfigs;
        FileConfiguration config = configs.get(currentLanguage);
        if (config == null) {
            config = configs.get(DEFAULT_LANGUAGE);
        }
        String message = config == null ? key : config.getString(key, key);
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public String get(String key, String... placeholders) {
        String message = get(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            message = message.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return message;
    }
}
