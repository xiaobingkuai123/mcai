package cn.xiaobingkuai123.mcai;

import org.bukkit.configuration.InvalidConfigurationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LanguageManagerTest {
    @Test
    void acceptsLanguageIdsSuitableForResourceFilenames() {
        assertTrue(LanguageManager.isValidLanguageId("zh_CN"));
        assertTrue(LanguageManager.isValidLanguageId("fr-CA"));
        assertTrue(LanguageManager.isValidLanguageId("pt_BR"));
        assertFalse(LanguageManager.isValidLanguageId("../en_US"));
        assertFalse(LanguageManager.isValidLanguageId(""));
        assertFalse(LanguageManager.isValidLanguageId(null));
    }

    @Test
    void parsesExistingYamlStringMessageFormat() throws Exception {
        var config = LanguageManager.parseLanguage("greeting: '&aHello {name}'\n");
        assertEquals("&aHello {name}", config.getString("greeting"));
    }

    @Test
    void rejectsInvalidOrEmptyLanguageFiles() {
        assertThrows(InvalidConfigurationException.class,
                () -> LanguageManager.parseLanguage("greeting: [unterminated\n"));
        assertThrows(InvalidConfigurationException.class,
                () -> LanguageManager.parseLanguage("# no messages\n"));
        assertThrows(InvalidConfigurationException.class,
                () -> LanguageManager.parseLanguage("greeting: 42\n"));
    }
}
