package hae.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class AiWhitelistRule {
    private final String group;
    private final List<String> names;

    public AiWhitelistRule(String group, List<String> names) {
        this.group = Objects.requireNonNullElse(group, "");
        this.names = List.copyOf(Objects.requireNonNullElseGet(names, Collections::emptyList));
    }

    public String getGroup() {
        return group;
    }

    public List<String> getNames() {
        return names;
    }

    public static AiWhitelistRule fromObject(Object value) {
        if (!(value instanceof java.util.Map<?, ?> fields)) {
            return new AiWhitelistRule("", Collections.emptyList());
        }

        Object groupValue = fields.get("group");
        String group = groupValue == null ? "" : groupValue.toString();
        Object namesValue = fields.get("names");
        if (!(namesValue instanceof List<?> rawNames)) {
            return new AiWhitelistRule(group, Collections.emptyList());
        }

        List<String> parsedNames = new ArrayList<>();
        for (Object rawName : rawNames) {
            if (rawName != null) {
                parsedNames.add(rawName.toString());
            }
        }
        return new AiWhitelistRule(group, parsedNames);
    }
}
