package dev.blueprints.flow;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.expr.*;
import java.util.*;
import static dev.blueprints.flow.VisualModels.*;

/** Bidirectional expression builder. Unsupported syntax is never replaced with a default. */
final class VisualExpressions {
    record Typed(Expression node,String type) {}
    final VisualScope scope;
    private int count;
    VisualExpressions(VisualScope scope) { this.scope=scope; }
    Expr describe(Expression input) {
        if(input instanceof EnclosedExpr enclosed) return describe(enclosed.getInner());
        if(input instanceof IntegerLiteralExpr number) return Expr.literal("number",String.valueOf(number.asNumber()));
        if(input instanceof LongLiteralExpr number) return Expr.literal("number",String.valueOf(number.asNumber())+"L");
        if(input instanceof DoubleLiteralExpr number) {
            String normalized=number.toString().replace("_","");
            if(normalized.toLowerCase(Locale.ROOT).contains("x")) throw unsupported("Шестнадцатеричное дробное число пока редактируется в Java.");
            return Expr.literal("number",normalized);
        }
        if(input instanceof StringLiteralExpr text) return Expr.literal("string",text.asString());
        if(input instanceof CharLiteralExpr text) return Expr.literal("char",String.valueOf(text.asChar()));
        if(input instanceof BooleanLiteralExpr bool) return Expr.literal("boolean",String.valueOf(bool.getValue()));
        if(input instanceof NullLiteralExpr) return Expr.literal("null","");
        if(input instanceof NameExpr name) { scope.variable(name.getNameAsString()); return Expr.literal("variable",name.getNameAsString()); }
        if(input instanceof BinaryExpr binary && binaryOperator(binary.getOperator().asString())!=null)
            return Expr.binary(binary.getOperator().asString(),describe(binary.getLeft()),describe(binary.getRight()));
        if(input instanceof UnaryExpr unary && Set.of("!","+","-","~").contains(unary.getOperator().asString()))
            return Expr.unary(unary.getOperator().asString(),describe(unary.getExpression()));
        if(input instanceof MethodCallExpr call) {
            List<Expr> arguments=call.getArguments().stream().map(this::describe).toList();
            List<String> types=arguments.stream().map(arg -> new VisualExpressions(scope).build(arg).type()).toList();
            String receiver=call.getScope().map(Object::toString).orElse(null);
            if("this".equals(receiver)) receiver=null;
            String selectedReceiver=receiver;
            var matches=scope.calls.values().stream().filter(candidate -> Objects.equals(candidate.scope(),selectedReceiver)
                    && candidate.name().equals(call.getNameAsString()) && candidate.option().parameters().size()==types.size())
                    .filter(candidate -> {for(int i=0;i<types.size();i++) if(!assignable(candidate.option().parameters().get(i).type(),types.get(i))) return false; return true;})
                    .sorted(Comparator.comparingInt(candidate -> score(candidate.option(),types))).toList();
            if(matches.isEmpty()) throw unsupported("Вызов "+call.getNameAsString()+" пока не входит в визуальный каталог.");
            return Expr.call(matches.getFirst().option().id(),arguments);
        }
        throw unsupported("Для выражения «"+shortText(input.toString())+"» пока нужен расширенный редактор Java.");
    }
    Typed build(Expr expression) { return build(expression,0); }
    private Typed build(Expr expression,int depth) {
        if(expression==null || expression.kind()==null) throw EditableCode.invalid("Заполните выражение.");
        if(depth>24 || ++count>256) throw EditableCode.invalid("Выражение слишком большое: упростите вложенность.");
        String value=expression.value();
        switch(expression.kind()) {
            case "number": {
                if(value==null || value.length()>80 || !value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fFdDlL]?"))
                    throw EditableCode.invalid("Введите число, например 0, -5 или 2.5.");
                var result=scope.session.parser.parseExpression(value);
                if(!result.isSuccessful() || result.getResult().isEmpty()) throw EditableCode.invalid("Проверьте запись числа.");
                Expression parsed=result.getResult().orElseThrow();
                String lower=value.toLowerCase(Locale.ROOT);
                String type=lower.endsWith("l")?"long":lower.endsWith("f")?"float":lower.contains(".")||lower.contains("e")||lower.endsWith("d")?"double":"int";
                return new Typed(parsed,type);
            }
            case "string": {
                if(value==null || value.length()>8000) throw EditableCode.invalid("Введите текст длиной до 8000 символов.");
                return new Typed(new StringLiteralExpr().setString(value),"String");
            }
            case "char": {
                if(value==null || value.length()!=1) throw EditableCode.invalid("Для char нужен один символ.");
                return new Typed(new CharLiteralExpr().setChar(value.charAt(0)),"char");
            }
            case "boolean": {
                if(!Set.of("true","false").contains(value==null?"":value)) throw EditableCode.invalid("Выберите Да или Нет.");
                return new Typed(new BooleanLiteralExpr(Boolean.parseBoolean(value)),"boolean");
            }
            case "null": return new Typed(new NullLiteralExpr(),"null");
            case "variable": {
                Variable variable=scope.variable(value);
                return new Typed(new NameExpr(variable.name()),variable.type());
            }
            case "binary": {
                var operator=binaryOperator(expression.operator());
                if(operator==null) throw EditableCode.invalid("Выберите доступную операцию сравнения или вычисления.");
                Typed left=build(expression.left(),depth+1),right=build(expression.right(),depth+1);
                if(left.type().equals("void")||right.type().equals("void")) throw EditableCode.invalid("Этот метод не возвращает значение. Добавьте его отдельным действием.");
                String op=expression.operator(),type;
                if(Set.of("&&","||").contains(op)) { requireBoolean(left);requireBoolean(right);type="boolean"; }
                else if(Set.of("<","<=",">",">=").contains(op)) { requireNumber(left);requireNumber(right);type="boolean"; }
                else if(Set.of("==","!=").contains(op)) {
                    if(!(assignable(left.type(),right.type())||assignable(right.type(),left.type()))) throw EditableCode.invalid("Эти значения нельзя сравнить между собой.");
                    type="boolean";
                } else if(op.equals("+") && (simple(left.type()).equals("String")||simple(right.type()).equals("String"))) type="String";
                else if(Set.of("&","|","^").contains(op) && booleanType(left.type()) && booleanType(right.type())) type="boolean";
                else {requireNumber(left);requireNumber(right);if(Set.of("&","|","^").contains(op)){requireIntegral(left);requireIntegral(right);}type=promote(left.type(),right.type());}
                return new Typed(new BinaryExpr(group(left.node()),group(right.node()),operator),type);
            }
            case "unary": {
                String op=expression.operator();
                UnaryExpr.Operator operator=switch(op==null?"":op) {
                    case "!" -> UnaryExpr.Operator.LOGICAL_COMPLEMENT;case "+" -> UnaryExpr.Operator.PLUS;
                    case "-" -> UnaryExpr.Operator.MINUS;case "~" -> UnaryExpr.Operator.BITWISE_COMPLEMENT;
                    default -> throw EditableCode.invalid("Выберите доступную операцию над значением.");};
                Typed operand=build(expression.operand(),depth+1);
                if(op.equals("!")) requireBoolean(operand); else requireNumber(operand);
                if(op.equals("~")) requireIntegral(operand);
                return new Typed(new UnaryExpr(group(operand.node()),operator),op.equals("!")?"boolean":promote(operand.type(),"int"));
            }
            case "call": return call(expression.callId(),expression.arguments(),depth);
            default: throw EditableCode.invalid("Неизвестный вид выражения.");
        }
    }
    Typed call(String id,List<Expr> arguments,int depth) {
        var callable=scope.calls.get(id);
        if(callable==null) throw EditableCode.invalid("Выберите метод из списка доступных вызовов.");
        List<Expr> args=arguments==null?List.of():arguments;
        if(args.size()!=callable.option().parameters().size()) throw EditableCode.invalid("Заполните все аргументы выбранного метода.");
        MethodCallExpr result=new MethodCallExpr().setName(callable.name());
        if(callable.scope()!=null) result.setScope(scope.session.parser.parseExpression(callable.scope()).getResult().orElseThrow());
        List<String> argumentTypes=new ArrayList<>();
        for(int i=0;i<args.size();i++) {
            Typed argument=build(args.get(i),depth+1);
            requireAssignable(callable.option().parameters().get(i).type(),argument);
            argumentTypes.add(argument.type());
            result.addArgument(argument.node());
        }
        int selectedScore=score(callable.option(),argumentTypes);
        boolean closer=scope.calls.values().stream().filter(other->Objects.equals(other.scope(),callable.scope()) && other.name().equals(callable.name())
                        && other.option().parameters().size()==argumentTypes.size())
                .anyMatch(other->score(other.option(),argumentTypes)<selectedScore);
        if(closer) throw EditableCode.invalid("С этими аргументами Java выберет другую перегрузку метода. Выберите в списке вариант с подходящими типами параметров.");
        return new Typed(result,callable.option().returnType());
    }
    private static int score(Method method,List<String> types) {
        int score=0;
        for(int i=0;i<types.size();i++) {
            String expected=method.parameters().get(i).type(),actual=types.get(i);
            if(!assignable(expected,actual)) return 100000;
            if(expected.equals(actual)) continue;
            if(actual.equals("null")) {score+=simple(expected).equals("Object")?10:1;continue;}
            if(simple(expected).equals("Object")) {score+=Set.of("int","long","short","byte","char","double","float","boolean").contains(actual)?40:10;continue;}
            if(numeric(expected)&&numeric(actual)) {
                score+=Math.max(0,List.of("byte","short","char","int","long","float","double").indexOf(unbox(expected))
                        -List.of("byte","short","char","int","long","float","double").indexOf(unbox(actual)))+1;
                if(!expected.equals(unbox(expected)) || !actual.equals(unbox(actual))) score+=20;
            } else score+=20;
        }
        return score;
    }
    static Expression group(Expression node) { return node instanceof BinaryExpr || node instanceof AssignExpr ? new EnclosedExpr(node):node; }
    static String simple(String type) { return VisualScope.simple(type); }
    static String unbox(String type) {
        return switch(simple(type)) {case "Integer"->"int";case "Long"->"long";case "Float"->"float";case "Double"->"double";
            case "Short"->"short";case "Byte"->"byte";case "Character"->"char";case "Boolean"->"boolean";default->simple(type);};
    }
    static boolean booleanType(String type) { return unbox(type).equals("boolean"); }
    static boolean numeric(String type) { return Set.of("byte","short","char","int","long","float","double").contains(unbox(type)); }
    static void requireBoolean(Typed value) { if(!booleanType(value.type())) throw EditableCode.invalid("В условии нужно логическое значение: сравнение, Да/Нет или boolean-переменная."); }
    static void requireNumber(Typed value) { if(!numeric(value.type())) throw EditableCode.invalid("Для этой операции нужно число."); }
    static void requireIntegral(Typed value) { if(!Set.of("byte","short","char","int","long").contains(unbox(value.type()))) throw EditableCode.invalid("Для побитовой операции нужно целое число."); }
    static void requireAssignable(String expected,Typed value) {
        if(assignable(expected,value.type())) return;
        // Java allows constant integer narrowing in variable/assignment contexts, not call arguments.
        throw EditableCode.invalid("Ожидается "+expected+", а выбрано значение типа "+value.type()+".");
    }
    static boolean assignable(String expected,String actual) {
        if(expected.equals(actual)||simple(expected).equals(simple(actual)) && !expected.contains("<") && !actual.contains("<")) return true;
        if(actual.equals("void") || expected.equals("void")) return false;
        if(actual.equals("null")) return !Set.of("boolean","byte","short","char","int","long","float","double").contains(expected);
        if(simple(expected).equals("Object")) return true;
        if((numeric(expected)||booleanType(expected)) && unbox(expected).equals(unbox(actual))) return true;
        if(numeric(expected)&&numeric(actual)) {
            if(Set.of("Integer","Long","Float","Double","Short","Byte","Character").contains(simple(expected))) return false;
            String from=unbox(actual),to=unbox(expected);
            return switch(from) {case "byte"->Set.of("short","int","long","float","double").contains(to);
                case "short","char"->Set.of("int","long","float","double").contains(to);case "int"->Set.of("long","float","double").contains(to);
                case "long"->Set.of("float","double").contains(to);case "float"->to.equals("double");default->false;};
        }
        return false;
    }
    static String promote(String first,String second) {
        for(String candidate:List.of("double","float","long")) if(unbox(first).equals(candidate)||unbox(second).equals(candidate)) return candidate;
        return "int";
    }
    static BinaryExpr.Operator binaryOperator(String value) {
        if(value==null || !Set.of("+","-","*","/","%","==","!=","<","<=",">",">=","&&","||","&","|","^").contains(value)) return null;
        return Arrays.stream(BinaryExpr.Operator.values()).filter(operator->operator.asString().equals(value)).findFirst().orElse(null);
    }
    static UnsupportedOperationException unsupported(String message) { return new UnsupportedOperationException(message); }
    private static String shortText(String text) { return text.length()<90?text:text.substring(0,87)+"…"; }
}
