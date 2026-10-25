package dev.blueprints.flow;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import dev.blueprints.roundtrip.RoundTripService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.blueprints.flow.FlowModels.*;
import static org.junit.jupiter.api.Assertions.*;

class FlowGraphBuilderTest {
    private List<MethodFlow> methods(String source) {
        var parsed = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(source);
        assertTrue(parsed.isSuccessful(), parsed.getProblems().toString());
        return new FlowGraphBuilder().build(parsed.getResult().orElseThrow());
    }
    private MethodFlow body(String source) { return methods("class A { void run() {" + source + "} }").getFirst(); }
    private FlowNode node(MethodFlow flow, String label) {
        return flow.nodes().stream().filter(n -> n.label().equals(label)).findFirst().orElseThrow(
                () -> new AssertionError("Missing " + label + " in " + flow.nodes()));
    }
    private FlowNode kind(MethodFlow flow, String kind) {
        return flow.nodes().stream().filter(n -> n.kind().equals(kind)).findFirst().orElseThrow();
    }
    private boolean connected(MethodFlow flow, FlowNode from, FlowNode to, String label) {
        return flow.edges().stream().anyMatch(e -> e.source().equals(from.id())
                && e.target().equals(to.id()) && e.label().equals(label));
    }

    @Test void keepsSequentialOrder() {
        var f = body("first(); second();");
        assertTrue(connected(f, kind(f, "start"), node(f, "first();"), ""));
        assertTrue(connected(f, node(f, "first();"), node(f, "second();"), ""));
        assertTrue(connected(f, node(f, "second();"), kind(f, "end"), ""));
    }

    @Test void branchesRejoinAndEarlyReturnDoesNotFallThrough() {
        var f = body("if (ready) { return; } else { work(); } done();");
        assertTrue(connected(f, node(f, "ready"), node(f, "return;"), "Да"));
        assertTrue(connected(f, node(f, "ready"), node(f, "work();"), "Нет"));
        assertTrue(connected(f, node(f, "return;"), kind(f, "end"), ""));
        assertTrue(connected(f, node(f, "work();"), node(f, "done();"), ""));
        assertFalse(connected(f, node(f, "return;"), node(f, "done();"), ""));
    }

    @Test void emptyBranchesKeepTwoDifferentLabels() {
        var f = body("if (ready) {} done();");
        assertTrue(connected(f, node(f, "ready"), node(f, "done();"), "Да"));
        assertTrue(connected(f, node(f, "ready"), node(f, "done();"), "Нет"));
    }

    @Test void forContinueRunsUpdateAndBreakSkipsIt() {
        var f = body("for(int i=0;i<4;i++){if(i==1)continue;if(i==2)break;work();}done();");
        assertTrue(connected(f, node(f, "continue"), node(f, "i++"), ""));
        assertTrue(connected(f, node(f, "break"), node(f, "done();"), ""));
        assertTrue(connected(f, node(f, "i++"), node(f, "i < 4"), ""));
        assertTrue(connected(f, node(f, "int i = 0"), node(f, "i < 4"), ""));
        assertTrue(connected(f, node(f, "work();"), node(f, "i++"), ""));
    }

    @Test void whileContinueReturnsToCondition() {
        var f = body("while(ready){if(skip)continue;work();}done();");
        assertTrue(connected(f, node(f, "continue"), node(f, "ready"), ""));
        assertTrue(connected(f, node(f, "ready"), node(f, "done();"), "Нет"));
    }

    @Test void nestedBreakOnlyExitsInnerLoop() {
        var f = body("while(outer){while(inner){break;}nextOuter();}done();");
        assertTrue(connected(f, node(f, "break"), node(f, "nextOuter();"), ""));
        assertFalse(connected(f, node(f, "break"), node(f, "done();"), ""));
    }

    @Test void doWhileRunsBodyBeforeChecking() {
        var f = body("do { work(); } while(ready); done();");
        assertTrue(connected(f, kind(f, "start"), node(f, "work();"), ""));
        assertTrue(connected(f, node(f, "work();"), node(f, "ready"), ""));
        assertTrue(connected(f, node(f, "ready"), node(f, "work();"), "Да"));
    }

    @Test void foreachHasIterationAndExit() {
        var f = body("for(int price:prices){work();}done();");
        var loop = node(f, "for (int price : prices)");
        assertTrue(connected(f, loop, node(f, "work();"), "Есть элемент"));
        assertTrue(connected(f, loop, node(f, "done();"), "Конец"));
        assertTrue(connected(f, node(f, "work();"), loop, ""));
    }

    @Test void infiniteForHasNoInventedExit() {
        var f = body("for(;;){work();}");
        assertEquals(0, f.nodes().stream().filter(n -> n.kind().equals("end")).count());
        assertEquals(0, f.edges().stream().filter(e -> e.label().equals("Нет")).count());
    }

    @Test void throwIsTerminalAndUnreachableStepsAreOmitted() {
        var f = body("throw new RuntimeException(); later();");
        var throwing = kind(f, "throw");
        assertTrue(connected(f, throwing, kind(f, "exception-end"), "throw"));
        assertTrue(f.edges().stream().filter(e -> e.source().equals(throwing.id())).allMatch(e -> e.kind().equals("exception")));
        assertTrue(f.nodes().stream().noneMatch(n -> n.label().contains("later")));
    }

    @Test void overloadsConstructorsAndNestedOwnersHaveDistinctIds() {
        var list = methods("class A { A(){} void f(int x){} void f(String x){} class B { void f(){} } }");
        assertEquals(4, list.size());
        assertEquals(4, list.stream().map(MethodFlow::id).distinct().count());
        assertEquals("constructor", list.getFirst().kind());
        assertEquals("f(int)", list.get(1).signature());
        assertEquals("f(String)", list.get(2).signature());
        assertEquals("A.B", list.getLast().owner());
    }

    @Test void abstractMethodsAndFilesWithoutMethodsHaveHonestEmptyStates() {
        var f = methods("abstract class A { abstract void f(); }").getFirst();
        assertEquals("no-body", f.mode());
        assertTrue(f.nodes().isEmpty());
        assertTrue(methods("class A { int value; }").isEmpty());
    }

    @Test void complexControlFlowIsExpanded() {
        for (String statement : List.of("try { work(); } finally { cleanup(); }",
                "switch(x){case 1: work(); break; default: break;}", "label: while(true){break label;}",
                "synchronized(this){work();}", "int x = switch(y){default -> 1;};")) {
            var f = body(statement);
            assertEquals("flow", f.mode(), statement);
            assertTrue(f.nodes().size() > 2);
            assertFalse(f.edges().isEmpty());
        }
    }

    @Test void lambdasAndLocalTypesDoNotLeakTheirReturnsIntoOuterMethod() {
        var f = body("Runnable r = () -> { return; }; class B { void nested(){return;} } work();");
        assertEquals("flow", f.mode());
        assertTrue(f.nodes().stream().noneMatch(n -> n.kind().equals("return")));
        assertEquals(1, f.nodes().stream().filter(n -> n.label().equals("work();")).count());
    }

    @Test void sourceRangesUseOriginalLinesAndColumnsIncludingCrlf() {
        var response = new RoundTripService().convert("A.java",
                "class A {\r\n\r\n  void run() {\r\n    if (ready) {\r\n      work();\r\n    }\r\n  }\r\n}");
        var f = response.methods().getFirst();
        assertEquals(new SourceRange(5, 7, 5, 13), node(f, "work();").range());
        assertEquals(new SourceRange(4, 9, 4, 13), node(f, "ready").range());
    }

    @Test void largeMethodsAreBoundedAndEdgesAlwaysReferenceExistingBlocks() {
        assertEquals("overview", body("work();".repeat(351)).mode());
        var f = body("for(int i=0;i<9;i++){if(i>3)break;while(ready){if(skip)continue;work();}}done();");
        Set<String> ids = new HashSet<>();
        f.nodes().forEach(n -> assertTrue(ids.add(n.id())));
        f.edges().forEach(e -> { assertTrue(ids.contains(e.source())); assertTrue(ids.contains(e.target())); });
    }
}
