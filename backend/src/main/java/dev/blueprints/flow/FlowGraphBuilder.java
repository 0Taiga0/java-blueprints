package dev.blueprints.flow;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import static dev.blueprints.flow.FlowModels.*;

/**
 * Граф операторов Java. Обычные выражения остаются атомарными.
 * switch-выражения раскрываются с учётом порядка вычисления.
 * Исключения консервативны: тип выбирает первый подходящий catch.
 */
public final class FlowGraphBuilder {
    public List<MethodFlow> build(CompilationUnit unit) {
        List<Node> declarations = new ArrayList<>();
        unit.walk(n -> {
            if (n instanceof MethodDeclaration || n instanceof ConstructorDeclaration || n instanceof InitializerDeclaration)
                declarations.add(n);
        });
        declarations.sort(Comparator.comparing(n -> n.getBegin().orElseThrow()));
        return declarations.stream().map(this::buildMethod).toList();
    }
    static String id(Node node) { return "method-" + range(node).startLine() + "-" + range(node).startColumn(); }
    static String owner(Node node) {
        Deque<String> owners = new ArrayDeque<>();
        for (Node p = node.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null))
            if (p instanceof TypeDeclaration<?> t) owners.addFirst(t.getNameAsString());
        return String.join(".",owners);
    }
    static String header(Node node) {
        if (node instanceof MethodDeclaration m) return m.getDeclarationAsString(true,true,true);
        if (node instanceof ConstructorDeclaration c) return c.getDeclarationAsString(true,true,true);
        if (node instanceof InitializerDeclaration i) return i.isStatic() ? "static { … }" : "{ … } · инициализация объекта";
        return node.toString();
    }
    private MethodFlow buildMethod(Node declaration) {
        BlockStmt body;
        String signature,kind;
        if (declaration instanceof CallableDeclaration<?> method) {
            signature = method.getNameAsString()+"("+method.getParameters().stream()
                    .map(p -> p.getTypeAsString()+(p.isVarArgs()?"...":"")).collect(Collectors.joining(", "))+")";
            kind = method instanceof ConstructorDeclaration ? "constructor" : "method";
            body = method instanceof MethodDeclaration m ? m.getBody().orElse(null) : ((ConstructorDeclaration)method).getBody();
        } else {
            var initializer = (InitializerDeclaration)declaration;
            signature = initializer.isStatic() ? "static { … }" : "{ … } · инициализатор";
            kind = "initializer"; body = initializer.getBody();
        }
        if (body == null) return new MethodFlow(id(declaration),owner(declaration),signature,kind,range(declaration),
                "no-body",List.of("Объявление без тела. Сигнатура и throws доступны в графе структуры файла."),List.of(),List.of());
        Graph graph = new Graph(declaration);
        List<String> warnings = new ArrayList<>();
        String mode = "flow";
        try {
            long count = body.findAll(Statement.class).size();
            if (count > 350) throw new OverviewRequired("Больше 350 операторов: показан обзор метода.");
            String end = graph.node("end","Конец метода",pointAtEnd(body),null,body,"","");
            String exceptional = graph.node("exception-end","Исключение выходит из метода",pointAtEnd(body),null,null,
                    header(declaration),"Проверяемые исключения должны быть пойманы или объявлены в throws. Это проверяет javac.");
            Context context = new Context(null,null,end,null,exceptional,Map.of());
            String entry = graph.statement(body,end,context,0);
            String start = graph.node("start",header(declaration),pointAtStart(declaration),null,body,header(declaration),
                    "Метод / конструктор / инициализатор. Выражения в обычных действиях показаны целиком.");
            graph.edge(start,entry,"");
            graph.keepReachable(start);
            if (graph.edges.stream().anyMatch(e -> e.kind().equals("exception")))
                warnings.add("Пунктир — возможное исключение. Проверяется первый подходящий catch по порядку. Типы и обязательный catch/throws проверяет компилятор.");
            if (body.findAll(TryStmt.class).stream().anyMatch(t -> t.getFinallyBlock().isPresent()))
                warnings.add("Один finally может быть показан на нескольких маршрутах: обычный выход, return, break/continue и исключение. Это один и тот же участок Java-кода.");
        } catch (OverviewRequired e) {
            mode = "overview"; warnings = List.of(e.getMessage());
            graph = new Graph(declaration);
            graph.node("opaque","Открыть тело метода",range(body),null,null,body.toString(),e.getMessage());
        }
        return new MethodFlow(id(declaration),owner(declaration),signature,kind,range(declaration),mode,
                List.copyOf(warnings),List.copyOf(graph.nodes),List.copyOf(graph.edges));
    }

    private record Jump(String breakTarget,String continueTarget) {}
    private record Context(String breakTarget,String continueTarget,String exit,String yieldTarget,
            String exception,Map<String,Jump> labels) {
        Context loop(String next,String repeat,Statement loop) {
            Map<String,Jump> copy = new LinkedHashMap<>(labels);
            if (loop.getParentNode().orElse(null) instanceof LabeledStmt label)
                copy.put(label.getLabel().asString(),new Jump(next,repeat));
            return new Context(next,repeat,exit,yieldTarget,exception,copy);
        }
        Context switchBreak(String target) { return new Context(target,continueTarget,exit,yieldTarget,exception,labels); }
        Context yielding(String target) { return new Context(breakTarget,continueTarget,exit,target,exception,labels); }
        Context catching(String target) { return new Context(breakTarget,continueTarget,exit,yieldTarget,target,labels); }
        Context route(BiFunction<String,String,String> route) {
            Map<String,Jump> copy = new LinkedHashMap<>();
            labels.forEach((label,j) -> copy.put(label,new Jump(route.apply(j.breakTarget(),"break "+label),
                    route.apply(j.continueTarget(),"continue "+label))));
            return new Context(route.apply(breakTarget,"break"),route.apply(continueTarget,"continue"),
                    route.apply(exit,"return"),route.apply(yieldTarget,"yield"),route.apply(exception,"исключение"),copy);
        }
    }
    private static final class OverviewRequired extends RuntimeException { OverviewRequired(String s) { super(s); } }

    private static final class Graph {
        final List<FlowNode> nodes = new ArrayList<>();
        final List<FlowEdge> edges = new ArrayList<>();
        final CodeCategory categories;
        Graph(Node declaration) { categories = new CodeCategory(declaration.findCompilationUnit().orElseThrow()); }

        String node(String kind,String text,SourceRange range,Node edit,Node insert,String code,String detail) {
            if (nodes.size() >= 1800) throw new OverviewRequired("Граф содержит больше 1800 узлов после раскрытия маршрутов. Показан обзор.");
            String label = text.replaceAll("\\s+"," ").trim();
            String id = "n"+nodes.size();
            nodes.add(new FlowNode(id,kind,label.length()>140?label.substring(0,137)+"…":label,range,
                    EditableCode.describe(edit),InsertableCode.describe(kind,insert),
                    code.isEmpty()?text:code,detail,categories.of(edit,text),null,MutableCode.describe(kind,insert)));
            return id;
        }
        String ast(String kind,String text,Node edit,Node insert,String detail) {
            return node(kind,text,range(edit != null ? edit : insert),edit,insert,text,detail);
        }
        void edge(String from,String to,String label) { edge(from,to,label,"normal"); }
        void edge(String from,String to,String label,String kind) {
            if (from != null && to != null) edges.add(new FlowEdge("e"+edges.size(),from,to,label,kind));
        }
        void possibleException(String node,Context context,Expression expression) {
            if (expression == null || (expression instanceof LiteralExpr && !(expression instanceof NullLiteralExpr)) || expression instanceof LambdaExpr) return;
            edge(node,context.exception(),"Возможно исключение","exception");
        }
        String operation(String kind,String text,Node ast,String next,Context c,Expression expression,int depth) {
            String node = ast(kind,text,ast,ast,containsSwitch(expression)
                    ? "Оператор завершает работу после вычисления раскрытого switch. Выражения вычисляются слева направо." : "");
            edge(node,next,"");
            possibleException(node,c,expression);
            return expression == null ? node : expression(expression,node,c,depth+1,false);
        }
        String decision(Expression expr,Context c,Node insertion,int depth) {
            String node = ast("condition",expr.toString(),expr,insertion,"Условие вычисляется один раз при каждом достижении этого блока.");
            possibleException(node,c,expr);
            return node;
        }
        String sequence(List<Statement> statements,String next,Context c,int depth) {
            String entry = next;
            for (int i=statements.size()-1;i>=0;i--) entry=statement(statements.get(i),entry,c,depth+1);
            return entry;
        }

        String statement(Statement s,String next,Context c,int depth) {
            if (depth>100) throw new OverviewRequired("Вложенность больше 100 уровней: показан обзор.");
            if (s instanceof BlockStmt b) return sequence(b.getStatements(),next,c,depth);
            if (s instanceof IfStmt b) {
                String test=decision(b.getCondition(),c,b,depth);
                edge(test,statement(b.getThenStmt(),next,c,depth+1),"Да");
                edge(test,b.getElseStmt().map(e->statement(e,next,c,depth+1)).orElse(next),"Нет");
                return expression(b.getCondition(),test,c,depth+1,false);
            }
            if (s instanceof WhileStmt w) {
                String test=decision(w.getCondition(),c,w,depth);
                String entry=expression(w.getCondition(),test,c,depth+1,false);
                if (!isFalse(w.getCondition())) edge(test,statement(w.getBody(),entry,c.loop(next,entry,w),depth+1),"Да");
                if (!isTrue(w.getCondition())) edge(test,next,"Нет");
                return entry;
            }
            if (s instanceof ForEachStmt f) {
                String test=ast("condition","for ("+f.getVariable()+" : "+f.getIterable()+")",f.getIterable(),f,
                        "Коллекция/массив вычисляется перед циклом. Проверка следующего элемента и присваивание переменной повторяются на каждой итерации.");
                possibleException(test,c,f.getIterable());
                edge(test,statement(f.getBody(),test,c.loop(next,test,f),depth+1),"Есть элемент");
                edge(test,next,"Конец");
                // Вложенный switch в iterable выполняется только один раз, не на обратной стрелке.
                return expression(f.getIterable(),test,c,depth+1,false);
            }
            if (s instanceof ForStmt f) {
                String test=f.getCompare().map(e->decision(e,c,f,depth)).orElseGet(() ->
                        node("condition","true · бесконечный цикл",pointAtStart(f),null,f,"for (;;)","Нет проверки завершения: выйти можно через break/return/исключение."));
                String entry=f.getCompare().map(e->expression(e,test,c,depth+1,false)).orElse(test);
                String update=entry;
                for (int i=f.getUpdate().size()-1;i>=0;i--) {
                    Expression e=f.getUpdate().get(i); update=operation("operation",e.toString(),e,update,c,e,depth);
                }
                if (f.getCompare().map(e->!isFalse(e)).orElse(true))
                    edge(test,statement(f.getBody(),update,c.loop(next,update,f),depth+1),"Да");
                if (f.getCompare().map(e->!isTrue(e)).orElse(false)) edge(test,next,"Нет");
                for (int i=f.getInitialization().size()-1;i>=0;i--) {
                    Expression e=f.getInitialization().get(i); entry=operation("operation",e.toString(),e,entry,c,e,depth);
                }
                return entry;
            }
            if (s instanceof DoStmt d) {
                String test=decision(d.getCondition(),c,d,depth);
                String conditionEntry=expression(d.getCondition(),test,c,depth+1,false);
                String entry=statement(d.getBody(),conditionEntry,c.loop(next,conditionEntry,d),depth+1);
                if (!isFalse(d.getCondition())) edge(test,entry,"Да");
                if (!isTrue(d.getCondition())) edge(test,next,"Нет");
                return entry;
            }
            if (s instanceof SwitchStmt sw) return switchFlow(sw.getSelector(),sw.getEntries(),sw,next,c.switchBreak(next),depth,false);
            if (s instanceof TryStmt t) return tryFlow(t,next,c,depth);
            if (s instanceof ReturnStmt r) {
                String node=ast("return",r.toString(),r,r,"Сначала вычислить и запомнить результат, затем пройти finally/закрытие ресурсов и выйти из метода. Исключение или return в finally может заменить этот выход.");
                edge(node,c.exit(),"");
                possibleException(node,c,r.getExpression().orElse(null));
                return r.getExpression().map(e->expression(e,node,c,depth+1,false)).orElse(node);
            }
            if (s instanceof ThrowStmt t) {
                String node=ast("throw",t.toString(),t,t,"Передать исключение первому подходящему обработчику; при выходе выполнить finally.");
                edge(node,c.exception(),"throw","exception");
                return expression(t.getExpression(),node,c,depth+1,false);
            }
            if (s instanceof YieldStmt y) {
                if (c.yieldTarget()==null) throw new OverviewRequired("yield вне switch-выражения.");
                String node=ast("yield",y.toString(),y,y,"Передать значение из ветки switch-выражения.");
                edge(node,c.yieldTarget(),"значение"); possibleException(node,c,y.getExpression());
                return expression(y.getExpression(),node,c,depth+1,false);
            }
            if (s instanceof BreakStmt b) {
                String label=b.getLabel().map(Object::toString).orElse(null);
                String target=label==null?c.breakTarget():Optional.ofNullable(c.labels().get(label)).map(Jump::breakTarget).orElse(null);
                if (target==null) throw new OverviewRequired("break вне подходящего цикла/switch/метки.");
                return operation("jump",label==null?"break":"break "+label,b,target,c,null,depth);
            }
            if (s instanceof ContinueStmt b) {
                String label=b.getLabel().map(Object::toString).orElse(null);
                String target=label==null?c.continueTarget():Optional.ofNullable(c.labels().get(label)).map(Jump::continueTarget).orElse(null);
                if (target==null) throw new OverviewRequired("continue вне подходящего цикла.");
                return operation("jump",label==null?"continue":"continue "+label,b,target,c,null,depth);
            }
            if (s instanceof LabeledStmt l) {
                Map<String,Jump> labels=new LinkedHashMap<>(c.labels()); labels.put(l.getLabel().asString(),new Jump(next,null));
                return statement(l.getStatement(),next,new Context(c.breakTarget(),c.continueTarget(),c.exit(),c.yieldTarget(),c.exception(),labels),depth+1);
            }
            if (s instanceof SynchronizedStmt sync) return synchronizedFlow(sync,next,c,depth);
            if (s instanceof AssertStmt a) {
                String enabled=ast("condition","Проверки assert включены?",a,null,"Без -ea JVM пропускает assert.");
                String test=decision(a.getCheck(),c,null,depth);
                String fail=ast("throw","throw AssertionError",a,null,"При ложном условии assert вычисляется сообщение и выбрасывается AssertionError.");
                edge(fail,c.exception(),"AssertionError","exception");
                String message=a.getMessage().map(e->expression(e,fail,c,depth+1,true)).orElse(fail);
                edge(test,next,"Да"); edge(test,message,"Нет");
                edge(enabled,expression(a.getCheck(),test,c,depth+1,false),"Да"); edge(enabled,next,"Нет");
                return enabled;
            }
            if (s instanceof LocalClassDeclarationStmt || s instanceof LocalRecordDeclarationStmt || s instanceof EmptyStmt) return next;
            if (s instanceof ExpressionStmt e) return operation("operation",s.toString(),s,next,c,e.getExpression(),depth);
            if (s instanceof ExplicitConstructorInvocationStmt invocation) {
                String node=operation("operation",s.toString(),s,next,c,null,depth);
                edge(node,c.exception(),"Конструктор бросил исключение","exception");
                List<Expression> arguments=new ArrayList<>();
                invocation.getExpression().ifPresent(arguments::add); arguments.addAll(invocation.getArguments());
                String entry=node;
                if (arguments.stream().anyMatch(this::containsSwitch))
                    for (int i=arguments.size()-1;i>=0;i--) entry=expression(arguments.get(i),entry,c,depth+1,true);
                return entry;
            }
            throw new OverviewRequired("Нераскрытый оператор: "+s.getClass().getSimpleName());
        }

        String tryFlow(TryStmt t,String next,Context outer,int depth) {
            Map<String,String> routes=new HashMap<>();
            BiFunction<String,String,String> finish=(target,cause)-> {
                if (target==null || t.getFinallyBlock().isEmpty()) return target;
                return routes.computeIfAbsent(cause+":"+target,key-> {
                    BlockStmt fin=t.getFinallyBlock().orElseThrow();
                    String body=statement(fin,target,outer,depth+1);
                    String gate=node("finally","finally · "+cause,pointAtStart(fin),null,fin,fin.toString(),
                            "Этот маршрут выполняет общий finally перед: "+cause+". Если finally завершится резко, прежний маршрут заменяется.");
                    edge(gate,body,""); return gate;
                });
            };
            Context catchContext=outer.route(finish);
            String normal=finish.apply(next,"обычный выход");
            String dispatch=catchContext.exception();
            for (int i=t.getCatchClauses().size()-1;i>=0;i--) {
                CatchClause clause=t.getCatchClauses().get(i);
                String test=node("catch","catch ("+clause.getParameter()+")",range(clause.getParameter()),null,clause.getBody(),
                        "catch ("+clause.getParameter()+")","Проверка типа исключения. Первый подходящий catch выполняется, последующие пропускаются. Исключение из тела catch не идёт в соседний catch.");
                edge(test,statement(clause.getBody(),normal,catchContext,depth+1),"Подходит","exception");
                edge(test,dispatch,"Не подходит","exception"); dispatch=test;
            }
            Context tryContext=catchContext.catching(dispatch);
            String entry=resources(t,0,normal,tryContext,depth+1);
            String gate=ast("try","try"+(t.getResources().isEmpty()?"":" · с ресурсами"),t,t,
                    "Исключение из try проверяется обработчиками catch в порядке записи. Ресурсы закрываются до catch/finally.");
            edge(gate,entry,""); return gate;
        }

        // Ресурсы моделируются вложенными областями: открываются слева направо,
        // закрываются справа налево. При ошибке открытия закрыты только уже открытые.
        String resources(TryStmt t,int index,String next,Context c,int depth) {
            if (index==t.getResources().size()) return statement(t.getTryBlock(),next,c,depth+1);
            if (depth>100) throw new OverviewRequired("Слишком глубокая вложенность ресурсов.");
            Expression resource=t.getResources().get(index);
            String name=resource instanceof VariableDeclarationExpr v
                    ? v.getVariable(0).getNameAsString() : resource.toString();
            Map<String,String> routes=new HashMap<>();
            BiFunction<String,String,String> close=(target,cause)-> {
                if (target==null) return null;
                return routes.computeIfAbsent(cause+":"+target,key-> {
                    boolean pendingException=cause.equals("исключение");
                    String node=node("resource","close("+name+") · "+cause,range(resource),null,null,resource.toString(),
                            "Закрыть ресурс, если он не null. "+(pendingException
                                    ? "Исходное исключение сохраняется; ошибка close добавляется к нему как suppressed."
                                    : "Ошибка close отменяет обычный выход/return и передаётся обработчику. Остальные ресурсы тоже закрываются."));
                    edge(node,target,"Закрыт / null");
                    if (pendingException) {
                        String suppressed=node("resource","Добавить suppressed",range(resource),null,null,
                                "primaryException.addSuppressed(closeException)","Сохранить исходное исключение и присоединить ошибку закрытия.");
                        edge(node,suppressed,"Ошибка close","exception"); edge(suppressed,target,"Исходное исключение","exception");
                    } else edge(node,c.exception(),"Ошибка close","exception");
                    return node;
                });
            };
            Context inside=c.route(close);
            String normal=close.apply(next,"обычный выход");
            String following=resources(t,index+1,normal,inside,depth+1);
            String open=node("resource","Открыть ресурс: "+resource,range(resource),null,null,resource.toString(),
                    "Если инициализация не удалась, этот ресурс не закрывается; ранее открытые ресурсы закрываются.");
            edge(open,following,"Успешно"); possibleException(open,c,resource);
            return expression(resource,open,c,depth+1,false);
        }

        String synchronizedFlow(SynchronizedStmt s,String next,Context outer,int depth) {
            Map<String,String> routes=new HashMap<>();
            BiFunction<String,String,String> release=(target,cause)-> {
                if (target==null) return null;
                return routes.computeIfAbsent(cause+":"+target,key-> {
                    String releaseNode=node("resource","Освободить монитор · "+cause,range(s.getExpression()),null,null,
                            s.getExpression().toString(),"Монитор освобождается при обычном и резком выходе из synchronized.");
                    edge(releaseNode,target,""); return releaseNode;
                });
            };
            String body=statement(s.getBody(),release.apply(next,"обычный выход"),outer.route(release),depth+1);
            String acquire=ast("resource","synchronized ("+s.getExpression()+")",s.getExpression(),s,"Получить монитор объекта, затем войти в тело.");
            edge(acquire,body,"Монитор получен"); possibleException(acquire,outer,s.getExpression());
            return expression(s.getExpression(),acquire,outer,depth+1,false);
        }

        String switchFlow(Expression selector,NodeList<SwitchEntry> entries,Node sw,String next,Context c,int depth,boolean value) {
            String dispatch=ast("switch","switch ("+selector+")",selector,sw,
                    value?"Выбрать ветку switch-выражения и получить значение через -> или yield."
                            :"Выбрать case/default. В форме case: без break выполнение продолжается в следующей группе; форма -> не проваливается дальше.");
            possibleException(dispatch,c,selector);
            String following=next;
            List<String> caseNodes=new ArrayList<>();
            for (int i=entries.size()-1;i>=0;i--) {
                SwitchEntry entry=entries.get(i);
                boolean colon=entry.getType()==SwitchEntry.Type.STATEMENT_GROUP;
                String continuation=colon?following:next;
                String body;
                if (value && entry.getType()==SwitchEntry.Type.EXPRESSION && !entry.getStatements().isEmpty()
                        && entry.getStatement(0) instanceof ExpressionStmt expr) {
                    String yield=node("yield","Значение: "+expr.getExpression(),range(expr.getExpression()),null,null,expr.getExpression().toString(),
                            "Результат ветки -> становится значением switch-выражения.");
                    edge(yield,next,"значение"); possibleException(yield,c,expr.getExpression());
                    body=expression(expr.getExpression(),yield,c,depth+1,false);
                } else body=sequence(entry.getStatements(),continuation,value?c.yielding(next):c,depth+1);
                String label=entry.getLabels().isEmpty()?"default":"case "+entry.getLabels().stream().map(Object::toString).collect(Collectors.joining(", "));
                if (entry.isDefault() && !entry.getLabels().isEmpty()) label+=", default";
                if (entry.getGuard().isPresent()) label+=" when "+entry.getGuard().orElseThrow();
                String caseNode=node("case",label,range(entry),null,entry,label,
                        colon?"Группа case: без break может перейти в следующую группу.":"Ветка -> завершается без перехода в следующий case.");
                edge(caseNode,body,""); following=caseNode; caseNodes.add(0,caseNode);
            }
            // Диспетчер выбора — атомарная операция Java. Включает сравнение labels,
            // pattern matching и when по правилам switch; вход — только в выбранную ветку.
            for (int i=0;i<entries.size();i++) edge(dispatch,caseNodes.get(i),
                    entries.get(i).isDefault() || entries.get(i).getLabels().isEmpty()?"default":"case "+entries.get(i).getLabels().stream().map(Object::toString).collect(Collectors.joining(", ")));
            boolean hasDefault=entries.stream().anyMatch(e->e.isDefault() || e.getLabels().isEmpty());
            boolean enhanced=entries.stream().anyMatch(e->e.getLabels().stream().anyMatch(x->x instanceof PatternExpr || x instanceof NullLiteralExpr));
            if (!hasDefault) {
                if (value || enhanced) edge(dispatch,c.exception(),"Нет подходящей ветки / MatchException","exception");
                else edge(dispatch,next,"Нет совпадения");
            }
            return expression(selector,dispatch,c,depth+1,false);
        }

        /** Раскрываются только выражения со switch; обычные выражения сохраняют полный Java-текст. */
        String expression(Expression e,String next,Context c,int depth,boolean force) {
            if (depth>100) throw new OverviewRequired("Слишком глубокая вложенность выражений.");
            if (!containsSwitch(e)) {
                if (!force) return next;
                String atom=node("evaluation","Вычислить: "+e,range(e),null,null,e.toString(),
                        "Часть выражения; результат используется следующим шагом. Внутренние операции и short-circuit обычного выражения задаёт этот Java-текст.");
                edge(atom,next,""); possibleException(atom,c,e); return atom;
            }
            if (e instanceof SwitchExpr sw) return switchFlow(sw.getSelector(),sw.getEntries(),sw,next,c.yielding(next),depth+1,true);
            if (e instanceof ConditionalExpr ternary) {
                String test=node("condition",ternary.getCondition().toString(),range(ternary.getCondition()),null,null,ternary.getCondition().toString(),"Оператор ?: выполняет только выбранную ветку.");
                edge(test,expression(ternary.getThenExpr(),next,c,depth+1,true),"Да");
                edge(test,expression(ternary.getElseExpr(),next,c,depth+1,true),"Нет");
                return expression(ternary.getCondition(),test,c,depth+1,true);
            }
            if (e instanceof BinaryExpr binary && (binary.getOperator()==BinaryExpr.Operator.AND || binary.getOperator()==BinaryExpr.Operator.OR)) {
                String test=node("condition","Результат: "+binary.getLeft(),range(binary.getLeft()),null,null,binary.getLeft().toString(),
                        "Сокращённое вычисление "+binary.getOperator().asString()+": правая часть вычисляется только при необходимости.");
                String right=expression(binary.getRight(),next,c,depth+1,true);
                boolean and=binary.getOperator()==BinaryExpr.Operator.AND;
                edge(test,and?right:next,"Да"); edge(test,and?next:right,"Нет");
                return expression(binary.getLeft(),test,c,depth+1,true);
            }
            List<Expression> children=evaluationChildren(e);
            String entry=next;
            if (force) {
                entry=node("evaluation","Вычислить: "+e,range(e),null,null,e.toString(),
                        "Применить операцию к уже вычисленным частям выражения. Раскрытый switch повторно не выполняется.");
                edge(entry,next,""); possibleException(entry,c,e);
            }
            for (int i=children.size()-1;i>=0;i--) entry=expression(children.get(i),entry,c,depth+1,true);
            return entry;
        }
        List<Expression> evaluationChildren(Expression e) {
            List<Expression> list=new ArrayList<>();
            if (e instanceof MethodCallExpr m) { m.getScope().ifPresent(list::add); list.addAll(m.getArguments()); }
            else if (e instanceof ObjectCreationExpr o) { o.getScope().ifPresent(list::add); list.addAll(o.getArguments()); }
            else if (e instanceof BinaryExpr b) { list.add(b.getLeft()); list.add(b.getRight()); }
            else if (e instanceof AssignExpr a) { list.add(a.getTarget()); list.add(a.getValue()); }
            else if (e instanceof ArrayAccessExpr a) { list.add(a.getName()); list.add(a.getIndex()); }
            else if (e instanceof ArrayCreationExpr a) { a.getLevels().forEach(l->l.getDimension().ifPresent(list::add)); a.getInitializer().ifPresent(list::add); }
            else if (e instanceof ArrayInitializerExpr a) list.addAll(a.getValues());
            else if (e instanceof VariableDeclarationExpr v) v.getVariables().forEach(x->x.getInitializer().ifPresent(list::add));
            else if (e instanceof EnclosedExpr p) list.add(p.getInner());
            else if (e instanceof UnaryExpr u) list.add(u.getExpression());
            else if (e instanceof CastExpr c) list.add(c.getExpression());
            else if (e instanceof InstanceOfExpr i) list.add(i.getExpression());
            else if (e instanceof FieldAccessExpr f) list.add(f.getScope());
            else for (Node child:e.getChildNodes()) if (child instanceof Expression expression) list.add(expression);
            return list;
        }
        boolean containsSwitch(Node n) {
            if (n==null || n instanceof LambdaExpr || n instanceof BodyDeclaration<?>) return false;
            if (n instanceof SwitchExpr) return true;
            return n.getChildNodes().stream().anyMatch(this::containsSwitch);
        }
        void keepReachable(String start) {
            Map<String,List<String>> outgoing=new HashMap<>();
            edges.forEach(e->outgoing.computeIfAbsent(e.source(),ignored->new ArrayList<>()).add(e.target()));
            Set<String> visited=new HashSet<>(); Deque<String> pending=new ArrayDeque<>(); pending.push(start);
            while (!pending.isEmpty()) { String id=pending.pop(); if (visited.add(id)) outgoing.getOrDefault(id,List.of()).forEach(pending::push); }
            nodes.removeIf(n->!visited.contains(n.id()));
            edges.removeIf(e->!visited.contains(e.source()) || !visited.contains(e.target()));
        }
    }
    private static boolean isTrue(Expression e) {
        if (e instanceof EnclosedExpr p) return isTrue(p.getInner());
        return e instanceof BooleanLiteralExpr b && b.getValue();
    }
    private static boolean isFalse(Expression e) {
        if (e instanceof EnclosedExpr p) return isFalse(p.getInner());
        return e instanceof BooleanLiteralExpr b && !b.getValue();
    }
    static SourceRange range(Node node) {
        var r=node.getRange().orElseThrow(); return new SourceRange(r.begin.line,r.begin.column,r.end.line,r.end.column);
    }
    static SourceRange pointAtStart(Node node) {
        var p=node.getBegin().orElseThrow(); return new SourceRange(p.line,p.column,p.line,p.column);
    }
    static SourceRange pointAtEnd(Node node) {
        var p=node.getEnd().orElseThrow(); return new SourceRange(p.line,p.column,p.line,p.column);
    }
}
