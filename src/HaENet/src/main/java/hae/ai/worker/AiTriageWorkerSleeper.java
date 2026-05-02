package hae.ai.worker;

@FunctionalInterface
public interface AiTriageWorkerSleeper {
    void sleepMillis(long millis) throws InterruptedException;
}
