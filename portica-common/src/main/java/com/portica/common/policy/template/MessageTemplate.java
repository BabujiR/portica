package com.portica.common.policy.template;

import com.portica.common.policy.condition.VariableResolver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text with {@code {variable.name}} references, e.g. {@code "Hello {request.header.name}"}.
 * <p>
 * Only braces containing a plain variable name are substitutions, so JSON can be written
 * naturally: in {@code {"id":"{request.queryparam.id}"}} the outer braces stay literal.
 * Unset variables render as an empty string. Values are inserted as-is (not escaped).
 */
public final class MessageTemplate {

    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.\\-]*)}");

    private final String source;
    private final List<Object> parts; // String literal or Var
    private final Set<String> variables;

    private record Var(String name) {
    }

    private MessageTemplate(String source) {
        this.source = source;
        List<Object> p = new ArrayList<>();
        Set<String> vars = new LinkedHashSet<>();
        Matcher m = VARIABLE.matcher(source);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                p.add(source.substring(last, m.start()));
            }
            p.add(new Var(m.group(1)));
            vars.add(m.group(1));
            last = m.end();
        }
        if (last < source.length()) {
            p.add(source.substring(last));
        }
        this.parts = List.copyOf(p);
        this.variables = Set.copyOf(vars);
    }

    public static MessageTemplate of(String source) {
        return new MessageTemplate(source == null ? "" : source);
    }

    public String render(VariableResolver resolver) {
        if (variables.isEmpty()) {
            return source;
        }
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof Var v) {
                Object value = resolver.resolve(v.name());
                if (value != null) {
                    sb.append(value);
                }
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    public boolean isConstant() {
        return variables.isEmpty();
    }

    public Set<String> variables() {
        return variables;
    }

    public boolean references(String variablePrefix) {
        return variables.stream().anyMatch(v -> v.startsWith(variablePrefix));
    }

    @Override
    public String toString() {
        return source;
    }
}
