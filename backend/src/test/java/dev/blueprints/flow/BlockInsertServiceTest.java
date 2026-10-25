package dev.blueprints.flow;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.*;
import dev.blueprints.roundtrip.*;
import dev.blueprints.check.*;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static dev.blueprints.flow.BlockInsertModels.*;

class BlockInsertServiceTest {
    final RoundTripService converter = new RoundTripService();
    final BlockInsertService service = new BlockInsertService(converter);
    Request request(String code, Predicate<FlowModels.FlowNode> choose, String position, String kind, String added) {
        var graph = converter.convert("A.java", code);
        var method = graph.methods().getFirst();
        var node = method.nodes().stream().filter(choose).findFirst().orElseThrow();
        return new Request("A.java", code, graph.revision(), method.id(), node.id(), position, kind, added);
    }
    Predicate<FlowModels.FlowNode> label(String label) { return n -> n.label().equals(label); }
    Predicate<FlowModels.FlowNode> role(String role) { return n -> n.insert() != null && n.insert().role().equals(role); }
    BlockEditModels.Result add(String code, Predicate<FlowModels.FlowNode> choose, String position, String kind, String added) {
        return service.insert(request(code, choose, position, kind, added));
    }
    void invalid(Request request) { assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(SourceException.class, () -> service.insert(request)).status()); }
    List<Statement> body(String source) { return StaticJavaParser.parse(source).findFirst(com.github.javaparser.ast.body.MethodDeclaration.class).orElseThrow().getBody().orElseThrow().getStatements(); }

    @Test void insertsBeforeAndAfterExactActionAndSelectsNewRange() {
        String code = "class A {void f(){first(); second();}}";
        var result = add(code, label("first();"), "after", "operation", "added();");
        assertEquals(List.of("first();", "added();", "second();"), body(result.source()).stream().map(Object::toString).toList());
        var method = result.conversion().methods().getFirst();
        var node = method.nodes().stream().filter(n -> n.id().equals(result.selectedNodeId())).findFirst().orElseThrow();
        assertEquals("added();", node.label());
        assertTrue(result.source().lines().toList().get(node.range().startLine()-1).contains("added();"));
        var second = add(result.source(), label("second();"), "before", "variable", "int x = 2;");
        assertEquals(4, body(second.source()).size());
    }
    @Test void insertsIntoEmptyMethodAndConstructorAfterSuper() {
        var empty = add("class A {void f(){}}", n -> n.kind().equals("end"), "body-end", "call", "work();");
        assertEquals("work();", body(empty.source()).getFirst().toString());
        var constructor = add("class A {A(){super();}}", n -> n.kind().equals("start"), "body-start", "call", "work();");
        var statements = StaticJavaParser.parse(constructor.source()).findFirst(com.github.javaparser.ast.body.ConstructorDeclaration.class).orElseThrow().getBody().getStatements();
        assertTrue(statements.get(0).isExplicitConstructorInvocationStmt());
        assertEquals("work();", statements.get(1).toString());
    }
    @Test void wrapsUnbracedBranchWithoutMovingElse() {
        var result = add("class A {void f(boolean b){if(b) one(); else two();}}", label("one();"), "after", "operation", "three();");
        var branch = (IfStmt) body(result.source()).getFirst();
        assertEquals(2, branch.getThenStmt().asBlockStmt().getStatements().size());
        assertEquals("two();", branch.getElseStmt().orElseThrow().toString());
    }
    @Test void createsMissingElseAndPreservesOriginalBranch() {
        var result = add("class A {void f(boolean b){if(b) one();}}", role("if"), "else-end", "call", "two();");
        var branch = (IfStmt) body(result.source()).getFirst();
        assertEquals("one();", branch.getThenStmt().toString());
        assertEquals("two();", branch.getElseStmt().orElseThrow().asBlockStmt().getStatement(0).toString());
    }
    @Test void addsToStartAndEndOfBranches() {
        var result = add("class A {void f(boolean b){if(b){one();}else{two();}}}", role("if"), "then-start", "call", "first();");
        result = add(result.source(), role("if"), "else-end", "call", "last();");
        var branch = (IfStmt) body(result.source()).getFirst();
        assertEquals("first();", branch.getThenStmt().asBlockStmt().getStatement(0).toString());
        assertEquals("last();", branch.getElseStmt().orElseThrow().asBlockStmt().getStatement(1).toString());
    }
    @Test void addsInsideAllFourLoopBodies() {
        for (String loop : List.of("while(b) work();", "do work(); while(b);", "for(int i=0;i<3;i++)work();", "for(int x:items)work();")) {
            var result = add("class A {void f(){"+loop+"}}", role("loop"), "body-end", "call", "added();");
            var body = InsertableCode.loopBody(body(result.source()).getFirst()).asBlockStmt();
            assertEquals(List.of("work();", "added();"), body.getStatements().stream().map(Object::toString).toList());
        }
    }
    @Test void createsConditionThenPopulatesIt() {
        var result = add("class A {int f(int x){return x;}}", label("return x;"), "before", "condition", "if(x < 0) {}");
        result = add(result.source(), role("if"), "then-end", "return", "return 0;");
        assertEquals("return 0;", ((IfStmt)body(result.source()).getFirst()).getThenStmt().asBlockStmt().getStatement(0).toString());
        assertEquals("return x;", body(result.source()).get(1).toString());
    }
    @Test void createsLoopThenPopulatesIt() {
        var result = add("class A {void f(){}}", n -> n.kind().equals("start"), "body-start", "loop", "for(int i=0;i<3;i++){}");
        result = add(result.source(), role("loop"), "body-start", "call", "System.out.println(i);");
        assertTrue(result.source().contains("System.out.println(i);"));
        assertEquals("flow",result.conversion().methods().getFirst().mode());
    }
    @Test void rejectsAfterReturnAndEarlyReturnBeforeExistingCode() {
        invalid(request("class A {int f(){return 1;}}", label("return 1;"), "after", "call", "work();"));
        invalid(request("class A {void f(){work();}}", n -> n.kind().equals("start"), "body-start", "return", "return;"));
        invalid(request("class A {void f(boolean b){if(b)return;else return;}}", role("if"), "after", "call", "work();"));
    }
    @Test void rejectsInfiniteLoopBeforeFollowingCodeButSupportsItsBody() {
        invalid(request("class A {void f(){work();}}", n -> n.kind().equals("start"), "body-start", "loop", "for(;;){}"));
        var result = add("class A {void f(){for(;;){}}}", role("loop"), "body-start", "break", "break;");
        assertTrue(result.source().contains("break;"));
        assertTrue(result.conversion().methods().getFirst().nodes().stream().anyMatch(n -> n.kind().equals("end")));
    }
    @Test void breakAndContinueRequireLoopAndTerminalPosition() {
        for (String kind : List.of("break", "continue")) {
            invalid(request("class A {void f(){}}", n -> n.kind().equals("start"), "body-start", kind, kind+";"));
            var result = add("class A {void f(){while(b){work();}}}", label("work();"), "after", kind, kind+";");
            assertTrue(result.source().contains(kind+";"));
            invalid(request("class A {void f(){while(b){work();}}}", role("loop"), "body-start", kind, kind+";"));
        }
    }
    @Test void rejectsMultipleStatementsWrongKindAndUnsupportedFlow() {
        for (String code : List.of("one(); two();", "if(", "{work();}", "return;")) {
            invalid(request("class A {void f(){}}", n -> n.kind().equals("start"), "body-start", "operation", code));
        }
        invalid(request("class A {void f(){}}", n -> n.kind().equals("start"), "body-start", "condition", "if(true){continue;}"));
    }
    @Test void refusesHeaderExpressionsAsInsertionAnchors() {
        var request = request("class A {void f(){for(int i=0;i<2;i++)work();}}", n -> n.edit()!=null && n.edit().kind().equals("for-init"), "after", "call", "work();");
        invalid(request);
    }
    @Test void retainsCommentsIncludingDuplicatesAndBomCrLf() {
        String code = "\uFEFF// файл\r\nclass A {\r\n/** Описание */ void f(boolean b){\r\n// заметка\r\nif(b) one(); // рядом\r\n// заметка\r\ntwo();\r\n}}";
        var result = add(code, label("one();"), "after", "call", "three();");
        assertTrue(result.source().contains("// файл"));
        assertTrue(result.source().contains("Описание"));
        assertTrue(result.source().contains("// рядом"));
        assertEquals(2, result.source().split("// заметка",-1).length-1);
        assertTrue(result.conversion().structureEquivalent());
    }
    @Test void checksRevisionIdsAndAllowedPositions() {
        var valid = request("class A {void f(){}}", n -> n.kind().equals("start"), "body-start", "call", "work();");
        var stale = new Request(valid.fileName(),valid.source(),"stale",valid.methodId(),valid.nodeId(),valid.position(),valid.kind(),valid.code());
        assertEquals(HttpStatus.CONFLICT, assertThrows(SourceException.class,()->service.insert(stale)).status());
        var forged = new Request(valid.fileName(),valid.source(),valid.revision(),valid.methodId(),"n999",valid.position(),valid.kind(),valid.code());
        assertEquals(HttpStatus.CONFLICT, assertThrows(SourceException.class,()->service.insert(forged)).status());
        invalid(new Request(valid.fileName(),valid.source(),valid.revision(),valid.methodId(),valid.nodeId(),"else-end",valid.kind(),valid.code()));
    }
    @Test void keepsOverloadedMethodSelection() {
        String code = "class A {void f(){} void f(int x){work();}}";
        var before = converter.convert("A.java",code);
        var m = before.methods().get(1);
        var n = m.nodes().stream().filter(node->node.kind().equals("start")).findFirst().orElseThrow();
        var result = service.insert(new Request("A.java",code,before.revision(),m.id(),n.id(),"body-start","call","added();"));
        assertEquals(result.conversion().methods().get(1).id(),result.selectedMethodId());
        assertTrue(StaticJavaParser.parse(result.source()).findAll(com.github.javaparser.ast.body.MethodDeclaration.class).getFirst().getBody().orElseThrow().isEmpty());
    }
    @Test void insertedCodeActuallyChangesCompilationAndJunitResults() {
        String code = "public class A {public static int f(){int x=1;return x;}}";
        var changed = add(code,label("int x = 1;"),"after","operation","x += 2;");
        String tests = "import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; class ATest {@Test void value(){assertEquals(3,A.f());}}";
        var checked = new CheckService().check(new CheckModels.Request("A.java",changed.source(),"ATest.java",tests),true);
        assertEquals("passed",checked.status(),checked.output());
        assertEquals(1,checked.passed());
    }
}
