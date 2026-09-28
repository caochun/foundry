package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Parses the upstream function-call language; no scripts, reflection or dynamic evaluation. */
final class MappingTransforms {
    private static final Pattern CALL = Pattern.compile("([A-Za-z_]\\w*)\\((.*)\\)", Pattern.DOTALL);
    private static final Pattern INTEGER = Pattern.compile("^[+-]?[0-9]+");
    private static final Pattern FLOAT = Pattern.compile("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private MappingTransforms() {}

    static void validate(String expression) {
        if (expression == null) return;
        var call = CALL.matcher(expression.trim());
        if (!call.matches()) throw new IllegalArgumentException("Invalid transform expression");
        if (call.group(1).equals("custom")) {
            var arguments = split(call.group(2));
            arity(arguments, 1);
            if (text(arguments.getFirst()).isBlank()) throw new IllegalArgumentException("Custom function name is empty");
        } else compile(expression, Map.of());
    }

    static TransformRegistry.Compiled compile(String expression, Map<String, TransformRegistry.Registered> functions) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("Transform expression is required");
        var call = CALL.matcher(expression.trim());
        if (!call.matches()) throw new IllegalArgumentException("Invalid transform expression");
        String name = call.group(1);
        var arguments = split(call.group(2));
        if (name.equals("custom")) {
            arity(arguments, 1);
            String key = text(arguments.getFirst());
            var function = functions.get(key);
            if (function == null) throw new IllegalArgumentException("Custom transform is not registered: " + key);
            return new TransformRegistry.Compiled(function.transform(), Map.of(key, function.version()));
        }
        TransformRegistry.Transform transform = switch (name) {
            case "concat" -> {
                var parts = arguments.stream().map(MappingTransforms::argument).toList();
                yield (value, record) -> parts.stream().map(part -> part.literal ? part.value : record.get(part.value))
                        .map(item -> item == null ? "" : scalarText(item)).collect(java.util.stream.Collectors.joining());
            }
            case "prefix", "suffix" -> {
                arity(arguments, 1);
                String affix = text(arguments.getFirst());
                yield (value, record) -> value == null ? null : name.equals("prefix") ? affix + scalarText(value) : scalarText(value) + affix;
            }
            case "toUpper", "toLower", "trim" -> {
                arity(arguments, 0);
                yield (value, record) -> value == null ? null : switch (name) {
                    case "toUpper" -> scalarText(value).toUpperCase(Locale.ROOT);
                    case "toLower" -> scalarText(value).toLowerCase(Locale.ROOT);
                    default -> trim(scalarText(value));
                };
            }
            case "parseInt", "parseFloat" -> {
                arity(arguments, 0);
                yield (value, record) -> number(value, name.equals("parseInt"));
            }
            case "ifPresent" -> {
                arity(arguments, 2);
                String yes = text(arguments.getFirst());
                String no = text(arguments.get(1));
                yield (value, record) -> value == null ? no : yes;
            }
            case "coalesce" -> {
                arity(arguments, 1);
                String fallback = text(arguments.getFirst());
                yield (value, record) -> value == null ? fallback : value;
            }
            case "map" -> {
                arity(arguments, 1);
                var lookup = lookup(arguments.getFirst());
                yield (value, record) -> value == null ? null : lookup.get(scalarText(value));
            }
            case "parseDate", "parseDateTime" -> {
                arity(arguments, 1);
                var format = new DateFormat(text(arguments.getFirst()), name.equals("parseDateTime"));
                yield (value, record) -> value == null ? null : format.parse(scalarText(value));
            }
            default -> throw new IllegalArgumentException("Unknown transform function: " + name);
        };
        return new TransformRegistry.Compiled(transform, Map.of());
    }

    private static Object number(Object value, boolean integer) {
        if (value == null) return null;
        var match = (integer ? INTEGER : FLOAT).matcher(trim(scalarText(value)));
        if (!match.find()) return null;
        if (integer) {
            var parsed = new BigInteger(match.group());
            if (parsed.bitLength() < 32) return parsed.intValue();
            if (parsed.bitLength() < 64) return parsed.longValue();
            if (!Double.isFinite(parsed.doubleValue())) return null;
            return parsed;
        }
        double parsed = Double.parseDouble(match.group());
        return Double.isFinite(parsed) ? parsed : null;
    }

    private static String scalarText(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        throw new IllegalArgumentException("Text transforms require a scalar source value");
    }
    private static String trim(String value) { return value.replaceAll("^[\\x09-\\x0d\\x20\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+|[\\x09-\\x0d\\x20\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]+$", ""); }
    private static void arity(List<String> values, int expected) { if (values.size() != expected) throw new IllegalArgumentException("Wrong number of transform arguments"); }
    private record Argument(String value, boolean literal) {}
    private static Argument argument(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Empty transform argument");
        if (value.charAt(0) == '\'' || value.charAt(0) == '"') return new Argument(unquote(value), true);
        if (value.chars().anyMatch(character -> "'\"(){}".indexOf(character) >= 0)) throw new IllegalArgumentException("Invalid transform field argument");
        return new Argument(value, false);
    }
    private static String text(String raw) { return argument(raw).value; }

    private static String unquote(String value) {
        if (value.isEmpty() || value.charAt(0) != '\'' && value.charAt(0) != '"') throw new IllegalArgumentException("Expected quoted string");
        char quote = value.charAt(0);
        if (value.length() < 2 || value.charAt(value.length() - 1) != quote) throw new IllegalArgumentException("Unterminated string argument");
        var result = new StringBuilder();
        for (int index = 1; index < value.length() - 1; index++) {
            char ch = value.charAt(index);
            if (ch == quote) throw new IllegalArgumentException("Unexpected quote in transform string");
            if (ch == '\\') {
                if (++index >= value.length() - 1) throw new IllegalArgumentException("Unterminated string escape");
                ch = value.charAt(index);
                result.append(switch (ch) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case '\\', '\'', '"' -> ch;
                    default -> throw new IllegalArgumentException("Unknown string escape"); });
            } else result.append(ch);
        }
        return result.toString();
    }

    private static List<String> split(String source) {
        var result = new ArrayList<String>();
        if (source.isBlank()) return result;
        int start = 0;
        int braces = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char ch = source.charAt(index);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '\'' || ch == '"') quote = ch;
            else if (ch == '{') braces++;
            else if (ch == '}' && --braces < 0) throw new IllegalArgumentException("Unbalanced map argument");
            else if (ch == '(' || ch == ')') throw new IllegalArgumentException("Nested transform calls are not part of this language");
            else if (ch == ',' && braces == 0) {
                result.add(source.substring(start, index).trim());
                start = index + 1;
            }
        }
        if (quote != 0 || escaped || braces != 0) throw new IllegalArgumentException("Unterminated transform argument");
        result.add(source.substring(start).trim());
        if (result.stream().anyMatch(String::isEmpty)) throw new IllegalArgumentException("Empty transform argument");
        return result;
    }

    private static Map<String, String> lookup(String input) {
        if (!input.startsWith("{") || !input.endsWith("}")) throw new IllegalArgumentException("map requires an object argument");
        var result = new LinkedHashMap<String, String>();
        for (String pair : split(input.substring(1, input.length() - 1))) {
            int separator = -1;
            char quote = 0;
            boolean escaped = false;
            for (int i = 0; i < pair.length(); i++) {
                char ch = pair.charAt(i);
                if (quote != 0) {
                    if (escaped) escaped = false;
                    else if (ch == '\\') escaped = true;
                    else if (ch == quote) quote = 0;
                } else if (ch == '\'' || ch == '"') quote = ch;
                else if (ch == ':') { separator = i; break; }
            }
            if (separator < 0) throw new IllegalArgumentException("Invalid map entry");
            String key = unquote(pair.substring(0, separator).trim());
            String value = unquote(pair.substring(separator + 1).trim());
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate map key");
        }
        return Map.copyOf(result);
    }

    private static final class DateFormat {
        private final Pattern pattern;
        private final List<String> tokens = new ArrayList<>();
        private final boolean time;
        DateFormat(String format, boolean time) {
            this.time = time;
            var regex = new StringBuilder("^");
            for (int index = 0; index < format.length();) {
                int offset = index;
                String token = List.of("yyyy", "MM", "dd", "HH", "mm", "ss").stream().filter(candidate -> format.startsWith(candidate, offset)).findFirst().orElse(null);
                if (token == null) regex.append(Pattern.quote(format.substring(index, ++index)));
                else {
                    if (tokens.contains(token) || !time && Set.of("HH", "mm", "ss").contains(token)) throw new IllegalArgumentException("Invalid date format token");
                    tokens.add(token);
                    regex.append("([0-9]{").append(token.length()).append("})");
                    index += token.length();
                }
            }
            if (tokens.isEmpty()) throw new IllegalArgumentException("Date format requires date tokens");
            pattern = Pattern.compile(regex.append('$').toString());
        }
        String parse(String value) {
            var match = pattern.matcher(value);
            if (!match.matches()) throw new IllegalArgumentException("Value does not match date format");
            var values = new HashMap<String, Integer>();
            for (int index = 0; index < tokens.size(); index++) values.put(tokens.get(index), Integer.parseInt(match.group(index + 1)));
            var date = LocalDate.of(values.getOrDefault("yyyy", 0), values.getOrDefault("MM", 1), values.getOrDefault("dd", 1));
            return time ? date.atTime(values.getOrDefault("HH", 0), values.getOrDefault("mm", 0), values.getOrDefault("ss", 0)).toInstant(ZoneOffset.UTC).toString() : date.toString();
        }
    }
}
