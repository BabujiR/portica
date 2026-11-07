package com.portica.common.policy.condition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses Portica's condition language (Apigee-style).
 * <pre>
 *   request.verb == "GET" and (proxy.pathsuffix ~ "/pets/**" or request.header.x-debug)
 *   response.status.code >= 500
 *   not (client.ip =| "10.")
 *   request.header.user-agent ~~ "(?i).*bot.*"
 * </pre>
 * Operators: {@code ==}, {@code !=}, {@code =|} (starts with), {@code ~} (wildcard: {@code *} within a path
 * segment, {@code **} across segments), {@code ~~} (Java regex, full match), {@code < <= > >=} (numeric);
 * logic: {@code and}/{@code &&}, {@code or}/{@code ||}, {@code not}/{@code !}, parentheses.
 * A bare variable is true when it is set and not empty, {@code "false"} or {@code 0}.
 * Variable names are case-sensitive except header names. String literals use double quotes.
 */
public final class ConditionParser {

    private static final int MAX_LENGTH = 4096;
    private static final int MAX_DEPTH = 32;

    private final String text;
    private final List<Token> tokens;
    private final Set<String> variables = new HashSet<>();
    private int pos;
    private int depth;

    private ConditionParser(String text) {
        this.text = text;
        this.tokens = tokenize(text);
    }

    /** Parses a condition; null or blank means "always true". */
    public static Condition parse(String text) {
        if (text == null || text.isBlank()) {
            return Condition.ALWAYS;
        }
        if (text.length() > MAX_LENGTH) {
            throw new ConditionSyntaxException("Condition is longer than " + MAX_LENGTH + " characters", "", 0);
        }
        ConditionParser parser = new ConditionParser(text);
        Node root = parser.parseOr();
        if (parser.peek().type != Type.EOF) {
            throw parser.error("Unexpected '" + parser.peek().text + "'");
        }
        return new CompiledCondition(root, Set.copyOf(parser.variables), text.strip());
    }

    // ------------------------------------------------------------------ grammar

    private Node parseOr() {
        Node left = parseAnd();
        while (peek().type == Type.OR) {
            pos++;
            Node l = left;
            Node r = parseAnd();
            left = v -> l.eval(v) || r.eval(v);
        }
        return left;
    }

    private Node parseAnd() {
        Node left = parseUnary();
        while (peek().type == Type.AND) {
            pos++;
            Node l = left;
            Node r = parseUnary();
            left = v -> l.eval(v) && r.eval(v);
        }
        return left;
    }

    private Node parseUnary() {
        if (peek().type == Type.NOT) {
            pos++;
            Node inner = parseUnary();
            return v -> !inner.eval(v);
        }
        return parsePrimary();
    }

    private Node parsePrimary() {
        Token t = peek();
        if (t.type == Type.LPAREN) {
            if (++depth > MAX_DEPTH) {
                throw error("Condition is nested too deeply");
            }
            pos++;
            Node inner = parseOr();
            expect(Type.RPAREN, "Expected ')'");
            depth--;
            return inner;
        }
        Operand left = parseOperand();
        if (peek().type != Type.OP) {
            return v -> Values.truthy(left.value(v));
        }
        String op = tokens.get(pos++).text;
        Operand right = parseOperand();
        return comparison(left, op, right);
    }

    private Operand parseOperand() {
        Token t = tokens.get(pos);
        switch (t.type) {
            case IDENT -> {
                pos++;
                variables.add(t.text);
                String name = t.text;
                return new Operand(v -> v.resolve(name), null, false);
            }
            case STRING -> {
                pos++;
                return Operand.literal(t.text);
            }
            case NUMBER -> {
                pos++;
                return Operand.literal(Double.valueOf(t.text));
            }
            case TRUE -> {
                pos++;
                return Operand.literal(Boolean.TRUE);
            }
            case FALSE -> {
                pos++;
                return Operand.literal(Boolean.FALSE);
            }
            case NULL -> {
                pos++;
                return new Operand(v -> null, null, true);
            }
            default -> throw error(t.type == Type.EOF ? "Unexpected end of condition" : "Unexpected '" + t.text + "'");
        }
    }

    private Node comparison(Operand left, String op, Operand right) {
        switch (op) {
            case "==" -> {
                return v -> Values.equal(left.value(v), right.value(v));
            }
            case "!=" -> {
                return v -> !Values.equal(left.value(v), right.value(v));
            }
            case "=|" -> {
                return v -> {
                    Object l = left.value(v);
                    Object r = right.value(v);
                    return l != null && r != null && Values.string(l).startsWith(Values.string(r));
                };
            }
            case "~", "~~" -> {
                boolean wildcard = op.equals("~");
                Pattern constant = null;
                if (right.isConstant()) {
                    try {
                        String p = Values.string(right.constant());
                        constant = wildcard ? WildcardPattern.compile(p) : Pattern.compile(p);
                    } catch (PatternSyntaxException e) {
                        throw error("Invalid regular expression: " + e.getDescription());
                    }
                }
                Pattern fixed = constant;
                return v -> {
                    Object l = left.value(v);
                    Object r = right.value(v);
                    if (l == null || r == null) {
                        return false;
                    }
                    Pattern p = fixed != null ? fixed
                            : (wildcard ? WildcardPattern.compile(Values.string(r)) : Pattern.compile(Values.string(r)));
                    return p.matcher(Values.string(l)).matches();
                };
            }
            case "<", "<=", ">", ">=" -> {
                return v -> {
                    Double l = Values.number(left.value(v));
                    Double r = Values.number(right.value(v));
                    if (l == null || r == null) {
                        return false;
                    }
                    int c = Double.compare(l, r);
                    return switch (op) {
                        case "<" -> c < 0;
                        case "<=" -> c <= 0;
                        case ">" -> c > 0;
                        default -> c >= 0;
                    };
                };
            }
            default -> throw error("Unknown operator '" + op + "'");
        }
    }

    // ------------------------------------------------------------------ tokens

    private enum Type { LPAREN, RPAREN, IDENT, STRING, NUMBER, OP, AND, OR, NOT, TRUE, FALSE, NULL, EOF }

    private record Token(Type type, String text, int position) {
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private void expect(Type type, String message) {
        if (peek().type != type) {
            throw error(message);
        }
        pos++;
    }

    private ConditionSyntaxException error(String message) {
        return new ConditionSyntaxException(message, text, peek().position);
    }

    private static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            if (c == '(') {
                out.add(new Token(Type.LPAREN, "(", i++));
            } else if (c == ')') {
                out.add(new Token(Type.RPAREN, ")", i++));
            } else if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < s.length()) {
                    char ch = s.charAt(i++);
                    if (ch == '\\' && i < s.length()) {
                        sb.append(s.charAt(i++));
                    } else if (ch == '"') {
                        closed = true;
                        break;
                    } else {
                        sb.append(ch);
                    }
                }
                if (!closed) {
                    throw new ConditionSyntaxException("Unterminated string", s, start);
                }
                out.add(new Token(Type.STRING, sb.toString(), start));
            } else if (s.startsWith("&&", i)) {
                out.add(new Token(Type.AND, "&&", i));
                i += 2;
            } else if (s.startsWith("||", i)) {
                out.add(new Token(Type.OR, "||", i));
                i += 2;
            } else if (s.startsWith("==", i) || s.startsWith("!=", i) || s.startsWith("=|", i)
                    || s.startsWith("~~", i) || s.startsWith("<=", i) || s.startsWith(">=", i)) {
                out.add(new Token(Type.OP, s.substring(i, i + 2), i));
                i += 2;
            } else if (c == '!') {
                out.add(new Token(Type.NOT, "!", i++));
            } else if (c == '~' || c == '<' || c == '>') {
                out.add(new Token(Type.OP, String.valueOf(c), i++));
            } else if (Character.isDigit(c) || (c == '-' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                i++;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                String num = s.substring(start, i);
                try {
                    Double.parseDouble(num);
                } catch (NumberFormatException e) {
                    throw new ConditionSyntaxException("Invalid number '" + num + "'", s, start);
                }
                out.add(new Token(Type.NUMBER, num, start));
            } else if (Character.isLetter(c) || c == '_') {
                i++;
                while (i < s.length() && isIdentifierPart(s.charAt(i))) {
                    i++;
                }
                String word = s.substring(start, i);
                out.add(new Token(keyword(word), word, start));
            } else {
                throw new ConditionSyntaxException("Unexpected character '" + c + "'", s, i);
            }
        }
        out.add(new Token(Type.EOF, "", s.length()));
        return out;
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    private static Type keyword(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "and" -> Type.AND;
            case "or" -> Type.OR;
            case "not" -> Type.NOT;
            case "true" -> Type.TRUE;
            case "false" -> Type.FALSE;
            case "null" -> Type.NULL;
            default -> Type.IDENT;
        };
    }

    // ------------------------------------------------------------------ evaluation

    @FunctionalInterface
    private interface Node {
        boolean eval(VariableResolver v);
    }

    private record Operand(java.util.function.Function<VariableResolver, Object> getter, Object constant,
                           boolean isConstant) {

        static Operand literal(Object value) {
            return new Operand(v -> value, value, true);
        }

        Object value(VariableResolver v) {
            return getter.apply(v);
        }
    }

    private record CompiledCondition(Node root, Set<String> variables, String source) implements Condition {

        @Override
        public boolean test(VariableResolver resolver) {
            try {
                return root.eval(resolver == null ? VariableResolver.EMPTY : resolver);
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public String toString() {
            return source;
        }
    }

    /** Comparison and truthiness rules. */
    static final class Values {

        private Values() {
        }

        static boolean truthy(Object v) {
            if (v == null) {
                return false;
            }
            if (v instanceof Boolean b) {
                return b;
            }
            if (v instanceof Number n) {
                return n.doubleValue() != 0;
            }
            String s = v.toString();
            return !s.isEmpty() && !s.equalsIgnoreCase("false") && !s.equals("0");
        }

        static boolean equal(Object l, Object r) {
            if (l == null || r == null) {
                return l == null && r == null;
            }
            if (l instanceof Number || r instanceof Number) {
                Double a = number(l);
                Double b = number(r);
                if (a != null && b != null) {
                    return a.doubleValue() == b.doubleValue();
                }
            }
            if (l instanceof Boolean || r instanceof Boolean) {
                return string(l).equalsIgnoreCase(string(r));
            }
            return string(l).equals(string(r));
        }

        static Double number(Object v) {
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v == null) {
                return null;
            }
            try {
                return Double.parseDouble(v.toString().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        static String string(Object v) {
            if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
                return Long.toString(d.longValue());
            }
            return String.valueOf(v);
        }
    }
}
