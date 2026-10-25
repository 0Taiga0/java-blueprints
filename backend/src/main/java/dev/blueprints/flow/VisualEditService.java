package dev.blueprints.flow;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.Type;
import dev.blueprints.roundtrip.*;
import java.util.*;
import javax.lang.model.SourceVersion;
import org.springframework.stereotype.Service;
import static dev.blueprints.flow.VisualModels.*;
import static dev.blueprints.flow.VisualExpressions.*;

@Service
public class VisualEditService {
    private final RoundTripService converter;
    public VisualEditService(RoundTripService converter) { this.converter=converter; }
    private record Editing(FlowMutationSession session,Node target,VisualScope scope,boolean insert) {}

    public Description describe(Context request) {
        Editing editing=prepare(request);
        Spec spec=null; boolean supported=true; String reason="";
        if(!editing.insert()) {
            try { spec=describe(editing.target(),editing.scope()); }
            catch(UnsupportedOperationException | SourceException exception) { supported=false;reason=exception.getMessage(); }
        }
        return new Description(editing.session().before.revision(),supported,reason,spec,
                editing.scope().variables.values().stream().map(VisualScope.Binding::option).toList(),
                editing.scope().calls.values().stream().map(VisualScope.Callable::option).toList(),List.copyOf(editing.scope().types));
    }
    public BlockEditModels.Result apply(Apply request) {
        if(!Set.of("insert","edit").contains(request.mode()==null?"":request.mode())) throw EditableCode.invalid("Выберите создание или изменение блока.");
        boolean insert=request.mode().equals("insert");
        if(insert != (request.position()!=null)) throw EditableCode.invalid("Для добавления укажите место, для изменения не задавайте позицию вставки.");
        Editing editing=prepare(new Context(request.fileName(),request.source(),request.revision(),request.methodId(),request.nodeId(),request.position()));
        if(request.spec()==null) throw EditableCode.invalid("Заполните параметры блока.");
        if(!insert) {
            Spec original;
            try { original=describe(editing.target(),editing.scope()); }
            catch(UnsupportedOperationException exception) { throw EditableCode.invalid(exception.getMessage()); }
            if(!original.kind().equals(request.spec().kind())) throw EditableCode.invalid("Сохраните вид существующего блока или добавьте новый блок.");
            if(original.kind().equals("loop") && !original.loopKind().equals(request.spec().loopKind()))
                throw EditableCode.invalid("При изменении сохраните вид цикла. Для другого вида добавьте новый цикл.");
        }
        Statement replacement=build(request.spec(),editing.scope(),insert?null:editing.target());
        if(editing.target() instanceof Statement statement) {
            statement.getComment().ifPresent(comment -> replacement.setComment(comment.clone()));
            if(!insert) renameBinding(editing,request.spec(),replacement);
            StatementPlacement.replace(statement,replacement);
        } else throw EditableCode.invalid("Этот элемент пока нельзя изменить визуальной формой.");
        return editing.session().finish(replacement,true);
    }
    private Editing prepare(Context request) {
        var session=new FlowMutationSession(converter,request.fileName(),request.source(),request.revision(),request.methodId());
        var node=session.block(request.nodeId());
        Node target;
        boolean insert=request.position()!=null;
        if(insert) {
            Node anchor=session.insertionTarget(node,request.position());
            EmptyStmt marker=new EmptyStmt(); StatementPlacement.insertAt(anchor,request.position(),marker); target=marker;
        } else {
            target=session.editTarget(node);
            Node parent=target.getParentNode().orElse(null);
            if(parent instanceof IfStmt branch && branch.getCondition()==target) target=branch;
            else if(parent instanceof ForStmt || parent instanceof ForEachStmt || parent instanceof WhileStmt || parent instanceof DoStmt) target=parent;
        }
        Node point=target;
        // The loop counter belongs to its condition/body, but not outside the loop.
        if(target instanceof ForStmt loop) point=loop.getCompare().map(n->(Node)n).orElse(loop.getBody());
        else if(target instanceof ForEachStmt loop) point=loop.getBody();
        return new Editing(session,target,new VisualScope(session,point),insert);
    }
    private Spec describe(Node target,VisualScope scope) {
        var expressions=new VisualExpressions(scope);
        if(target instanceof ExpressionStmt statement) {
            Expression expression=statement.getExpression();
            if(expression instanceof VariableDeclarationExpr declaration) {
                if(declaration.getVariables().size()!=1 || !declaration.getAnnotations().isEmpty()) throw unsupported("Декларация нескольких переменных или с аннотацией пока редактируется в Java.");
                var variable=declaration.getVariable(0);
                if(variable.getType().isVarType()) throw unsupported("Для var пока используйте расширенный редактор Java.");
                return spec("variable",variable.getNameAsString(),variable.getType().toString(),
                        variable.getInitializer().map(expressions::describe).orElse(null),declaration.isFinal(),null,null,null,null,null,null,null,null,null);
            }
            if(expression instanceof AssignExpr assignment && assignment.getTarget() instanceof NameExpr name) {
                scope.variable(name.getNameAsString());
                if(!Set.of("=","+=","-=","*=","/=","%=").contains(assignment.getOperator().asString())) throw unsupported("Этот оператор присваивания пока редактируется в Java.");
                return spec("action",null,null,expressions.describe(assignment.getValue()),null,name.getNameAsString(),assignment.getOperator().asString(),null,null,null,null,null,null,null);
            }
            if(expression instanceof UnaryExpr unary && unary.getExpression() instanceof NameExpr name && Set.of("++","--").contains(unary.getOperator().asString())) {
                scope.variable(name.getNameAsString());
                return spec("action",null,null,null,null,name.getNameAsString(),unary.getOperator().asString(),null,null,null,null,null,null,null);
            }
            if(expression instanceof MethodCallExpr call) {
                Expr described=expressions.describe(call);
                return spec("call",null,null,null,null,null,null,null,null,null,null,null,described.callId(),described.arguments());
            }
        }
        if(target instanceof IfStmt condition) return spec("condition",null,null,null,null,null,null,expressions.describe(condition.getCondition()),null,null,null,null,null,null);
        if(target instanceof WhileStmt loop) return spec("loop",null,null,null,null,null,null,expressions.describe(loop.getCondition()),"while",null,null,null,null,null);
        if(target instanceof DoStmt loop) return spec("loop",null,null,null,null,null,null,expressions.describe(loop.getCondition()),"do",null,null,null,null,null);
        if(target instanceof ForEachStmt loop) {
            if(loop.getVariable().getVariables().size()!=1 || !loop.getVariable().getAnnotations().isEmpty()) throw unsupported("Этот заголовок foreach пока редактируется в Java.");
            var variable=loop.getVariable().getVariable(0);
            return spec("loop",variable.getNameAsString(),variable.getType().toString(),null,loop.getVariable().isFinal(),null,null,null,"foreach",null,null,expressions.describe(loop.getIterable()),null,null);
        }
        if(target instanceof ForStmt loop) {
            if(loop.getInitialization().size()!=1 || !(loop.getInitialization().get(0) instanceof VariableDeclarationExpr declaration)
                    || declaration.getVariables().size()!=1 || loop.getCompare().isEmpty() || loop.getUpdate().size()!=1 || !declaration.getAnnotations().isEmpty())
                throw unsupported("Визуальная форма for поддерживает один счётчик, условие и шаг. Этот цикл сохранён для Java-редактора.");
            var variable=declaration.getVariable(0);
            if(variable.getInitializer().isEmpty() || !numeric(variable.getType().toString()) || variable.getType().isVarType()) throw unsupported("Для этого for пока нужен Java-редактор.");
            Expression update=loop.getUpdate().get(0); String operator; Expr step;
            if(update instanceof UnaryExpr unary && unary.getExpression().isNameExpr()
                    && unary.getExpression().asNameExpr().getNameAsString().equals(variable.getNameAsString()) && Set.of("++","--").contains(unary.getOperator().asString())) {
                operator=unary.getOperator().asString().equals("++")?"+=":"-=";step=Expr.literal("number","1");
            } else if(update instanceof AssignExpr assignment && assignment.getTarget().isNameExpr()
                    && assignment.getTarget().asNameExpr().getNameAsString().equals(variable.getNameAsString()) && Set.of("+=","-=").contains(assignment.getOperator().asString())) {
                operator=assignment.getOperator().asString();step=expressions.describe(assignment.getValue());
            } else throw unsupported("Для этого шага for пока нужен Java-редактор.");
            return spec("loop",variable.getNameAsString(),variable.getType().toString(),null,declaration.isFinal(),null,operator,
                    expressions.describe(loop.getCompare().orElseThrow()),"for",expressions.describe(variable.getInitializer().orElseThrow()),step,null,null,null);
        }
        if(target instanceof ReturnStmt returned) return spec("return",null,null,returned.getExpression().map(expressions::describe).orElse(null),null,null,null,null,null,null,null,null,null,null);
        throw unsupported("Для этого блока пока доступен расширенный редактор Java. Исходный код сохранён.");
    }
    private Statement build(Spec spec,VisualScope scope,Node original) {
        var expressions=new VisualExpressions(scope);
        switch(spec.kind()==null?"":spec.kind()) {
            case "variable": {
                checkName(spec.name()); Type type=type(scope,spec.type()); checkFresh(scope,spec.name(),original);
                var variable=new VariableDeclarator(type,spec.name());
                if(spec.value()!=null) { var value=expressions.build(spec.value()); requireVariableAssignable(spec.type(),value);variable.setInitializer(value.node()); }
                return new ExpressionStmt(new VariableDeclarationExpr(variable).setFinal(Boolean.TRUE.equals(spec.finalVariable())));
            }
            case "action": {
                Variable target=scope.variable(spec.target());
                if(!target.writable()) throw EditableCode.invalid("Переменную final нельзя изменять.");
                String operator=spec.operator();
                if(Set.of("++","--").contains(operator==null?"":operator)) {
                    requireNumber(new Typed(new NameExpr(target.name()),target.type()));
                    return new ExpressionStmt(new UnaryExpr(new NameExpr(target.name()),operator.equals("++")?UnaryExpr.Operator.POSTFIX_INCREMENT:UnaryExpr.Operator.POSTFIX_DECREMENT));
                }
                if(!Set.of("=","+=","-=","*=","/=","%=").contains(operator==null?"":operator)) throw EditableCode.invalid("Выберите действие над переменной.");
                Typed value=expressions.build(spec.value());
                if(operator.equals("=")) requireVariableAssignable(target.type(),value);
                else if(!(operator.equals("+=") && simple(target.type()).equals("String"))) {requireNumber(new Typed(new NameExpr(target.name()),target.type()));requireNumber(value);}
                var selected=Arrays.stream(AssignExpr.Operator.values()).filter(op->op.asString().equals(operator)).findFirst().orElseThrow();
                return new ExpressionStmt(new AssignExpr(new NameExpr(target.name()),value.node(),selected));
            }
            case "condition": {
                Typed condition=expressions.build(spec.condition());requireBoolean(condition);
                IfStmt result=original instanceof IfStmt branch?branch.clone():new IfStmt().setThenStmt(new BlockStmt());
                result.setCondition(condition.node());return result;
            }
            case "loop": {
                String kind=spec.loopKind();
                Statement body=original instanceof Statement statement && InsertableCode.loopBody(statement)!=null?InsertableCode.loopBody(statement).clone():new BlockStmt();
                if("while".equals(kind)||"do".equals(kind)) {
                    Typed condition=expressions.build(spec.condition());requireBoolean(condition);
                    return kind.equals("while")?new WhileStmt(condition.node(),body):new DoStmt(body,condition.node());
                }
                checkName(spec.name());Type type=type(scope,spec.type());checkFresh(scope,spec.name(),original);
                VariableDeclarator old=declared(original);
                if(old!=null) scope.variables.remove(old.getNameAsString());
                if("foreach".equals(kind)) {
                    Typed iterable=expressions.build(spec.iterable());
                    String base=simple(iterable.type());
                    if(!iterable.type().endsWith("[]") && !Set.of("List","ArrayList","LinkedList","Set","HashSet","TreeSet","Collection","Iterable").contains(base))
                        throw EditableCode.invalid("Для перебора выберите массив или коллекцию.");
                    String element=VisualScope.elementType(iterable.type());
                    if(!assignable(spec.type(),element)) throw EditableCode.invalid("Тип элемента коллекции "+element+" не подходит переменной "+spec.type()+".");
                    return new ForEachStmt(new VariableDeclarationExpr(new VariableDeclarator(type,spec.name())).setFinal(Boolean.TRUE.equals(spec.finalVariable())),iterable.node(),body);
                }
                if(!"for".equals(kind)) throw EditableCode.invalid("Выберите вид цикла.");
                if(!numeric(spec.type())) throw EditableCode.invalid("Для счётчика выберите числовой тип.");
                Typed start=expressions.build(spec.start());requireVariableAssignable(spec.type(),start);
                scope.put(spec.name(),spec.type(),true);
                expressions=new VisualExpressions(scope);
                Typed condition=expressions.build(spec.condition());requireBoolean(condition);
                Typed step=expressions.build(spec.step());requireNumber(step);
                if(!Set.of("+=","-=").contains(spec.operator()==null?"":spec.operator())) throw EditableCode.invalid("Выберите увеличение или уменьшение счётчика.");
                var declaration=new VariableDeclarationExpr(new VariableDeclarator(type,spec.name(),start.node()));
                var update=new AssignExpr(new NameExpr(spec.name()),step.node(),spec.operator().equals("+=")?AssignExpr.Operator.PLUS:AssignExpr.Operator.MINUS);
                return new ForStmt(new NodeList<>(declaration),condition.node(),new NodeList<>(update),body);
            }
            case "call": return new ExpressionStmt(expressions.call(spec.callId(),spec.arguments(),0).node());
            case "return": {
                String expected=scope.session.declaration instanceof MethodDeclaration method?method.getType().toString():"void";
                if(spec.value()==null) { if(!expected.equals("void")) throw EditableCode.invalid("Этот метод должен вернуть значение типа "+expected+".");return new ReturnStmt(); }
                Typed value=expressions.build(spec.value());requireVariableAssignable(expected,value);return new ReturnStmt(value.node());
            }
            default: throw EditableCode.invalid("Выберите доступный вид визуального блока.");
        }
    }
    private Type type(VisualScope scope,String text) {
        if(text==null || !scope.types.contains(text)) throw EditableCode.invalid("Выберите тип из списка.");
        return scope.session.parser.parseType(text).getResult().orElseThrow(()->EditableCode.invalid("Этот тип нельзя использовать для переменной."));
    }
    private void checkName(String name) {
        if(name==null || name.length()>100 || !SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name) || Set.of("var","yield","record","sealed","permits","_").contains(name))
            throw EditableCode.invalid("Имя должно быть допустимым именем Java: например count или totalPrice.");
    }
    private void checkFresh(VisualScope scope,String name,Node original) {
        VariableDeclarator old=declared(original);
        if(old!=null && old.getNameAsString().equals(name)) return;
        var existing=scope.variables.get(name);
        if(existing!=null && !(existing.declaration()!=null && existing.declaration().getParentNode().orElse(null) instanceof FieldDeclaration))
            throw EditableCode.invalid("Имя «"+name+"» уже используется в этой области. Выберите другое.");
        // A declaration added before a later declaration in the same block would also collide.
        Node context=original!=null?original:scope.point;
        Node parent=context.getParentNode().orElse(null);
        if(parent instanceof BlockStmt block) for(Statement statement:block.getStatements())
            if(statement!=original && statement instanceof ExpressionStmt expression && expression.getExpression() instanceof VariableDeclarationExpr declaration
                    && declaration.getVariables().stream().anyMatch(variable->variable.getNameAsString().equals(name)))
                throw EditableCode.invalid("Имя «"+name+"» уже есть в этом блоке.");
    }
    private VariableDeclarator declared(Node node) {
        if(node instanceof ExpressionStmt statement && statement.getExpression() instanceof VariableDeclarationExpr declaration && declaration.getVariables().size()==1) return declaration.getVariable(0);
        if(node instanceof ForStmt loop && loop.getInitialization().size()==1 && loop.getInitialization().get(0) instanceof VariableDeclarationExpr declaration && declaration.getVariables().size()==1) return declaration.getVariable(0);
        if(node instanceof ForEachStmt loop && loop.getVariable().getVariables().size()==1) return loop.getVariable().getVariable(0);
        return null;
    }
    private void renameBinding(Editing editing,Spec spec,Statement replacement) {
        VariableDeclarator old=declared(editing.target()),next=declared(replacement);
        if(old==null || next==null || old.getNameAsString().equals(next.getNameAsString())) return;
        String oldName=old.getNameAsString(),newName=next.getNameAsString();
        // Renaming a declaration alone can bind a different variable in nested scopes. Reject it until a full refactoring UI is offered.
        boolean used=editing.session().declaration.findAll(NameExpr.class).stream().filter(name->name.getNameAsString().equals(oldName))
                .anyMatch(name->{var binding=new VisualScope(editing.session(),name).variables.get(oldName);return binding!=null && binding.declaration()==old;});
        if(used) throw EditableCode.invalid("Переменная «"+oldName+"» уже используется. Сохраните её имя; переименование всех ссылок пока выполняется в Java-редакторе.");
    }
    private void requireVariableAssignable(String type,Typed value) {
        if(Set.of("byte","short","char").contains(type) && value.type().equals("int")) {
            try {
                int number=Integer.parseInt(value.node().toString());
                boolean allowed=switch(type){case "byte"->number>=Byte.MIN_VALUE&&number<=Byte.MAX_VALUE;case "short"->number>=Short.MIN_VALUE&&number<=Short.MAX_VALUE;default->number>=0&&number<=Character.MAX_VALUE;};
                if(allowed) return;
            } catch(NumberFormatException ignored) {}
        }
        requireAssignable(type,value);
    }
    private static Spec spec(String kind,String name,String type,Expr value,Boolean finalVariable,String target,String operator,Expr condition,
                             String loopKind,Expr start,Expr step,Expr iterable,String callId,List<Expr> arguments) {
        return new Spec(kind,name,type,value,finalVariable,target,operator,condition,loopKind,start,step,iterable,callId,arguments);
    }
}
