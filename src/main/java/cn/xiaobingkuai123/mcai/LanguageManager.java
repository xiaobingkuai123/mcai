package cn.xiaobingkuai123.mcai;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class LanguageManager {
    private final McAI plugin;
    private final Map<String, FileConfiguration> languageConfigs = new HashMap<>();
    private String currentLanguage;

    public LanguageManager(McAI plugin) {
        this.plugin = plugin;
        this.currentLanguage = plugin.getConfig().getString("language", "zh_CN");
        for (String lang : new String[]{"zh_CN", "en_US"}) {
            saveDefaultLanguageFile(lang);
            loadLanguageFile(lang);
        }
    }

    private void saveDefaultLanguageFile(String lang) {
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) langDir.mkdirs();
        File langFile = new File(langDir, lang + ".yml");
        if (!langFile.exists()) plugin.saveResource("lang/" + lang + ".yml", false);
    }

    private void loadLanguageFile(String lang) {
        File file = new File(plugin.getDataFolder(), "lang/" + lang + ".yml");
        if (file.exists()) {
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
            InputStream defaultStream = plugin.getResource("lang/" + lang + ".yml");
            if (defaultStream != null) {
                config.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defaultStream, StandardCharsets.UTF_8)));
            }
            languageConfigs.put(lang, config);
        }
    }

    public void reload() {
        currentLanguage = plugin.getConfig().getString("language", "zh_CN");
        languageConfigs.clear();
        for (String lang : new String[]{"zh_CN", "en_US"}) {
            loadLanguageFile(lang);
        }
    }

    public String get(String key) {
        FileConfiguration config = languageConfigs.getOrDefault(currentLanguage, languageConfigs.get("zh_CN"));
        return ChatColor.translateAlternateColorCodes('&', config.getString(key, key));
    }

    public String get(String key, String... placeholders) {
        String message = get(key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            message = message.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return message;
    }
}
