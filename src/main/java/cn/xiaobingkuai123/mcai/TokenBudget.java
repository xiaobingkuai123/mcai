package cn.xiaobingkuai123.mcai;

/** Applies only the minimum token budgets required by the supported request formats. */
final class TokenBudget {
    static final int MIN_OUTPUT_TOKENS = 1;
    static final int MIN_THINKING_TOKENS = 1_024;

    private TokenBudget() {
    }

    static int outputTokens(int value) {
        return Math.max(MIN_OUTPUT_TOKENS, value);
    }

    static int thinkingTokens(int value) {
        return Math.max(MIN_THINKING_TOKENS, value);
    }
}
