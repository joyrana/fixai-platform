package com.fixai.platform.certification.domain.evaluation;

import com.fixai.platform.fixcore.FixMessageView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, side-effect-free arithmetic relation over message fields, e.g. {@code CumQty + LeavesQty == OrderQty}.
 * Grammar: {@code sum OP sum} where {@code sum := term (('+'|'-') term)*}, {@code term := number | FieldName}.
 * Deliberately not a general expression language: no functions, no variables, no evaluation of message content as code.
 */
public final class Invariant {

    private enum Op {
        EQ("=="), NE("!="), LE("<="), GE(">="), LT("<"), GT(">");

        final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }
    }

    private record Term(int sign, String operand) {
    }

    private final String text;
    private final List<Term> left;
    private final Op op;
    private final List<Term> right;

    private Invariant(String text, List<Term> left, Op op, List<Term> right) {
        this.text = text;
        this.left = left;
        this.op = op;
        this.right = right;
    }

    public static Invariant parse(String text) {
        for (Op candidate : Op.values()) {
            int index = text.indexOf(candidate.symbol);
            if (index > 0) {
                return new Invariant(text, sum(text.substring(0, index)), candidate,
                        sum(text.substring(index + candidate.symbol.length())));
            }
        }
        throw new IllegalArgumentException("Invariant needs one of == != <= >= < >: " + text);
    }

    private static List<Term> sum(String expression) {
        List<Term> terms = new ArrayList<>();
        String normalised = expression.replace("-", " - ").replace("+", " + ").trim();
        int sign = 1;
        boolean expectOperand = true;
        for (String token : normalised.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.equals("+") || token.equals("-")) {
                if (expectOperand) {
                    throw new IllegalArgumentException("Unexpected operator in invariant: " + expression);
                }
                sign = token.equals("+") ? 1 : -1;
                expectOperand = true;
            } else {
                if (!token.matches("[A-Za-z][A-Za-z0-9]*|\\d+(\\.\\d+)?")) {
                    throw new IllegalArgumentException("Invalid operand '" + token + "' in invariant: " + expression);
                }
                terms.add(new Term(sign, token));
                expectOperand = false;
            }
        }
        if (terms.isEmpty() || expectOperand) {
            throw new IllegalArgumentException("Incomplete invariant expression: " + expression);
        }
        return terms;
    }

    public String text() {
        return text;
    }

    public AssertionResult evaluate(FixMessageView message, Long ordinal) {
        try {
            BigDecimal l = value(left, message);
            BigDecimal r = value(right, message);
            int cmp = l.compareTo(r);
            boolean passed = switch (op) {
                case EQ -> cmp == 0;
                case NE -> cmp != 0;
                case LE -> cmp <= 0;
                case GE -> cmp >= 0;
                case LT -> cmp < 0;
                case GT -> cmp > 0;
            };
            return new AssertionResult(text, "true",
                    l.stripTrailingZeros().toPlainString() + " " + op.symbol + " " + r.stripTrailingZeros().toPlainString(),
                    passed, ordinal);
        } catch (MissingOperandException exception) {
            return new AssertionResult(text, "true", exception.getMessage(), false, ordinal);
        }
    }

    private static BigDecimal value(List<Term> terms, FixMessageView message) {
        BigDecimal total = BigDecimal.ZERO;
        for (Term term : terms) {
            BigDecimal operand;
            if (Character.isDigit(term.operand().charAt(0))) {
                operand = new BigDecimal(term.operand());
            } else {
                String raw = message.value(term.operand())
                        .orElseThrow(() -> new MissingOperandException(term.operand() + " missing"));
                try {
                    operand = new BigDecimal(raw);
                } catch (NumberFormatException exception) {
                    throw new MissingOperandException(term.operand() + " not numeric");
                }
            }
            total = term.sign() > 0 ? total.add(operand) : total.subtract(operand);
        }
        return total;
    }

    private static final class MissingOperandException extends RuntimeException {
        MissingOperandException(String message) {
            super(message);
        }
    }
}
