package cn.xiaobingkuai123.mcai;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryPersistenceTest {
    @TempDir Path directory;
    @Test void publicFileIsIndependentAndRetainsProviderBlocks() throws Exception {
        var persistence = new HistoryPersistence(directory.toFile());
        var history = new ConversationHistory(ConversationHistory.Mode.ACCUMULATE, 1);
        history.addRound(List.of(new cn.xiaobingkuai123.mcai.adapter.ApiAdapter.Message("user", "public"),
                new cn.xiaobingkuai123.mcai.adapter.ApiAdapter.Message("assistant", "summary", "thinking", "signature"),
                new cn.xiaobingkuai123.mcai.adapter.ApiAdapter.Message("assistant", "answer")));
        persistence.savePublic(history);
        UUID id = UUID.randomUUID();
        assertEquals(0, persistence.load(id, ConversationHistory.Mode.SLIDING, 1).roundCount());
        var loaded = persistence.loadPublic(ConversationHistory.Mode.ACCUMULATE, 1);
        assertTrue(loaded.capped());
        assertEquals("signature", loaded.persistedMessages().get(1).signature());
        assertTrue(java.nio.file.Files.isRegularFile(directory.resolve("history/public-ai.yml")));
        persistence.save(id, history);
        persistence.deletePublic();
        assertEquals(0, persistence.loadPublic(ConversationHistory.Mode.SLIDING, 1).roundCount());
        assertEquals(1, persistence.load(id, ConversationHistory.Mode.SLIDING, 1).roundCount());
    }
    @Test void messageIndicesAreNumericAndMalformedAliasesAreIgnored() throws Exception {
        UUID id = UUID.randomUUID();
        Path historyDir = java.nio.file.Files.createDirectories(directory.resolve("history"));
        var yaml = new YamlConfiguration();
        yaml.set("version", 1);
        for (String key : List.of("10", "11", "2", "3", "0", "1", "8", "9", "6", "7", "4", "5", "-1", "01", "bad")) {
            int index;
            try { index = Integer.parseInt(key); } catch (NumberFormatException e) { index = 0; }
            yaml.set("messages." + key + ".role", index % 2 == 0 ? "user" : "assistant");
            yaml.set("messages." + key + ".content", key);
        }
        yaml.save(historyDir.resolve(id + ".yml").toFile());
        var history = new HistoryPersistence(directory.toFile()).load(id, ConversationHistory.Mode.SLIDING, 10);
        assertEquals(java.util.stream.IntStream.range(0, 12).mapToObj(String::valueOf).toList(),
                history.persistedMessages().stream().map(ConversationHistory.Message::content).toList());
    }
}
