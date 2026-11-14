package cn.xiaobingkuai123.mcai;

import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PublicAiManagerTest {
    @Test void triggerMatchesOnlyAtStartAndAtWordBoundary() {
        assertEquals("你好", PublicAiManager.parseTrigger("AI", "@AI 你好"));
        assertEquals("hello", PublicAiManager.parseTrigger("AI", "@ai hello"));
        assertEquals("", PublicAiManager.parseTrigger("AI", "@AI"));
        assertNull(PublicAiManager.parseTrigger("AI", "@AIfoo"));
        assertNull(PublicAiManager.parseTrigger("AI", "hello @AI hi"));
        assertNull(PublicAiManager.parseTrigger("", "@ hello"));
        assertEquals("你好", PublicAiManager.parseTrigger("助手", "@助手 你好"));
    }
    @Test void packagedPublicConfigurationIsIndependent() throws Exception {
        var config = new YamlConfiguration();
        config.load(Path.of("src/main/resources/config.yml").toFile());
        assertEquals("openai", config.getString("ai.public.provider"));
        assertEquals("gpt-6.1-sol", config.getString("ai.public.model"));
        assertFalse(config.getBoolean("ai.public.history.persistence.enabled"));
        assertNull(config.get("ai.public.api-key"));
        assertEquals(20, config.getInt("ai.public.history.max-rounds"));
    }
    @Test void publicLanguageKeysMatch() throws Exception {
        var zh = LanguageManager.parseLanguage(Files.readString(Path.of("src/main/resources/lang/zh_CN.yml")));
        var en = LanguageManager.parseLanguage(Files.readString(Path.of("src/main/resources/lang/en_US.yml")));
        assertEquals(zh.getKeys(false).stream().filter(k -> k.startsWith("public-ai-")).collect(java.util.stream.Collectors.toSet()),
                en.getKeys(false).stream().filter(k -> k.startsWith("public-ai-")).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void codePointChunksDoNotSplitSurrogatesAndRespectLimit() {
        var chunks = PublicAiManager.splitCodePoints("A😀BC😀", 2);
        assertEquals(List.of("A😀", "BC", "😀"), chunks);
        assertTrue(chunks.stream().allMatch(s -> s.codePointCount(0, s.length()) <= 2));
        assertTrue(chunks.stream().noneMatch(s -> s.contains("\\uD800") || s.contains("\\uDC00")));
    }

    @Test void contextSanitizationRemovesUnknownPlaceholdersControlsAndLimitsCodePoints() {
        String value = PublicAiManager.sanitizeContext("Steve\n%unknown%\u0000" + "😀".repeat(600));
        assertFalse(value.contains("%unknown%"));
        assertFalse(value.contains("\u0000"));
        assertTrue(value.codePointCount(0, value.length()) <= 500);
        assertTrue(value.startsWith("Steve"));
    }

    @Test void zeroRefundAndRepeatedRefundRemainSafe() {
        var record = new EconomyManager.DailyUsageRecord();
        record.resetIfNewDay(); record.pointsUsed = 4;
        var charge = new EconomyManager.Charge(record, record.currentDate, 4);
        assertEquals(4, charge.refund(4));
        assertEquals(0, charge.refund(4));
        assertEquals(0, record.pointsUsed);
        assertEquals(0, new EconomyManager.Charge(record, record.currentDate, 0).refund(0));
    }
    @Test void publicThinkingDisplayUsesProviderSpecificEvents() {
        var openai = profile(new cn.xiaobingkuai123.mcai.adapter.OpenAIResponsesAdapter(30), true, "allowed");
        var deepseek = new ChatManager.ApiRequestProfile("deepseek", new cn.xiaobingkuai123.mcai.adapter.DeepSeekAdapter(30),
                "key", "https://example.test", "m", 8192, "system", true, "effort", 4096, "high", .7, 1.0, 30, 0, true, false, false, "reasoning");
        var gemini = new ChatManager.ApiRequestProfile("gemini", new cn.xiaobingkuai123.mcai.adapter.GeminiAdapter(30),
                "key", "https://example.test", "m", 8192, "system", true, "budget", 4096, "4096", .7, 1.0, 30, 0, true, false, true, "summary");
        assertTrue(PublicAiManager.thinkingEventAllowed(openai, AiStreamClient.EventType.OPENAI_REASONING_SUMMARY));
        assertFalse(PublicAiManager.thinkingEventAllowed(openai, AiStreamClient.EventType.DEEPSEEK_REASONING));
        assertTrue(PublicAiManager.thinkingEventAllowed(deepseek, AiStreamClient.EventType.DEEPSEEK_REASONING));
        assertTrue(PublicAiManager.thinkingEventAllowed(gemini, AiStreamClient.EventType.GEMINI_THOUGHT_SUMMARY));
    }
    private static ChatManager.ApiRequestProfile profile(cn.xiaobingkuai123.mcai.adapter.ApiAdapter adapter, boolean thinking, String model) {
        return new ChatManager.ApiRequestProfile("openai", adapter, "key", "https://example.test", model, 8192,
                "system", thinking, "effort", thinking ? 4096 : 0, "high", .7, 1.0, 30, 0, true, false,
                thinking && adapter instanceof cn.xiaobingkuai123.mcai.adapter.OpenAIResponsesAdapter,
                thinking && adapter instanceof cn.xiaobingkuai123.mcai.adapter.OpenAIResponsesAdapter ? "summary" : "none");
    }
    @Test void consoleContextUsesLanguageNameAndNeverNeedsPlayerOrPapi() throws Exception {
        for (String language : List.of("zh_CN", "en_US")) {
            var lang = LanguageManager.parseLanguage(Files.readString(Path.of("src/main/resources/lang/" + language + ".yml")));
            String name = lang.getString("console-name");
            String expected = language.equals("zh_CN") ? "控制台" : "Console";
            assertEquals(expected, name);
            assertEquals("[" + expected + "] ", PublicAiManager.resolveConsoleContext("[{player}] %player_health%", name));
        }
    }
}
