package dev.lifeloom.params;

import dev.lifeloom.core.LifeloomException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 参数表达式（包内私有实现）：四则运算、括号、一元符号与同属主参数引用。
 *
 * <p>语法：expr := term (('+' | '-') term)*；term := factor (('*' | '/') factor)*；
 * factor := '-' factor | '+' factor | '(' expr ')' | number | reference。
 * 数字为十进制字面量（支持小数），引用为同属主下的参数名。
 */
final class Expr {

    private Expr() {
    }

    /** 求值入口：解析失败 / 数值错误抛 {@link LifeloomException}。 */
    static double evaluate(String source, Lookup lookup) {
        return parse(source).eval(lookup);
    }

    /** 提取表达式引用的全部参数名（语法错误同样抛出）。 */
    static Set<String> refs(String source) {
        Set<String> out = new LinkedHashSet<>();
        parse(source).collectRefs(out);
        return out;
    }

    /** 参数取值回调。 */
    interface Lookup {
        double valueOf(String name);
    }

    private static Node parse(String source) {
        if (source == null || source.isBlank()) {
            throw new LifeloomException("表达式为空");
        }
        Parser parser = new Parser(source);
        Node node = parser.parseExpr();
        parser.skipSpaces();
        if (!parser.atEnd()) {
            throw new LifeloomException("表达式尾随内容: " + parser.rest());
        }
        return node;
    }

    /** 表达式节点。 */
    private interface Node {
        double eval(Lookup lookup);

        void collectRefs(Set<String> out);
    }

    private static final class Num implements Node {
        private final double value;

        Num(double value) {
            this.value = value;
        }

        @Override
        public double eval(Lookup lookup) {
            return value;
        }

        @Override
        public void collectRefs(Set<String> out) {
        }
    }

    private static final class Ref implements Node {
        private final String name;

        Ref(String name) {
            this.name = name;
        }

        @Override
        public double eval(Lookup lookup) {
            return lookup.valueOf(name);
        }

        @Override
        public void collectRefs(Set<String> out) {
            out.add(name);
        }
    }

    private static final class Neg implements Node {
        private final Node inner;

        Neg(Node inner) {
            this.inner = inner;
        }

        @Override
        public double eval(Lookup lookup) {
            return -inner.eval(lookup);
        }

        @Override
        public void collectRefs(Set<String> out) {
            inner.collectRefs(out);
        }
    }

    private static final class Bin implements Node {
        private final char op;
        private final Node left;
        private final Node right;

        Bin(char op, Node left, Node right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        public double eval(Lookup lookup) {
            double l = left.eval(lookup);
            double r = right.eval(lookup);
            double result;
            switch (op) {
                case '+':
                    result = l + r;
                    break;
                case '-':
                    result = l - r;
                    break;
                case '*':
                    result = l * r;
                    break;
                case '/':
                    if (r == 0) {
                        throw new LifeloomException("表达式除零");
                    }
                    result = l / r;
                    break;
                default:
                    throw new LifeloomException("表达式运算符非法: " + op);
            }
            if (!Double.isFinite(result)) {
                throw new LifeloomException("表达式结果非有限数");
            }
            return result;
        }

        @Override
        public void collectRefs(Set<String> out) {
            left.collectRefs(out);
            right.collectRefs(out);
        }
    }

    /** 递归下降解析器。 */
    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        String rest() {
            return src.substring(Math.min(pos, src.length()));
        }

        void skipSpaces() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        Node parseExpr() {
            Node left = parseTerm();
            while (true) {
                skipSpaces();
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    char op = src.charAt(pos);
                    pos++;
                    left = new Bin(op, left, parseTerm());
                } else {
                    return left;
                }
            }
        }

        Node parseTerm() {
            Node left = parseFactor();
            while (true) {
                skipSpaces();
                if (pos < src.length() && (src.charAt(pos) == '*' || src.charAt(pos) == '/')) {
                    char op = src.charAt(pos);
                    pos++;
                    left = new Bin(op, left, parseFactor());
                } else {
                    return left;
                }
            }
        }

        Node parseFactor() {
            skipSpaces();
            if (pos >= src.length()) {
                throw new LifeloomException("表达式意外结束");
            }
            char c = src.charAt(pos);
            if (c == '-') {
                pos++;
                return new Neg(parseFactor());
            }
            if (c == '+') {
                pos++;
                return parseFactor();
            }
            if (c == '(') {
                pos++;
                Node inner = parseExpr();
                skipSpaces();
                if (pos >= src.length() || src.charAt(pos) != ')') {
                    throw new LifeloomException("表达式缺少右括号");
                }
                pos++;
                return inner;
            }
            if (Character.isDigit(c) || c == '.') {
                return parseNumber();
            }
            if (Character.isLetter(c) || c == '_') {
                return parseIdent();
            }
            throw new LifeloomException("表达式非法字符: " + c);
        }

        Node parseNumber() {
            int start = pos;
            while (pos < src.length()
                    && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            String text = src.substring(start, pos);
            double value;
            try {
                value = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw new LifeloomException("表达式数值非法: " + text);
            }
            if (!Double.isFinite(value)) {
                throw new LifeloomException("表达式数值非法: " + text);
            }
            return new Num(value);
        }

        Node parseIdent() {
            int start = pos;
            while (pos < src.length()
                    && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            return new Ref(src.substring(start, pos));
        }
    }
}
