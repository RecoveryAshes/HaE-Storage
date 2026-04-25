package hae;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NegativeScopeRegressionTest {
    private static final Path PRODUCTION_ROOT = Path.of("src", "main");
    private static final List<ForbiddenPattern> FORBIDDEN_PATTERNS = List.of(
            new ForbiddenPattern("ValidatorService", Pattern.compile("ValidatorService")),
            new ForbiddenPattern("RuleTester", Pattern.compile("RuleTester")),
            new ForbiddenPattern("Severity", Pattern.compile("Severity")),
            new ForbiddenPattern("V-Timeout", Pattern.compile("V-Timeout")),
            new ForbiddenPattern("V-Bulk", Pattern.compile("V-Bulk")),
            new ForbiddenPattern("ProcessBuilder", Pattern.compile("ProcessBuilder")),
            new ForbiddenPattern("Runtime.getRuntime().exec", Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec"))
    );

    @Test
    void productionCodeAndResourcesExcludeOutOfScopeUpstreamFeatures() throws IOException {
        List<String> matches;
        try (Stream<Path> paths = Files.walk(PRODUCTION_ROOT)) {
            matches = paths
                    .filter(Files::isRegularFile)
                    .filter(NegativeScopeRegressionTest::isScannableProductionFile)
                    .flatMap(NegativeScopeRegressionTest::forbiddenMatches)
                    .toList();
        }

        assertTrue(matches.isEmpty(), () -> "Forbidden production scope matches:\n" + String.join("\n", matches));
    }

    private static boolean isScannableProductionFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".java") || fileName.endsWith(".yml") || fileName.endsWith(".yaml")
                || fileName.endsWith(".properties") || fileName.endsWith(".json") || fileName.endsWith(".xml")
                || fileName.endsWith(".txt") || fileName.endsWith(".md");
    }

    private static Stream<String> forbiddenMatches(Path path) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            Stream.Builder<String> matches = Stream.builder();
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                String line = lines.get(lineIndex);
                for (ForbiddenPattern forbiddenPattern : FORBIDDEN_PATTERNS) {
                    if (forbiddenPattern.pattern().matcher(line).find()) {
                        matches.add(path + ":" + (lineIndex + 1) + " [" + forbiddenPattern.name() + "] " + line.trim());
                    }
                }
            }
            return matches.build();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to scan production file: " + path, e);
        }
    }

    private record ForbiddenPattern(String name, Pattern pattern) {
    }
}
