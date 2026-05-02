package hae.ai;

import java.util.List;
import java.util.Locale;

public final class AiWhitelistRules {
    private AiWhitelistRules() {
    }

    public static boolean allowsRule(List<AiWhitelistRule> whitelist, String ruleName) {
        String normalizedRuleName = normalize(ruleName);
        if (normalizedRuleName.isEmpty() || whitelist == null || whitelist.isEmpty()) {
            return false;
        }
        for (AiWhitelistRule rule : whitelist) {
            if (rule == null || rule.getNames() == null) {
                continue;
            }
            for (String name : rule.getNames()) {
                if (normalizedRuleName.equals(normalize(name))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
