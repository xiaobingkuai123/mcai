package cn.xiaobingkuai123.mcai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenBudgetTest {
    @Test
    void outputBudgetHasOnlyTheRequiredPositiveMinimum() {
        assertEquals(1, TokenBudget.outputTokens(-10));
        assertEquals(4096, TokenBudget.outputTokens(4096));
        assertEquals(300_000, TokenBudget.outputTokens(300_000));
    }

    @Test
    void thinkingBudgetHasTheExistingMinimumAndNoPluginImposedMaximum() {
        assertEquals(1024, TokenBudget.thinkingTokens(0));
        assertEquals(4096, TokenBudget.thinkingTokens(4096));
        assertEquals(100_000, TokenBudget.thinkingTokens(100_000));
    }
}
