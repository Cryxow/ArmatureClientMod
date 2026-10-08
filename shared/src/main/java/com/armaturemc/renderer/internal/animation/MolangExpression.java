package com.armaturemc.renderer.internal.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Recursive-descent parser and evaluator for the Molang subset Blockbench exports.
 *
 * <p>Supported grammar, loosest binding first:
 * <ul>
 *   <li>{@code cond ? a : b} ternary</li>
 *   <li>{@code ||} and {@code &&}</li>
 *   <li>{@code == != < <= > >=}</li>
 *   <li>{@code + -}</li>
 *   <li>{@code * / %}</li>
 *   <li>unary {@code ! - +}</li>
 *   <li>literals, {@code ( )}, {@link #compile(String) named calls}, identifiers</li>
 * </ul>
 *
 * <p>Expressions are kept as text on the compiled keyframe and evaluated per
 * sample, so {@code math.random} and query-driven values stay dynamic instead of
 * being frozen at load time.
 */
public final class MolangExpression {
    private static final int MAX_PRECEDENCE_DEPTH = 64;

    private final String source;
    private final MolangContext context;
    private int index;
    private int depth;

    private MolangExpression(String source, MolangContext context) {
        this.source = source;
        this.context = context;
    }

    /**
     * Parses an expression and returns it bound to {@code context}. The caller
     * keeps the result and re-evaluates it against a fresh context per sample.
     */
    public static MolangExpression compile(String source, MolangContext context) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Molang expression cannot be empty");
        }
        String trimmed = source.trim();
        MolangExpression parser = new MolangExpression(trimmed, context);
        parser.ternary();
        parser.skipWhitespace();
        if (parser.index != trimmed.length()) {
            throw new IllegalArgumentException("Invalid Molang expression: " + source);
        }
        return parser;
    }

    /** Parses and immediately evaluates, for one-shot callers. */
    public static double evaluate(String source, MolangContext context) {
        return compile(source, context).evaluate();
    }

    public String source() {
        return source;
    }

    public double evaluate() {
        return evaluate(context);
    }

    /** Re-evaluates this expression against a different runtime context. */
    public double evaluate(MolangContext runtime) {
        if (runtime == null) return evaluate();
        MolangExpression parser = new MolangExpression(source, runtime);
        double value = parser.ternary();
        parser.skipWhitespace();
        if (parser.index != source.length()) {
            throw new IllegalArgumentException("Invalid Molang expression: " + source);
        }
        return value;
    }

    private double ternary() {
        double condition = logicalOr();
        skipWhitespace();
        if (!consume('?')) return condition;
        double whenTrue = ternary();
        skipWhitespace();
        if (!consume(':')) {
            throw new IllegalArgumentException("Missing ':' in ternary in: " + source);
        }
        double whenFalse = ternary();
        return condition != 0.0 ? whenTrue : whenFalse;
    }

    private double logicalOr() {
        double left = logicalAnd();
        while (true) {
            skipWhitespace();
            if (!consume("||")) return left;
            double right = logicalAnd();
            left = (left != 0.0 || right != 0.0) ? 1.0 : 0.0;
        }
    }

    private double logicalAnd() {
        double left = comparison();
        while (true) {
            skipWhitespace();
            if (!consume("&&")) return left;
            double right = comparison();
            left = (left != 0.0 && right != 0.0) ? 1.0 : 0.0;
        }
    }

    private double comparison() {
        double left = additive();
        while (true) {
            skipWhitespace();
            if (consume("==")) left = left == additive() ? 1.0 : 0.0;
            else if (consume("!=")) left = left != additive() ? 1.0 : 0.0;
            else if (consume("<=")) left = left <= additive() ? 1.0 : 0.0;
            else if (consume(">=")) left = left >= additive() ? 1.0 : 0.0;
            else if (consume('<')) left = left < additive() ? 1.0 : 0.0;
            else if (consume('>')) left = left > additive() ? 1.0 : 0.0;
            else return left;
        }
    }

    private double additive() {
        double value = multiplicative();
        while (true) {
            skipWhitespace();
            if (consume('+')) value += multiplicative();
            else if (consume('-')) value -= multiplicative();
            else return value;
        }
    }

    private double multiplicative() {
        double value = unary();
        while (true) {
            skipWhitespace();
            if (consume('*')) value *= unary();
            else if (consume('/')) value /= unary();
            else return value;
        }
    }

    private double unary() {
        skipWhitespace();
        if (consume('!')) return unary() == 0.0 ? 1.0 : 0.0;
        if (consume('-')) return -unary();
        if (consume('+')) return unary();
        return primary();
    }

    private double primary() {
        skipWhitespace();
        if (index >= source.length()) throw new IllegalArgumentException("Unexpected end of expression: " + source);
        char c = source.charAt(index);
        if (c == '(') {
            index++;
            double value = nested(() -> ternary());
            skipWhitespace();
            if (!consume(')')) throw new IllegalArgumentException("Missing ')' in expression: " + source);
            return value;
        }
        if (Character.isDigit(c) || c == '.') return number();
        if (Character.isLetter(c) || c == '_') return identifier();
        throw new IllegalArgumentException("Unexpected character '" + c + "' in expression: " + source);
    }

    private double identifier() {
        int start = index;
        while (index < source.length()) {
            char c = source.charAt(index);
            // ':' is deliberately excluded so a ternary written without spaces
            // such as "cond ? 1:2" splits into its branches.
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') break;
            index++;
        }
        String identifier = source.substring(start, index);
        if (identifier.isEmpty()) {
            throw new IllegalArgumentException("Unexpected character '" + source.charAt(index)
                + "' in expression: " + source);
        }

        skipWhitespace();
        if (index < source.length() && source.charAt(index) == '(') {
            index++;
            List<Double> arguments = new ArrayList<>(3);
            skipWhitespace();
            if (index < source.length() && source.charAt(index) == ')') {
                index++;
            } else {
                while (true) {
                    arguments.add(nested(this::ternary));
                    skipWhitespace();
                    if (consume(',')) continue;
                    if (!consume(')')) {
                        throw new IllegalArgumentException("Missing ')' in call to " + identifier
                            + " in expression: " + source);
                    }
                    break;
                }
            }
            return call(identifier, arguments);
        }

        if ("true".equals(identifier)) return 1.0;
        if ("false".equals(identifier)) return 0.0;

        // Bare namespace reads such as "query.yaw" or "variable.aim" carry no call.
        if (identifier.startsWith("query.") || identifier.startsWith("q.")) {
            return query(identifier.substring(identifier.indexOf('.') + 1), List.of());
        }
        if (identifier.startsWith("variable.") || identifier.startsWith("v.")
            || identifier.startsWith("temp.") || identifier.startsWith("t.")) {
            return variable(identifier.substring(identifier.indexOf('.') + 1), List.of());
        }

        // "math.foo(1, 2)" is the canonical form, but Blockbench also emits the
        // bare namespace as a 0-constant in some Bedrock-targeted exports.
        if ("math".equals(identifier) || "query".equals(identifier) || "variable".equals(identifier)
            || "q".equals(identifier) || "v".equals(identifier) || "t".equals(identifier)) {
            return 0.0;
        }
        throw new IllegalArgumentException("Unknown identifier '" + identifier + "' in expression: " + source);
    }

    private double call(String identifier, List<Double> arguments) {
        if (identifier.startsWith("math.")) {
            return applyMathFunction(identifier.substring(5), arguments);
        }
        if (identifier.startsWith("query.") || identifier.startsWith("q.")) {
            return query(identifier.substring(identifier.indexOf('.') + 1), arguments);
        }
        if (identifier.startsWith("variable.") || identifier.startsWith("v.")
            || identifier.startsWith("temp.") || identifier.startsWith("t.")) {
            return variable(identifier.substring(identifier.indexOf('.') + 1), arguments);
        }
        throw new IllegalArgumentException("Unknown function '" + identifier + "' in expression: " + source);
    }

    private double query(String name, List<Double> arguments) {
        return switch (name) {
            case "yaw" -> context.yaw();
            case "pitch" -> context.pitch();
            case "modified_distance_moved" -> context.modifiedDistanceMoved();
            case "modified_move_speed" -> context.modifiedMoveSpeed();
            case "anim_time" -> context.animTime();
            case "delta_time" -> context.deltaTime();
            case "game_time" -> context.gameTime();
            case "program_time" -> context.programTime();
            case "lifetime" -> context.lifetime();
            case "age" -> context.lifetime();
            case "is_moving" -> context.isMoving() ? 1.0 : 0.0;
            case "is_on_ground", "is_onground" -> context.isOnGround() ? 1.0 : 0.0;
            case "is_sneaking" -> context.isSneaking() ? 1.0 : 0.0;
            case "is_sprinting" -> context.isSprinting() ? 1.0 : 0.0;
            case "is_swimming" -> context.isSwimming() ? 1.0 : 0.0;
            case "first_person" -> 0.0;
            case "third_person" -> 1.0;
            case "health", "max_health" -> context.health();
            case "hurt_time" -> context.hurtTime();
            case "target_x", "target_y", "target_z" -> 0.0;
            case "view_x", "view_y", "view_z" -> 0.0;
            case "camera_x", "camera_y", "camera_z" -> 0.0;
            case "uuid", "player_uuid" -> 0.0;
            case "day_time" -> context.gameTime();
            case "moon_phase" -> 0.0;
            default -> throw new IllegalArgumentException("Unknown query '" + name + "' in expression: " + source);
        };
    }

    private double variable(String name, List<Double> arguments) {
        // A Molang variable that was never assigned reads as 0 rather than
        // aborting the model, which is what Bedrock does at runtime.
        DoubleSupplier value = context.variables().get(name);
        return value == null ? 0.0 : value.getAsDouble();
    }

    private double applyMathFunction(String function, List<Double> arguments) {
        int count = arguments.size();
        switch (function) {
            // Trigonometry is in degrees, matching Bedrock.
            case "sin" -> { return Math.sin(Math.toRadians(arg(arguments, 0, function))); }
            case "cos" -> { return Math.cos(Math.toRadians(arg(arguments, 0, function))); }
            case "tan" -> { return Math.tan(Math.toRadians(arg(arguments, 0, function))); }
            case "asin" -> { return Math.toDegrees(Math.asin(clamp(arg(arguments, 0, function), -1.0, 1.0))); }
            case "acos" -> { return Math.toDegrees(Math.acos(clamp(arg(arguments, 0, function), -1.0, 1.0))); }
            case "atan" -> { return Math.toDegrees(Math.atan(arg(arguments, 0, function))); }
            case "atan2" -> {
                return Math.toDegrees(Math.atan2(arg(arguments, 0, function), arg(arguments, 1, function)));
            }
            case "abs" -> { return Math.abs(arg(arguments, 0, function)); }
            case "sqrt" -> { return Math.sqrt(Math.max(0.0, arg(arguments, 0, function))); }
            case "floor" -> { return Math.floor(arg(arguments, 0, function)); }
            case "ceil" -> { return Math.ceil(arg(arguments, 0, function)); }
            case "round" -> { return roundHalfUp(arg(arguments, 0, function)); }
            case "trunc" -> { return (double) (long) arg(arguments, 0, function); }
            case "sign" -> { return Math.signum(arg(arguments, 0, function)); }
            case "exp" -> { return Math.exp(arg(arguments, 0, function)); }
            case "ln" -> { return Math.log(arg(arguments, 0, function)); }
            case "degrees" -> { return Math.toDegrees(arg(arguments, 0, function)); }
            case "radians" -> { return Math.toRadians(arg(arguments, 0, function)); }
            case "inversesqrt", "inv_sqrt" -> {
                return 1.0 / Math.sqrt(Math.max(1.0e-9, Math.abs(arg(arguments, 0, function))));
            }
            case "saturate" -> { return clamp(arg(arguments, 0, function), 0.0, 1.0); }
            case "lerp" -> {
                return lerp(arg(arguments, 0, function), arg(arguments, 1, function), arg(arguments, 2, function));
            }
            case "lerprotate" -> {
                return lerpAngle(arg(arguments, 0, function), arg(arguments, 1, function),
                    arg(arguments, 2, function));
            }
            case "lerpplace" -> {
                // (delta, space, a, b, t); the mode/space flags are ignored here
                // because the native renderer interpolates in one space.
                return lerp(arg(arguments, 2, function), arg(arguments, 3, function),
                    arg(arguments, 4, function));
            }
            case "pow" -> { return Math.pow(arg(arguments, 0, function), arg(arguments, 1, function)); }
            case "mod" -> {
                double divisor = arg(arguments, 1, function);
                return divisor == 0.0 ? 0.0 : arg(arguments, 0, function) % divisor;
            }
            case "min" -> {
                double minimum = Double.POSITIVE_INFINITY;
                for (double value : arguments) minimum = Math.min(minimum, value);
                return count == 0 ? 0.0 : minimum;
            }
            case "max" -> {
                double maximum = Double.NEGATIVE_INFINITY;
                for (double value : arguments) maximum = Math.max(maximum, value);
                return count == 0 ? 0.0 : maximum;
            }
            case "clamp" -> {
                if (count == 3) {
                    return clamp(arg(arguments, 0, function), arg(arguments, 1, function),
                        arg(arguments, 2, function));
                }
                return clamp(arg(arguments, 0, function), 0.0, arg(arguments, 1, function));
            }
            case "step_smooth" -> {
                double edge = arg(arguments, 1, function);
                if (edge == 0.0) return arg(arguments, 0, function) >= 0.0 ? 1.0 : 0.0;
                double t = clamp(arg(arguments, 0, function) / edge, 0.0, 1.0);
                return t * t * (3.0 - 2.0 * t);
            }
            case "smooth" -> {
                double a = arg(arguments, 0, function);
                double b = arg(arguments, 1, function);
                double t = clamp(arg(arguments, 2, function), 0.0, 1.0);
                return lerp(a, b, t * t * (3.0 - 2.0 * t));
            }
            case "hermite_blend" -> {
                double time = arg(arguments, 0, function);
                if (time <= 0.0) return 0.0;
                if (time >= 1.0) return 1.0;
                return time * time * (3.0 - 2.0 * time);
            }
            // The random family. Seeded through the context so a deterministic
            // context yields reproducible poses.
            case "random" -> {
                return random(arg(arguments, 0, function), arg(arguments, 1, function));
            }
            case "random_integer", "randomint" -> {
                double low = arg(arguments, 0, function);
                double high = arg(arguments, 1, function);
                double span = Math.floor(high) - Math.ceil(low) + 1.0;
                if (span <= 0.0) return low;
                return Math.floor(low) + Math.floor(context.random().getAsDouble() * span);
            }
            case "die_roll" -> {
                double sides = arg(arguments, 0, function);
                if (sides < 1.0) return 0.0;
                return Math.floor(context.random().getAsDouble() * Math.floor(sides)) + 1.0;
            }
            case "die_roll_integer", "roll_integer" -> {
                double low = arg(arguments, 0, function);
                double high = arg(arguments, 1, function);
                double span = Math.floor(high) - Math.ceil(low) + 1.0;
                if (span <= 0.0) return low;
                return Math.floor(low) + Math.floor(context.random().getAsDouble() * span);
            }
            default -> throw new IllegalArgumentException("Unknown math function: math." + function);
        }
    }

    private double random(double low, double high) {
        if (high <= low) return low;
        return low + context.random().getAsDouble() * (high - low);
    }

    private static double arg(List<Double> arguments, int position, String function) {
        if (position >= arguments.size()) {
            throw new IllegalArgumentException("math." + function + " expects at least "
                + (position + 1) + " argument(s)");
        }
        return arguments.get(position);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Interpolates degrees the short way around the circle, like {@code math.lerprotate}. */
    private static double lerpAngle(double a, double b, double t) {
        double delta = ((b - a) % 360.0 + 540.0) % 360.0 - 180.0;
        return a + delta * t;
    }

    private static double roundHalfUp(double value) {
        return value >= 0.0 ? Math.floor(value + 0.5) : Math.ceil(value - 0.5);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private double number() {
        int start = index;
        boolean digits = false;
        while (index < source.length() && Character.isDigit(source.charAt(index))) {
            index++;
            digits = true;
        }
        if (index < source.length() && source.charAt(index) == '.') {
            index++;
            while (index < source.length() && Character.isDigit(source.charAt(index))) {
                index++;
                digits = true;
            }
        }
        if (!digits) throw new IllegalArgumentException("Invalid number in expression: " + source);
        if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
            int exponentStart = index;
            index++;
            if (index < source.length() && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++;
            int digitsStart = index;
            while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            if (digitsStart == index) index = exponentStart;
        }
        try {
            return Double.parseDouble(source.substring(start, index));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid number in expression: " + source, exception);
        }
    }

    /** Bounds recursion so a hostile expression cannot exhaust the stack. */
    private double nested(java.util.function.DoubleSupplier body) {
        if (++depth > MAX_PRECEDENCE_DEPTH) {
            throw new IllegalArgumentException("Molang expression is nested too deeply: " + source);
        }
        try {
            return body.getAsDouble();
        } finally {
            depth--;
        }
    }

    private boolean consume(char expected) {
        if (index >= source.length() || source.charAt(index) != expected) return false;
        index++;
        return true;
    }

    private boolean consume(String expected) {
        if (!source.startsWith(expected, index)) return false;
        // "!=" must not be mistaken for a leading unary "!" during comparison.
        if (expected.length() == 1 && index + 1 < source.length()
            && (source.charAt(index + 1) == '=' || source.charAt(index + 1) == '&')) {
            return false;
        }
        index += expected.length();
        return true;
    }

    private void skipWhitespace() {
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
    }
}