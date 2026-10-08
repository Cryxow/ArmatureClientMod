package com.armaturemc.renderer.internal.animation;

import com.google.gson.JsonElement;

/**
 * Parses Blockbench numeric values, including empty and JSON-null axes,
 * constant arithmetic, and Molang expressions.
 */
public final class BlockbenchNumericParser {
    private BlockbenchNumericParser() {
    }

    /**
     * Converts a JSON number, numeric string, or constant arithmetic expression to a
     * finite value. Missing, empty, and JSON-null axes are the Blockbench zero value.
     */
    public static double parse(JsonElement element, String field) {
        return parse(element, field, null).constant();
    }

    /**
     * Parses one authored axis, retaining its Molang source when the data point is an
     * expression.
     *
     * @return the parsed axis; {@code molang} is non-null only when the source was an
     *         expression that must be evaluated per sample
     */
    public static Axis parse(JsonElement element, String field, MolangContext context) {
        if (element == null || element.isJsonNull()) return Axis.constant(0.0);
        if (element.isJsonObject() || element.isJsonArray()) {
            throw new NumberFormatException("Not a numeric value: " + field);
        }
        String text = element.getAsString().trim();
        if (text.isEmpty() || text.equalsIgnoreCase("null")) return Axis.constant(0.0);
        try {
            return Axis.constant(Double.parseDouble(text));
        } catch (NumberFormatException ignored) {
            // Not a plain number: either constant arithmetic such as "-2.5-90",
            // or a Molang expression such as "math.random(0, 2)".
        }
        try {
            return Axis.constant(ConstantExpression.parse(text));
        } catch (NumberFormatException ignored) {
            // Not constant arithmetic either, so it must be Molang.
        }
        MolangContext evaluation = context == null ? MolangContext.defaults() : context;
        try {
            return new Axis(0.0, MolangExpression.compile(text, evaluation));
        } catch (RuntimeException exception) {
            throw new MolangExpressionParseException(text, "Unsupported expression", exception);
        }
    }

    /**
     * Parses an axis with a runtime context and returns only the resolved number.
     * Callers that need to keep the expression should use {@link #parse(JsonElement, String, MolangContext)}.
     */
    public static double parseWithMolangContext(JsonElement element, String field, MolangContext context) {
        return parse(element, field, context).resolve(context);
    }

    /** One authored axis: either a constant, or a Molang expression plus its fallback. */
    public record Axis(double constant, MolangExpression molang) {
        public Axis {
            if (molang == null && !Double.isFinite(constant)) {
                throw new NumberFormatException("Non-finite value: " + constant);
            }
        }

        public static Axis constant(double value) {
            return new Axis(value, null);
        }

        public boolean isExpression() {
            return molang != null;
        }

        /** Resolves against {@code context}, falling back to the compile-time constant. */
        public double resolve(MolangContext context) {
            if (molang == null) return constant;
            try {
                double value = molang.evaluate(context == null ? MolangContext.defaults() : context);
                return Double.isFinite(value) ? value : constant;
            } catch (RuntimeException exception) {
                return constant;
            }
        }
    }

    /**
     * Exception thrown when a data point is neither numeric nor a supported expression.
     */
    public static final class MolangExpressionParseException extends RuntimeException {
        public MolangExpressionParseException(String expression, String message) {
            super(message + ": " + expression);
        }

        public MolangExpressionParseException(String expression, String message, Throwable cause) {
            super(message + ": " + expression, cause);
        }
    }

    /** Evaluates constant arithmetic exported by Blockbench, such as {@code -2.5-90}. */
    private static final class ConstantExpression {
        private final String source;
        private int index;

        private ConstantExpression(String source) {
            this.source = source;
        }

        private static double parse(String source) {
            ConstantExpression parser = new ConstantExpression(source);
            double value = parser.expression();
            parser.skipWhitespace();
            if (parser.index != source.length() || !Double.isFinite(value)) {
                throw new NumberFormatException("Not a finite constant expression: " + source);
            }
            return value;
        }

        private double expression() {
            double value = term();
            while (true) {
                skipWhitespace();
                if (consume('+')) value += term();
                else if (consume('-')) value -= term();
                else return value;
            }
        }

        private double term() {
            double value = factor();
            while (true) {
                skipWhitespace();
                if (consume('*')) value *= factor();
                else if (consume('/')) value /= factor();
                else return value;
            }
        }

        private double factor() {
            skipWhitespace();
            if (consume('+')) return factor();
            if (consume('-')) return -factor();
            if (consume('(')) {
                double value = expression();
                skipWhitespace();
                if (!consume(')')) throw invalid();
                return value;
            }
            return number();
        }

        private double number() {
            skipWhitespace();
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
            if (!digits) throw invalid();
            if (index < source.length() && (source.charAt(index) == 'e'
                    || source.charAt(index) == 'E')) {
                int exponent = index++;
                if (index < source.length() && (source.charAt(index) == '+'
                        || source.charAt(index) == '-')) index++;
                int exponentStart = index;
                while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
                if (exponentStart == index) {
                    index = exponent;
                    throw invalid();
                }
            }
            try {
                return Double.parseDouble(source.substring(start, index));
            } catch (NumberFormatException exception) {
                throw invalid();
            }
        }

        private boolean consume(char expected) {
            if (index >= source.length() || source.charAt(index) != expected) return false;
            index++;
            return true;
        }

        private void skipWhitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        }

        private NumberFormatException invalid() {
            return new NumberFormatException("Invalid constant expression: " + source);
        }
    }
}
