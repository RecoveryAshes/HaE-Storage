package hae.ai.worker;

import hae.Config;
import hae.ai.AiConfig;

public final class AiTriageWorkerConfig {
    public static final int DEFAULT_CONCURRENCY = 2;
    public static final int HARD_MAX_CONCURRENCY = 8;
    public static final int DEFAULT_MAX_IN_FLIGHT_CHARS = 2_000_000;
    public static final int DEFAULT_MAX_ATTEMPTS = 2;
    public static final long DEFAULT_LEASE_DURATION_MILLIS = 300_000L;
    public static final long DEFAULT_IDLE_POLL_MILLIS = 250L;
    public static final long DEFAULT_RECOVERY_INTERVAL_MILLIS = 5_000L;
    public static final long DEFAULT_RETRY_BASE_DELAY_MILLIS = 500L;
    public static final long DEFAULT_RETRY_MAX_DELAY_MILLIS = 30_000L;

    private final int concurrency;
    private final int maxInFlightChars;
    private final int defaultMaxAttempts;
    private final long leaseDurationMillis;
    private final long idlePollMillis;
    private final long recoveryIntervalMillis;
    private final long retryBaseDelayMillis;
    private final long retryMaxDelayMillis;
    private final boolean autoStart;

    private AiTriageWorkerConfig(Builder builder) {
        this.concurrency = normalizeConcurrency(builder.concurrency, builder.maxConcurrency);
        this.maxInFlightChars = positive(builder.maxInFlightChars, DEFAULT_MAX_IN_FLIGHT_CHARS);
        this.defaultMaxAttempts = positive(builder.defaultMaxAttempts, DEFAULT_MAX_ATTEMPTS);
        this.leaseDurationMillis = positiveLong(builder.leaseDurationMillis, DEFAULT_LEASE_DURATION_MILLIS);
        this.idlePollMillis = positiveLong(builder.idlePollMillis, DEFAULT_IDLE_POLL_MILLIS);
        this.recoveryIntervalMillis = positiveLong(builder.recoveryIntervalMillis, DEFAULT_RECOVERY_INTERVAL_MILLIS);
        this.retryBaseDelayMillis = positiveLong(builder.retryBaseDelayMillis, DEFAULT_RETRY_BASE_DELAY_MILLIS);
        this.retryMaxDelayMillis = Math.max(this.retryBaseDelayMillis, positiveLong(builder.retryMaxDelayMillis, DEFAULT_RETRY_MAX_DELAY_MILLIS));
        this.autoStart = builder.autoStart;
    }

    public static AiTriageWorkerConfig defaults() {
        return builder().build();
    }

    public static AiTriageWorkerConfig fromAiConfig(AiConfig aiConfig) {
        Builder builder = builder();
        if (aiConfig != null) {
            builder.concurrency(aiConfig.getConcurrency());
            builder.maxConcurrency(aiConfig.getMaxConcurrency());
            builder.maxInFlightChars(aiConfig.getMaxInFlightChars());
        }
        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getConcurrency() {
        return concurrency;
    }

    public int getMaxInFlightChars() {
        return maxInFlightChars;
    }

    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    public long getLeaseDurationMillis() {
        return leaseDurationMillis;
    }

    public long getIdlePollMillis() {
        return idlePollMillis;
    }

    public long getRecoveryIntervalMillis() {
        return recoveryIntervalMillis;
    }

    public long getRetryBaseDelayMillis() {
        return retryBaseDelayMillis;
    }

    public long getRetryMaxDelayMillis() {
        return retryMaxDelayMillis;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    private static int normalizeConcurrency(int configuredConcurrency, int configuredMaxConcurrency) {
        int maxConcurrency = Math.min(HARD_MAX_CONCURRENCY, positive(configuredMaxConcurrency, Config.AIMaxConcurrency));
        return Math.max(1, Math.min(maxConcurrency, positive(configuredConcurrency, Config.AIConcurrency)));
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long positiveLong(long value, long fallback) {
        return value > 0L ? value : fallback;
    }

    public static final class Builder {
        private int concurrency = DEFAULT_CONCURRENCY;
        private int maxConcurrency = HARD_MAX_CONCURRENCY;
        private int maxInFlightChars = DEFAULT_MAX_IN_FLIGHT_CHARS;
        private int defaultMaxAttempts = DEFAULT_MAX_ATTEMPTS;
        private long leaseDurationMillis = DEFAULT_LEASE_DURATION_MILLIS;
        private long idlePollMillis = DEFAULT_IDLE_POLL_MILLIS;
        private long recoveryIntervalMillis = DEFAULT_RECOVERY_INTERVAL_MILLIS;
        private long retryBaseDelayMillis = DEFAULT_RETRY_BASE_DELAY_MILLIS;
        private long retryMaxDelayMillis = DEFAULT_RETRY_MAX_DELAY_MILLIS;
        private boolean autoStart = true;

        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }

        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public Builder maxInFlightChars(int maxInFlightChars) {
            this.maxInFlightChars = maxInFlightChars;
            return this;
        }

        public Builder defaultMaxAttempts(int defaultMaxAttempts) {
            this.defaultMaxAttempts = defaultMaxAttempts;
            return this;
        }

        public Builder leaseDurationMillis(long leaseDurationMillis) {
            this.leaseDurationMillis = leaseDurationMillis;
            return this;
        }

        public Builder idlePollMillis(long idlePollMillis) {
            this.idlePollMillis = idlePollMillis;
            return this;
        }

        public Builder recoveryIntervalMillis(long recoveryIntervalMillis) {
            this.recoveryIntervalMillis = recoveryIntervalMillis;
            return this;
        }

        public Builder retryBaseDelayMillis(long retryBaseDelayMillis) {
            this.retryBaseDelayMillis = retryBaseDelayMillis;
            return this;
        }

        public Builder retryMaxDelayMillis(long retryMaxDelayMillis) {
            this.retryMaxDelayMillis = retryMaxDelayMillis;
            return this;
        }

        public Builder autoStart(boolean autoStart) {
            this.autoStart = autoStart;
            return this;
        }

        public AiTriageWorkerConfig build() {
            return new AiTriageWorkerConfig(this);
        }
    }
}
