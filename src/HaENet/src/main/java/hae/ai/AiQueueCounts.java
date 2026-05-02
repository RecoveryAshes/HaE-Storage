package hae.ai;

public final class AiQueueCounts {
    private static final AiQueueCounts ZERO = new AiQueueCounts(0, 0, 0, 0, 0);

    private final int pending;
    private final int running;
    private final int succeeded;
    private final int failed;
    private final int skipped;

    public AiQueueCounts(int pending, int running, int succeeded, int failed, int skipped) {
        this.pending = Math.max(0, pending);
        this.running = Math.max(0, running);
        this.succeeded = Math.max(0, succeeded);
        this.failed = Math.max(0, failed);
        this.skipped = Math.max(0, skipped);
    }

    public static AiQueueCounts zero() {
        return ZERO;
    }

    public int getPending() {
        return pending;
    }

    public int getRunning() {
        return running;
    }

    public int getSucceeded() {
        return succeeded;
    }

    public int getFailed() {
        return failed;
    }

    public int getSkipped() {
        return skipped;
    }

    public int getTotal() {
        return pending + running + succeeded + failed + skipped;
    }

    public double getCompletionSuccessRate() {
        int completed = succeeded + failed;
        if (completed <= 0) {
            return 0.0;
        }
        return (succeeded * 100.0) / completed;
    }

    public String toStatusText() {
        return String.format(
                "待处理=%d 运行中=%d 成功=%d 失败=%d 跳过=%d 成功率=%.1f%%",
                pending,
                running,
                succeeded,
                failed,
                skipped,
                getCompletionSuccessRate()
        );
    }
}
