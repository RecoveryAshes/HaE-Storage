package hae.ai.worker;

@FunctionalInterface
public interface AiTriageWorkerClock {
    long nowMillis();
}
