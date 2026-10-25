package dev.blueprints.flow;

import dev.blueprints.roundtrip.RoundTripService;
import dev.blueprints.check.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import static dev.blueprints.flow.FlowModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedFlowGraphTest {
    final RoundTripService converter=new RoundTripService();
    MethodFlow flow(String statements) { return converter.convert("A.java","class A {void f(){"+statements+"}}").methods().getFirst(); }
    FlowNode node(MethodFlow f,String label) { return f.nodes().stream().filter(n->n.label().equals(label)).findFirst().orElseThrow(()->new AssertionError("Missing "+label+" in "+f.nodes().stream().map(FlowNode::label).toList())); }
    FlowNode kind(MethodFlow f,String kind) { return f.nodes().stream().filter(n->n.kind().equals(kind)).findFirst().orElseThrow(); }
    boolean edge(MethodFlow f,FlowNode from,FlowNode to) { return f.edges().stream().anyMatch(e->e.source().equals(from.id()) && e.target().equals(to.id())); }
    boolean path(MethodFlow f,FlowNode from,FlowNode to,boolean exceptions) {
        Set<String> seen=new HashSet<>(); Deque<String> queue=new ArrayDeque<>();queue.add(from.id());
        while(!queue.isEmpty()) {String id=queue.remove(); if(id.equals(to.id()))return true; if(!seen.add(id))continue;
            f.edges().stream().filter(e->e.source().equals(id) && (exceptions || !e.kind().equals("exception"))).forEach(e->queue.add(e.target()));}
        return false;
    }
    FlowNode destination(MethodFlow f,FlowNode from,String label) {
        var edge=f.edges().stream().filter(e->e.source().equals(from.id()) && e.label().equals(label)).findFirst().orElseThrow();
        return f.nodes().stream().filter(n->n.id().equals(edge.target())).findFirst().orElseThrow();
    }
    void consistent(MethodFlow flow) {
        assertEquals("flow",flow.mode(),flow.warnings().toString());
        Set<String> ids=new HashSet<>(); flow.nodes().forEach(n->assertTrue(ids.add(n.id())));
        flow.edges().forEach(e->{assertTrue(ids.contains(e.source()));assertTrue(ids.contains(e.target()));});
    }
    @Test void colonSwitchFallsThroughButBreakExitsSwitchOnly() {
        var f=flow("while(ready){switch(x){case 0:one();case 1:two();break;default:other();}afterSwitch();}done();");
        assertTrue(edge(f,node(f,"one();"),node(f,"case 1")));
        assertTrue(edge(f,node(f,"break"),node(f,"afterSwitch();")));
        assertFalse(edge(f,node(f,"break"),node(f,"done();")));
        consistent(f);
    }
    @Test void arrowCasesDoNotFallThroughAndMissingDefaultHasExit() {
        var f=flow("switch(x){case 1 -> one();case 2 -> two();}done();");
        assertTrue(edge(f,node(f,"one();"),node(f,"done();")));
        assertFalse(path(f,node(f,"one();"),node(f,"two();"),false));
        assertEquals(node(f,"done();"),destination(f,kind(f,"switch"),"Нет совпадения"));
    }
    @Test void continueInsideSwitchStillTargetsLoopUpdate() {
        var f=flow("for(int i=0;i<4;i++){switch(i){case 1:continue;default:work();}}done();");
        assertTrue(edge(f,node(f,"continue"),node(f,"i++")));
    }
    @Test void switchExpressionShowsYieldAndConsumer() {
        var f=flow("int value=switch(x){case 1 -> 10;default -> {work();yield 20;}};done();");
        assertTrue(path(f,node(f,"yield 20;"),node(f,"done();"),false));
        assertTrue(f.nodes().stream().anyMatch(n->n.label().equals("Значение: 10")));
        consistent(f);
    }
    @Test void switchInCallArgumentsPreservesLeftToRightOrder() {
        var f=flow("use(first(),switch(x){default -> second();},last());done();");
        assertTrue(path(f,node(f,"Вычислить: first()"),kind(f,"switch"),false));
        assertTrue(path(f,node(f,"Значение: second()"),node(f,"Вычислить: last()"),false));
        assertFalse(path(f,node(f,"Вычислить: last()"),kind(f,"switch"),false));
    }
    @Test void nestedCallUsingSwitchRunsBeforeFollowingArgument() {
        var f=flow("use(inner(switch(x){default -> 1;}),last());");
        var inner=node(f,"Вычислить: inner(switch(x) { default -> 1; })");
        assertTrue(path(f,kind(f,"switch"),inner,false));
        assertTrue(path(f,inner,node(f,"Вычислить: last()"),false));
        assertFalse(path(f,node(f,"Вычислить: last()"),inner,false));
    }
    @Test void constructorDelegationExpandsSwitchArguments() {
        var response=converter.convert("A.java","class A {A(int x){this(switch(x){default -> 1;},last());} A(int x,int y){}}");
        var f=response.methods().getFirst();
        assertTrue(path(f,kind(f,"switch"),node(f,"Вычислить: last()"),false));
        consistent(f);
    }
    @Test void switchInShortCircuitOperandIsConditional() {
        var f=flow("boolean value=ready() && switch(x){default -> true;};done();");
        FlowNode gate=node(f,"Результат: ready()");
        assertTrue(path(f,destination(f,gate,"Да"),kind(f,"switch"),false));
        assertFalse(path(f,destination(f,gate,"Нет"),kind(f,"switch"),false));
    }
    @Test void switchInTernaryOnlyRunsChosenBranch() {
        var f=flow("int value=ready ? switch(x){default -> 1;} : other();done();");
        FlowNode gate=node(f,"ready");
        assertTrue(path(f,destination(f,gate,"Да"),kind(f,"switch"),false));
        assertFalse(path(f,destination(f,gate,"Нет"),kind(f,"switch"),false));
    }
    @Test void switchInWhileConditionIsReevaluatedOnContinue() {
        var f=flow("while(switch(x){default -> ready;}){continue;}done();");
        assertEquals(kind(f,"switch"),destination(f,node(f,"continue"),""));
    }
    @Test void returnAndThrowGoThroughFinallyWithDifferentDestinations() {
        var f=flow("try{if(flag)return;throw problem;}finally{cleanup();}unreachable();");
        var ret=node(f,"return;");
        var retFin=destination(f,ret,"");
        var exFin=destination(f,node(f,"throw problem;"),"throw");
        assertEquals("finally",retFin.kind());assertEquals("finally",exFin.kind());
        assertNotEquals(retFin.id(),exFin.id());
        assertTrue(path(f,retFin,kind(f,"end"),false));
        assertFalse(path(f,exFin,kind(f,"end"),false));
        assertTrue(path(f,exFin,kind(f,"exception-end"),false));
        assertFalse(f.nodes().stream().anyMatch(n->n.label().equals("unreachable();")));
    }
    @Test void exceptionFromCatchBypassesSiblingHandlersButRunsFinally() {
        var f=flow("try{work();}catch(IOException e){throw other;}catch(Exception e){recover();}finally{cleanup();}done();");
        var thrown=node(f,"throw other;");
        assertEquals("finally",destination(f,thrown,"throw").kind());
        assertFalse(path(f,thrown,node(f,"recover();"),true));
        var first=node(f,"catch (IOException e)");
        assertEquals(node(f,"catch (Exception e)"),destination(f,first,"Не подходит"));
    }
    @Test void finallyReturnOverridesPendingReturnOrException() {
        var f=flow("try{throw problem;}finally{return;}after();");
        var fin=destination(f,node(f,"throw problem;"),"throw");
        assertTrue(path(f,fin,kind(f,"end"),false));
        assertFalse(f.nodes().stream().anyMatch(n->n.label().equals("after();")));
        assertFalse(f.nodes().stream().anyMatch(n->n.kind().equals("exception-end")));
    }
    @Test void nestedFinallyRunsInnerThenOuter() {
        var f=flow("try{try{return;}finally{inner();}}finally{outer();}");
        var ret=node(f,"return;");
        assertTrue(path(f,ret,node(f,"inner();"),false));
        assertTrue(path(f,node(f,"inner();"),node(f,"outer();"),false));
        assertFalse(path(f,node(f,"outer();"),node(f,"inner();"),false));
    }
    @Test void breakAndContinueCrossFinallyWhenLeavingTry() {
        var f=flow("for(int i=0;i<3;i++){try{if(flag)continue;break;}finally{cleanup();}}done();");
        var cont=destination(f,node(f,"continue"),"");
        var brk=destination(f,node(f,"break"),"");
        assertEquals("finally",cont.kind());assertEquals("finally",brk.kind());
        assertTrue(path(f,cont,node(f,"i++"),false));
        assertTrue(path(f,brk,node(f,"done();"),false));
        assertFalse(path(f,brk,node(f,"i++"),false));
    }
    @Test void breakOfInnerLoopDoesNotPrematurelyRunFinally() {
        var f=flow("try{while(ready){break;}inside();}finally{cleanup();}");
        assertEquals(node(f,"inside();"),destination(f,node(f,"break"),""));
    }
    @Test void labeledContinueAndBreakCrossFinally() {
        var f=flow("outer:while(ready){try{if(flag)continue outer;break outer;}finally{cleanup();}}done();");
        assertEquals("finally",destination(f,node(f,"break outer"),"").kind());
        assertEquals("finally",destination(f,node(f,"continue outer"),"").kind());
        consistent(f);
    }
    @Test void resourceInitializationFailureClosesOnlyEarlierResources() {
        var f=flow("try(R a=openA();R b=openB()){work();}catch(Exception e){recover();}");
        var openB=f.nodes().stream().filter(n->n.label().startsWith("Открыть ресурс: R b")).findFirst().orElseThrow();
        var onFailure=destination(f,openB,"Возможно исключение");
        assertEquals("close(a) · исключение",onFailure.label());
        assertFalse(path(f,onFailure,node(f,"close(b) · исключение"),false));
    }
    @Test void resourcesCloseInReverseOrderBeforeCatchAndFinally() {
        var f=flow("try(R a=openA();R b=openB()){throw problem;}catch(Exception e){recover();}finally{finish();}");
        var b=destination(f,node(f,"throw problem;"),"throw");
        assertEquals("close(b) · исключение",b.label());
        var a=destination(f,b,"Закрыт / null");assertEquals("close(a) · исключение",a.label());
        assertEquals("catch",destination(f,a,"Закрыт / null").kind());
        assertTrue(f.nodes().stream().anyMatch(n->n.label().equals("Добавить suppressed")));
        assertTrue(f.nodes().stream().filter(n->n.label().equals("finish();")).anyMatch(n->path(f,node(f,"recover();"),n,false)));
    }
    @Test void resourceCloseFailureOverridesReturnAndReachesCatch() {
        var f=flow("try(R a=open()){return;}catch(Exception e){recover();}");
        var closing=destination(f,node(f,"return;"),"");
        assertEquals("close(a) · return",closing.label());
        assertEquals("catch",destination(f,closing,"Ошибка close").kind());
    }
    @Test void literalInfiniteWhileDoesNotInventExit() {
        var f=flow("while(true){work();}after();");
        assertFalse(f.nodes().stream().anyMatch(n->n.label().equals("after();") || n.kind().equals("end")));
    }
    @Test void nullSwitchAndMultiCatchAreVisible() {
        var f=flow("try{switch(value){case null -> work();default -> done();}}catch(IOException | RuntimeException e){recover();}");
        assertTrue(f.nodes().stream().anyMatch(n->n.label().equals("case null")));
        assertTrue(f.nodes().stream().anyMatch(n->n.label().contains("IOException | RuntimeException")));
        consistent(f);
    }
    @Test void fieldsTypesInheritanceThrowsAndStaticInitializersHaveGraphNodes() {
        String code="interface I {int f();} class Problem extends Exception{} class A implements I {static int count=1;String[] names;static{count++;} A(){} public int f(){return count;} void checked() throws Problem {throw new Problem();}}";
        var response=converter.convert("A.java",code);var graph=response.structure();
        assertTrue(graph.nodes().stream().anyMatch(n->n.kind().equals("interface")));
        assertTrue(graph.nodes().stream().anyMatch(n->n.kind().equals("field") && n.code().contains("static int count")));
        assertTrue(graph.nodes().stream().anyMatch(n->n.kind().equals("field") && n.code().contains("String[]")));
        assertTrue(graph.edges().stream().anyMatch(e->e.label().equals("implements")));
        assertTrue(graph.edges().stream().anyMatch(e->e.label().equals("extends")));
        assertTrue(graph.edges().stream().anyMatch(e->e.label().equals("throws")));
        assertTrue(response.methods().stream().anyMatch(m->m.kind().equals("initializer") && m.signature().contains("static")));
        for(var n:graph.nodes()) if(n.targetMethodId()!=null) assertTrue(response.methods().stream().anyMatch(m->m.id().equals(n.targetMethodId())));
        var cls=graph.nodes().stream().filter(n->n.kind().equals("class") && n.code().contains("class A")).findFirst().orElseThrow();
        assertTrue(graph.edges().stream().anyMatch(e->e.source().equals(cls.id()) && e.label().equals("содержит")));
    }
    @Test void consoleCollectionsAndLongExpressionsRetainFullTextAndRanges() {
        String text="System.out.println(\""+"Я".repeat(180)+"\");";
        var f=flow("Scanner scanner=new Scanner(System.in,StandardCharsets.UTF_8);List<String> names=new ArrayList<>();names.add(scanner.nextLine());"+text);
        assertTrue(f.nodes().stream().anyMatch(n->n.category().equals("input")));
        assertTrue(f.nodes().stream().anyMatch(n->n.category().equals("collections")));
        var print=f.nodes().stream().filter(n->n.code().equals(text)).findFirst().orElseThrow();
        assertTrue(print.label().endsWith("…"));assertEquals(text,print.code());
        assertTrue(print.range().startColumn()>1);
    }
    @Test void insertingIntoColonCaseKeepsVariableScope() {
        String code="class A {void f(int mode){switch(mode){case 0:int x=1;use(x);break;default:break;}}}";
        var before=converter.convert("A.java",code);var m=before.methods().getFirst();var variable=node(m,"int x = 1;");
        var after=new BlockInsertService(converter).insert(new BlockInsertModels.Request("A.java",code,before.revision(),m.id(),variable.id(),"after","operation","x += 1;"));
        assertTrue(after.source().contains("x += 1;"));
        assertFalse(after.source().matches("(?s).*case 0:\\s*\\{.*"));
    }
    @Test void editsSwitchSelectorAndActionInFinally() {
        String code="class A {void f(){try{switch(x){default:work();}}finally{cleanup();}}}";
        var before=converter.convert("A.java",code);var m=before.methods().getFirst();var sw=kind(m,"switch");
        var edit=new BlockEditService(converter);
        var after=edit.edit(new BlockEditModels.Request("A.java",code,before.revision(),m.id(),sw.id(),"other"));
        assertTrue(after.source().contains("switch(other)") || after.source().contains("switch (other)"));
        var updated=after.conversion().methods().getFirst();var cleanup=node(updated,"cleanup();");
        var changed=edit.edit(new BlockEditModels.Request("A.java",after.source(),after.conversion().revision(),updated.id(),cleanup.id(),"finish();"));
        assertTrue(changed.source().contains("finish();"));assertFalse(changed.source().contains("cleanup();"));
    }
    @Test void compilerRequiresCatchOrThrowsOnlyForCheckedExceptions() {
        var checker=new CheckService();
        var missing=checker.check(new CheckModels.Request("A.java","class A {void f(){throw new java.io.IOException();}}","",""),false);
        assertEquals("compile_error",missing.status());
        assertFalse(missing.diagnostics().isEmpty());
        var declared=checker.check(new CheckModels.Request("A.java","class A {void f() throws java.io.IOException {throw new java.io.IOException();}}","",""),false);
        assertEquals("compiled",declared.status(),declared.diagnostics().toString());
        var unchecked=checker.check(new CheckModels.Request("A.java","class A {void f(){throw new IllegalArgumentException();}}","",""),false);
        assertEquals("compiled",unchecked.status(),unchecked.diagnostics().toString());
    }
    @Test void comprehensiveExampleParsesAllMethodsAndActuallyPassesJunit() throws Exception {
        String source=Files.readString(Path.of("../examples/JavaFeatures.java"));
        String tests=Files.readString(Path.of("../examples/JavaFeaturesTest.java"));
        var result=converter.convert("JavaFeatures.java",source);
        result.methods().stream().filter(m->!m.mode().equals("no-body")).forEach(this::consistent);
        assertTrue(result.structureEquivalent());
        var checked=new CheckService().check(new CheckModels.Request("JavaFeatures.java",source,"JavaFeaturesTest.java",tests),true);
        assertEquals("passed",checked.status(),checked.output()+checked.tests()+checked.diagnostics());
        assertEquals(6,checked.passed());
    }
}
