package dev.blueprints.flow;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.stmt.*;
import dev.blueprints.check.*;
import dev.blueprints.roundtrip.*;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static dev.blueprints.flow.FlowModels.*;

class BlockMutationServiceTest {
    final RoundTripService converter = new RoundTripService();
    final BlockDeleteService deletes = new BlockDeleteService(converter);
    final BlockMoveService moves = new BlockMoveService(converter);
    final BlockInsertService inserts = new BlockInsertService(converter);
    final BlockEditService edits = new BlockEditService(converter);
    record Selection(RoundTripModels.Response conversion, MethodFlow method, FlowNode node) {}
    Selection select(String source, Predicate<FlowNode> choose) {
        var conversion = converter.convert("A.java", source);
        var method = conversion.methods().getFirst();
        return new Selection(conversion, method, method.nodes().stream().filter(choose).findFirst().orElseThrow());
    }
    Predicate<FlowNode> label(String value) { return n -> n.label().equals(value) || n.mutation()!=null && n.mutation().text().strip().equals(value); }
    Predicate<FlowNode> role(String value) { return n -> n.insert()!=null && n.insert().role().equals(value); }
    BlockEditModels.Result delete(String source, Predicate<FlowNode> choose, String mode) {
        var s=select(source,choose);
        return checked(deletes.delete(new BlockDeleteModels.Request("A.java",source,s.conversion.revision(),s.method.id(),s.node.id(),mode)));
    }
    BlockEditModels.Result move(String source, Predicate<FlowNode> choose, Predicate<FlowNode> target, String position) {
        var s=select(source,choose); var t=s.method.nodes().stream().filter(target).findFirst().orElseThrow();
        return checked(moves.move(new BlockMoveModels.Request("A.java",source,s.conversion.revision(),s.method.id(),s.node.id(),t.id(),position)));
    }
    BlockEditModels.Result insert(String source, Predicate<FlowNode> target, String position, String kind, String code) {
        var s=select(source,target);
        return checked(inserts.insert(new BlockInsertModels.Request("A.java",source,s.conversion.revision(),s.method.id(),s.node.id(),position,kind,code)));
    }
    BlockEditModels.Result edit(String source, Predicate<FlowNode> target, String replacement) {
        var s=select(source,target);
        return checked(edits.edit(new BlockEditModels.Request("A.java",source,s.conversion.revision(),s.method.id(),s.node.id(),replacement)));
    }
    BlockEditModels.Result checked(BlockEditModels.Result result) {
        assertTrue(result.conversion().structureEquivalent()); assertNotNull(selected(result));
        for(var method:result.conversion().methods()) for(var node:method.nodes()) {
            if(node.edit()!=null) assertEquals(node.edit().text(),fragment(result.source(),node.edit().range()));
            if(node.mutation()!=null) assertEquals(node.mutation().text(),fragment(result.source(),node.mutation().range()));
        }
        return result;
    }
    String fragment(String source, SourceRange range) {
        var lines=source.lines().toList();
        if(range.startLine()==range.endLine()) return lines.get(range.startLine()-1).substring(range.startColumn()-1,range.endColumn());
        StringBuilder text=new StringBuilder(lines.get(range.startLine()-1).substring(range.startColumn()-1)).append('\n');
        for(int line=range.startLine();line<range.endLine()-1;line++) text.append(lines.get(line)).append('\n');
        return text.append(lines.get(range.endLine()-1),0,range.endColumn()).toString();
    }
    CompilationUnit parse(String source) {
        return new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(source).getResult().orElseThrow();
    }
    BlockStmt body(String source) { return parse(source).findFirst(MethodDeclaration.class).orElseThrow().getBody().orElseThrow(); }
    FlowNode selected(BlockEditModels.Result result) {
        var method=result.conversion().methods().stream().filter(m->m.id().equals(result.selectedMethodId())).findFirst().orElseThrow();
        return method.nodes().stream().filter(n->n.id().equals(result.selectedNodeId())).findFirst().orElseThrow();
    }
    void invalid(Runnable action) { assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,assertThrows(SourceException.class,action::run).status()); }
    void compilesAndPasses(String source, String tests, int expected) {
        var result=new CheckService().check(new CheckModels.Request("A.java",source,"ATest.java",tests),true);
        assertEquals("passed",result.status(),result.output()); assertEquals(expected,result.passed());
    }

    @Test void lastActionCanBeDeletedAndEmptyMethodRemainsEditable() {
        var result=delete("class A {void f(){work();}}",label("work();"),"delete");
        assertTrue(body(result.source()).isEmpty()); assertNotNull(selected(result));
        var added=insert(result.source(),n->n.kind().equals("start"),"body-start","call","again();");
        assertEquals("again();",body(added.source()).getStatement(0).toString());
    }
    @Test void deletesWholeIfAndEmptyBranchesAndLoops() {
        for (String statement:List.of("if(b)one();else two();","if(b){}else{}","while(b){}","for(;;){}","do{}while(b);")) {
            var result=delete("class A{void f(boolean b){"+statement+"}}",n->n.mutation()!=null,"delete");
            assertTrue(body(result.source()).isEmpty(),statement);
        }
    }
    @Test void deletingSingleUnbracedBodyKeepsElseAndLoopGrammar() {
        var result=delete("class A{void f(boolean b){if(b)one();else two();}}",label("one();"),"delete");
        var branch=body(result.source()).getStatement(0).asIfStmt();
        assertTrue(branch.getThenStmt().asBlockStmt().isEmpty()); assertEquals("two();",branch.getElseStmt().orElseThrow().toString());
        for(String loop:List.of("while(b)one();","do one();while(b);","for(int i=0;i<3;i++)one();","for(int i:items)one();")) {
            result=delete("class A{void f(boolean b){"+loop+"}}",label("one();"),"delete");
            assertTrue(InsertableCode.loopBody(body(result.source()).getStatement(0)).asBlockStmt().isEmpty());
        }
    }
    @Test void keepThenAndKeepElsePreserveLocalScopes() {
        String code="class A{void f(boolean b){if(b){int value=1;use(value);}else{int value=2;use(value);}int value=3;use(value);}}";
        for(String mode:List.of("keep-then","keep-else")) {
            var result=delete(code,role("if"),mode);
            var kept=body(result.source()).getStatement(0).asBlockStmt();
            assertEquals(2,kept.getStatements().size()); assertEquals(3,body(result.source()).getStatements().size());
            assertTrue(kept.getStatement(0).toString().contains(mode.equals("keep-then")?"1":"2"));
        }
    }
    @Test void keepBodyRetainsScopeAndRejectsJumpsWhichWouldRetargetOuterLoop() {
        var result=delete("class A{void f(boolean b){while(b){int local=1;work(local);}after();}}",role("loop"),"keep-body");
        assertTrue(body(result.source()).getStatement(0).isBlockStmt());
        for(String jump:List.of("break;","continue;")) {
            String code="class A{void f(boolean a,boolean b){while(a){while(b){"+jump+"}}}}";
            var inner=select(code,n->n.label().equals("b")).node;
            assertFalse(inner.mutation().deleteModes().contains("keep-body"));
            invalid(()->delete(code,label("b"),"keep-body"));
        }
    }
    @Test void keepBodyDoesNotDropReferencedForHeaderVariable() {
        for(String loop:List.of("for(int i=0;i<3;i++){sum+=i;}","for(int i:values){sum+=i;}")) {
            String code="class A{int f(int[] values){int sum=0;"+loop+"return sum;}}";
            assertFalse(select(code,role("loop")).node.mutation().deleteModes().contains("keep-body"));
            invalid(()->delete(code,role("loop"),"keep-body"));
        }
    }
    @Test void preservingBodyDoesNotDiscardPatternBindingScope() {
        String code="class A{void f(Object value){if(value instanceof String text){use(text);}else other();}}";
        assertEquals(List.of("delete"),select(code,role("if")).node.mutation().deleteModes());
        invalid(()->delete(code,role("if"),"keep-then"));
        String loop="class A{void f(Object value){while(value instanceof String text){use(text);}}}";
        assertFalse(select(loop,role("loop")).node.mutation().deleteModes().contains("keep-body"));
    }
    @Test void keepDoBodyDoesNotDiscardPatternBindingUsedAfterLoop() {
        String code="public class A{public static String text(Object value){do{}while(!(value instanceof String result));return result;}}";
        var compiled=new CheckService().check(new CheckModels.Request("A.java",code,null,null),false);
        assertEquals("compiled",compiled.status(),compiled.output());
        assertFalse(select(code,role("loop")).node.mutation().deleteModes().contains("keep-body"));
        invalid(()->delete(code,role("loop"),"keep-body"));
    }
    @Test void nestedSwitchBreakDoesNotPreventKeepingLoopBody() {
        String code="class A{void f(boolean b,int n){while(b){switch(n){case 1:work();break;default:other();}}}}";
        var result=delete(code,label("b"),"keep-body");
        assertTrue(body(result.source()).getStatement(0).isBlockStmt());
        assertEquals(1,parse(result.source()).findAll(BreakStmt.class).size());
    }
    @Test void labeledLoopContinuePreventsKeepBodyAndMovesOutOfLabel() {
        String code="class A{void f(boolean b){outer:while(b){continue outer;}after();}}";
        assertFalse(select(code,label("b")).node.mutation().deleteModes().contains("keep-body"));
        invalid(()->delete(code,label("b"),"keep-body"));
        invalid(()->move(code,label("b"),label("after();"),"after"));
    }
    @Test void deletingAndMovingCommentsPreservesDuplicateCounts() {
        String code="// файл\nclass A{void f(){\n// одинаковый\none();\n// одинаковый\ntwo();\n// третий\nthree();}}";
        var result=delete(code,label("one();"),"delete");
        assertEquals(2,result.source().split("// одинаковый",-1).length-1);
        assertTrue(result.source().contains("// файл")); assertTrue(result.source().contains("// третий"));
        result=move(code,label("one();"),label("three();"),"after");
        assertEquals(2,result.source().split("// одинаковый",-1).length-1);
        var moved=body(result.source()).getStatement(2);
        assertTrue(moved.getComment().orElseThrow().getContent().contains("одинаковый"));
        assertEquals("one();",selected(result).mutation().text().strip());
    }
    @Test void movesExactRepeatedActionAndSelectsItsFreshRange() {
        String code="class A{void f(){first();work();work();last();}}";
        var result=move(code,n->n.label().equals("work();")&&n.range().startColumn()<37,label("last();"),"after");
        var statements=body(result.source()).getStatements();
        assertEquals(List.of("first();","work();","last();","work();"),statements.stream().map(Object::toString).toList());
        var selected=selected(result); assertEquals("work();",selected.label());
        assertEquals(statements.get(3).getBegin().orElseThrow().line,selected.range().startLine());
    }
    @Test void movesBetweenBranchesAndFromUnbracedLoop() {
        var result=move("class A{void f(boolean b){if(b)one();else two();}}",label("one();"),role("if"),"else-end");
        var branch=body(result.source()).getStatement(0).asIfStmt();
        assertTrue(branch.getThenStmt().asBlockStmt().isEmpty());
        assertEquals(List.of("two();","one();"),branch.getElseStmt().orElseThrow().asBlockStmt().getStatements().stream().map(Object::toString).toList());
        result=move("class A{void f(boolean b){while(b)one();after();}}",label("one();"),label("after();"),"before");
        assertTrue(body(result.source()).getStatement(0).asWhileStmt().getBody().asBlockStmt().isEmpty());
        assertEquals("one();",body(result.source()).getStatement(1).toString());
    }
    @Test void movesWholeSubtreeWithoutFlatteningItsScope() {
        String code="class A{void f(boolean b){if(b){int x=1;use(x);}after();}}";
        var result=move(code,role("if"),label("after();"),"after");
        assertEquals("after();",body(result.source()).getStatement(0).toString());
        assertEquals(2,body(result.source()).getStatement(1).asIfStmt().getThenStmt().asBlockStmt().getStatements().size());
    }
    @Test void rejectsSelfOwnDescendantAndSyntheticSources() {
        String code="class A{void f(boolean b){while(b){one();}after();}}";
        invalid(()->move(code,label("b"),label("b"),"body-start"));
        invalid(()->move(code,label("b"),label("one();"),"before"));
        invalid(()->delete(code,n->n.kind().equals("start"),"delete"));
        invalid(()->move(code,n->n.kind().equals("end"),label("after();"),"before"));
        String forCode="class A{void f(){for(int i=0;i<3;i++)one();}}";
        assertTrue(select(forCode,n->n.edit()!=null&&n.edit().kind().equals("for-init")).node.mutation()==null);
        assertTrue(select(forCode,n->n.edit()!=null&&n.edit().kind().equals("for-update")).node.mutation()==null);
        String constructor="class A{A(){super();one();}}";
        assertNull(select(constructor,label("super();")).node.mutation());
        invalid(()->delete(constructor,label("super();"),"delete"));
    }
    @Test void rejectsMovesThatLeaveUnboundBreakOrUnreachableCode() {
        String code="class A{void f(boolean b){while(b){break;}after();}}";
        invalid(()->move(code,label("break"),label("after();"),"after"));
        invalid(()->move("class A{int f(){one();return 1;}}",label("return 1;"),label("one();"),"before"));
        invalid(()->delete("class A{void f(){while(true){break;}after();}}",label("break"),"delete"));
    }
    @Test void checksRevisionsAndIdsBeforeMutation() {
        String code="class A{void f(){one();two();}}"; var s=select(code,label("one();"));
        for(String bad:List.of("stale",s.conversion.revision())) {
            var request=new BlockDeleteModels.Request("A.java",code,bad,s.method.id(),bad.equals("stale")?s.node.id():"missing","delete");
            assertEquals(HttpStatus.CONFLICT,assertThrows(SourceException.class,()->deletes.delete(request)).status());
        }
        var forged=new BlockMoveModels.Request("A.java",code,s.conversion.revision(),s.method.id(),s.node.id(),"missing","before");
        assertEquals(HttpStatus.CONFLICT,assertThrows(SourceException.class,()->moves.move(forged)).status());
        invalid(()->delete(code,label("one();"),"keep-else"));
        assertTrue(s.conversion.generatedSource().contains("one();"));
        assertEquals(2,body(code).getStatements().size());
    }
    @Test void editsSelectionByAstIdentityWhenGraphNodeCountChanges() {
        String code="class A{void f(int x){int value=x;after();}}";
        var result=edit(code,label("int value = x;"),"int value = switch(x){case 0 -> 10; default -> 20;};");
        assertTrue(selected(result).edit().text().startsWith("int value = switch"));
        assertTrue(result.source().lines().toList().get(selected(result).range().startLine()-1).contains("int value"));
    }
    @Test void editAlsoRejectsNewUnreachableCodeAndConstantFalseLoop() {
        String code="class A{void f(boolean b){while(b){work();}after();}}";
        invalid(()->edit(code,label("b"),"true")); invalid(()->edit(code,label("b"),"false"));
        assertTrue(converter.convert("A.java",code).methods().getFirst().mode().equals("flow"));
    }
    @Test void insertionWorksInTryCatchAndEveryFinallyCopy() {
        String code="class A{void f(){try{work();}catch(RuntimeException e){}finally{cleanup();}}}";
        var result=insert(code,n->n.kind().equals("try"),"body-start","call","beforeTry();");
        result=insert(result.source(),n->n.kind().equals("catch"),"body-start","call","caught();");
        result=insert(result.source(),n->n.kind().equals("finally"),"body-start","call","beforeCleanup();");
        var t=parse(result.source()).findFirst(TryStmt.class).orElseThrow();
        assertEquals("beforeTry();",t.getTryBlock().getStatement(0).toString());
        assertEquals("caught();",t.getCatchClauses().get(0).getBody().getStatement(0).toString());
        assertEquals("beforeCleanup();",t.getFinallyBlock().orElseThrow().getStatement(0).toString());
        assertEquals(1,result.source().split(java.util.regex.Pattern.quote("beforeCleanup();"),-1).length-1);
    }
    @Test void deletionOfFinallyCopyChangesItsSingleAstOperator() {
        String code="class A{int f(boolean b){try{if(b)return 1;return 2;}finally{cleanup();}}}";
        assertTrue(select(code,label("cleanup();")).method.nodes().stream().filter(label("cleanup();")).count()>1);
        var result=delete(code,label("cleanup();"),"delete");
        assertFalse(result.source().contains("cleanup()"));
        assertTrue(parse(result.source()).findFirst(TryStmt.class).orElseThrow().getFinallyBlock().orElseThrow().isEmpty());
    }
    @Test void insertsIntoEmptyColonCaseAndRejectsDeadCodeInCaseSequence() {
        String code="class A{void f(int n){switch(n){case 1:case 2:break;default:work();}}}";
        var result=insert(code,label("case 1"),"body-start","call","added();");
        assertEquals("added();",parse(result.source()).findFirst(SwitchStmt.class).orElseThrow().getEntry(0).getStatement(0).toString());
        invalid(()->insert(code,label("default"),"body-start","return","return;"));
    }
    @Test void arrowStatementInsertionSwitchesEntryTypeToBlock() {
        String code="class A{void f(int n){switch(n){case 0->one();default->two();}}}";
        var result=insert(code,label("one();"),"after","call","three();");
        var entry=parse(result.source()).findFirst(SwitchStmt.class).orElseThrow().getEntry(0);
        assertEquals(SwitchEntry.Type.BLOCK,entry.getType());
        assertEquals(List.of("one();","three();"),entry.getStatement(0).asBlockStmt().getStatements().stream().map(Object::toString).toList());
        result=delete(code,label("one();"),"delete");
        assertTrue(parse(result.source()).findFirst(SwitchStmt.class).orElseThrow().getEntry(0).getStatement(0).asBlockStmt().isEmpty());
    }
    @Test void arrowValueCaseInsertionPreservesItsImplicitYield() {
        String code="class A{int f(int n){return switch(n){case 0->10;default->20;};}}";
        var result=insert(code,label("case 0"),"body-start","call","work();");
        var entry=parse(result.source()).findFirst(com.github.javaparser.ast.expr.SwitchExpr.class).orElseThrow().getEntry(0);
        assertEquals(SwitchEntry.Type.BLOCK,entry.getType());
        assertTrue(entry.getStatement(0).asBlockStmt().getStatement(1).isYieldStmt());
        invalid(()->insert(code,label("case 0"),"body-end","call","work();"));
    }
    @Test void movesBetweenExistingCatchFinallyAndSwitchBodies() {
        String code="class A{void f(){before();try{work();}catch(RuntimeException e){caught();}finally{cleanup();}}}";
        var result=move(code,label("before();"),n->n.kind().equals("catch"),"body-start");
        var t=parse(result.source()).findFirst(TryStmt.class).orElseThrow();
        assertEquals("before();",t.getCatchClauses().get(0).getBody().getStatement(0).toString());
        result=move(result.source(),label("before();"),n->n.kind().equals("finally"),"body-start");
        t=parse(result.source()).findFirst(TryStmt.class).orElseThrow();
        assertEquals("before();",t.getFinallyBlock().orElseThrow().getStatement(0).toString());
        code="class A{void f(int x){switch(x){case 1:one();break;default:two();}}}";
        result=move(code,label("one();"),label("default"),"body-end");
        var sw=parse(result.source()).findFirst(SwitchStmt.class).orElseThrow();
        assertEquals("break;",sw.getEntry(0).getStatement(0).toString());
        assertEquals("one();",sw.getEntry(1).getStatement(1).toString());
    }
    @Test void retainingIfScopeProducesCompilableSiblingDeclarations() {
        String source="public class A{public static int f(boolean b){if(b){int x=1;System.out.print(x);}else{int x=2;System.out.print(x);}int x=3;return x;}}";
        var result=delete(source,role("if"),"keep-then");
        compilesAndPasses(result.source(),"import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;class ATest{@Test void scoped(){assertEquals(3,A.f(false));}}",1);
    }
    @Test void failedMoveDoesNotAlterRequestOrOriginalGraphAndCanBeRetried() {
        String code="class A{void f(boolean b){while(b){one();}after();}}";
        var before=converter.convert("A.java",code);
        invalid(()->move(code,label("b"),label("one();"),"before"));
        var again=converter.convert("A.java",code);
        assertEquals(before,again);
        var result=move(code,label("one();"),label("after();"),"after");
        assertEquals("one();",body(result.source()).getStatement(2).toString());
    }
    @Test void longSeriesOfInsertEditMoveDeleteBuildsARealTestedProgram() {
        String source="// учебный файл\npublic class A{public static int total(int[] values){\n// заметка\nint sum=0;\n// заметка\nreturn sum;}}";
        source=insert(source,label("return sum;"),"before","loop","for(int value:values){}").source();
        source=insert(source,role("loop"),"body-start","condition","if(value<0){}").source();
        source=insert(source,role("if"),"then-start","continue","continue;").source();
        source=insert(source,role("if"),"after","operation","sum += value;").source();
        source=insert(source,label("sum += value;"),"before","operation","sum += 1000;").source();
        source=move(source,label("sum += 1000;"),role("if"),"then-start").source();
        source=delete(source,label("sum += 1000;"),"delete").source();
        source=edit(source,label("value < 0"),"value <= 0").source();
        source=edit(source,label("sum += value;"),"sum += value * 2;").source();
        source=insert(source,label("sum += value * 2;"),"after","operation","// бонус\nsum += 1;").source();
        source=move(source,label("sum += 1;"),role("if"),"before").source();
        source=move(source,label("sum += 1;"),label("sum += value * 2;"),"after").source();
        source=insert(source,role("loop"),"body-start","condition","if(value==123){}").source();
        source=delete(source,label("value == 123"),"delete").source();
        String tests="import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;class ATest{"
                +"@Test void empty(){assertEquals(0,A.total(new int[]{}));}"
                +"@Test void mixed(){assertEquals(8,A.total(new int[]{1,-7,0,2}));}"
                +"@Test void large(){int[] values=new int[1000];java.util.Arrays.fill(values,3);assertEquals(7000,A.total(values));}"
                +"@Test void negative(){assertEquals(0,A.total(new int[]{-2,0,-4}));}}";
        assertEquals(2,source.split("// заметка",-1).length-1);
        assertEquals(1,source.split("// бонус",-1).length-1);
        assertTrue(source.contains("// учебный файл"));
        compilesAndPasses(source,tests,4);
        assertTrue(converter.convert("A.java",source).structureEquivalent());
    }
}
