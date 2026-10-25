package dev.blueprints.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.blueprints.check.*;
import dev.blueprints.roundtrip.*;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static dev.blueprints.flow.FlowModels.*;
import static dev.blueprints.flow.VisualModels.*;

class VisualEditServiceTest {
    final RoundTripService converter=new RoundTripService();
    final VisualEditService service=new VisualEditService(converter);
    final ObjectMapper json=new ObjectMapper();
    final String demo="""
            class A {
                static int last;
                static int calculate(int amount, boolean vip) {
                    // Начальное количество
                    int count = 0;
                    count += amount;
                    if (vip && amount > 10) { count += 2; } else { count += 3; }
                    for (int i = 0; i < 2; i++) { count += 1; }
                    record(count);
                    return Math.max(count, 0);
                }
                static void record(int value) { last = value; }
            }
            """;
    record Selection(Context context,MethodFlow method,FlowNode node) {}
    Selection select(String source,String methodName,Predicate<FlowNode> choose,String position) {
        var conversion=converter.convert("A.java",source);
        var method=conversion.methods().stream().filter(m->m.signature().contains(methodName+"(")).findFirst().orElseThrow();
        var node=method.nodes().stream().filter(choose).findFirst().orElseThrow();
        return new Selection(new Context("A.java",source,conversion.revision(),method.id(),node.id(),position),method,node);
    }
    Predicate<FlowNode> text(String value) { return node->node.edit()!=null && node.edit().text().replaceAll("\\s+","").contains(value.replaceAll("\\s+","")); }
    Selection select(String source,String fragment) { return select(source,"calculate",text(fragment),null); }
    Description describe(String source,String fragment) { return service.describe(select(source,fragment).context()); }
    Spec spec(String text) throws Exception {return json.readValue(text,Spec.class);}
    Spec changed(Spec original,String field,Object value) {var tree=json.valueToTree(original);((com.fasterxml.jackson.databind.node.ObjectNode)tree).set(field,json.valueToTree(value));return json.convertValue(tree,Spec.class);}
    BlockEditModels.Result apply(Selection selection,Spec spec) {
        Context c=selection.context();
        var result=service.apply(new Apply(c.fileName(),c.source(),c.revision(),c.methodId(),c.nodeId(),c.position(),c.position()==null?"edit":"insert",spec));
        assertTrue(result.conversion().structureEquivalent());
        return result;
    }
    void passes(String source,String tests,int count) {
        var report=new CheckService().check(new CheckModels.Request("A.java",source,"ATest.java",tests),true);
        assertEquals("passed",report.status(),report.message()+"\n"+report.diagnostics()+"\n"+report.output());assertEquals(count,report.passed());
    }
    void invalid(Runnable operation) { assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,assertThrows(SourceException.class,operation::run).status()); }

    @Test void importedVariableActionConditionLoopAndCallDescribeWithoutRawJava() {
        for(var example:Map.of("int count", "variable","count += amount","action","vip &&", "condition","i < 2","loop","record(count)","call","return Math.max","return").entrySet()) {
            var description=describe(demo,example.getKey());
            assertTrue(description.supported(),description.reason());assertEquals(example.getValue(),description.spec().kind());
            assertFalse(json.valueToTree(description.spec()).toString().contains("\"raw\""));
        }
        var loop=describe(demo,"i < 2");assertEquals("for",loop.spec().loopKind());assertEquals("i",loop.spec().name());
        assertTrue(loop.variables().stream().anyMatch(variable->variable.name().equals("i")));
    }

    @Test void importAndVisualEditsCompileAndRunJUnit() throws Exception {
        String source=demo;
        var description=describe(source,"int count");
        source=apply(select(source,"int count"),changed(description.spec(),"value",Expr.literal("number","5"))).source();
        description=describe(source,"count += amount");
        source=apply(select(source,"count += amount"),changed(description.spec(),"value",Expr.binary("*",Expr.literal("variable","amount"),Expr.literal("number","2")))).source();
        description=describe(source,"vip &&");
        source=apply(select(source,"vip &&"),changed(description.spec(),"condition",Expr.binary("||",Expr.literal("variable","vip"),Expr.binary(">",Expr.literal("variable","amount"),Expr.literal("number","10"))))).source();
        description=describe(source,"i < 2");
        source=apply(select(source,"i < 2"),changed(description.spec(),"condition",Expr.binary("<",Expr.literal("variable","i"),Expr.literal("number","3")))).source();
        description=describe(source,"record(count)");
        source=apply(select(source,"record(count)"),changed(description.spec(),"arguments",List.of(Expr.binary("+",Expr.literal("variable","count"),Expr.literal("number","1"))))).source();
        assertTrue(source.contains("Начальное количество"));assertTrue(source.contains("else"));assertTrue(source.contains("count += 1"));
        passes(source,"""
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class ATest {
                    @Test void positive(){assertEquals(50,A.calculate(20,false));assertEquals(51,A.last);}
                    @Test void noVip(){assertEquals(11,A.calculate(0,false));}
                    @Test void vip(){assertEquals(10,A.calculate(0,true));}
                }
                """,3);
    }

    @Test void visualInsertIntoConditionAndLoopThenCallCompiles() throws Exception {
        String source="class A { static int last; static int calculate(int amount){int count=amount;return count;} static void record(int value){last=value;} }";
        source=apply(select(source,"calculate",text("return count"),"before"),spec("""
                {"kind":"variable","name":"bonus","type":"int","value":{"kind":"number","value":"2"}}
                """)).source();
        source=apply(select(source,"calculate",text("return count"),"before"),spec("""
                {"kind":"condition","condition":{"kind":"binary","operator":">","left":{"kind":"variable","value":"amount"},"right":{"kind":"number","value":"0"}}}
                """)).source();
        source=apply(select(source,"calculate",text("amount > 0"),"then-end"),spec("""
                {"kind":"action","target":"count","operator":"+=","value":{"kind":"variable","value":"bonus"}}
                """)).source();
        source=apply(select(source,"calculate",text("return count"),"before"),spec("""
                {"kind":"loop","loopKind":"for","name":"i","type":"int","start":{"kind":"number","value":"0"},
                 "condition":{"kind":"binary","operator":"<","left":{"kind":"variable","value":"i"},"right":{"kind":"number","value":"2"}},
                 "operator":"+=","step":{"kind":"number","value":"1"}}
                """)).source();
        source=apply(select(source,"calculate",text("i < 2"),"body-end"),spec("""
                {"kind":"action","target":"count","operator":"+=","value":{"kind":"number","value":"1"}}
                """)).source();
        var insertion=select(source,"calculate",text("return count"),"before");
        var options=service.describe(insertion.context());
        String call=options.methods().stream().filter(method->method.id().equals("record(int)")).findFirst().orElseThrow().id();
        source=apply(insertion,spec("{\"kind\":\"call\",\"callId\":\""+call+"\",\"arguments\":[{\"kind\":\"variable\",\"value\":\"count\"}]}")).source();
        passes(source,"""
                import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
                class ATest {@Test void works(){assertEquals(7,A.calculate(3));assertEquals(7,A.last);assertEquals(2,A.calculate(0));}}
                """,1);
    }

    @Test void scopesExcludeLaterAndSiblingVariablesAndIncludeLoopBodyCounter() {
        String source="class A {int calculate(int input){int before=1;if(input>0){int inside=2;before+=inside;}else{before++;}int later=3;return before;}}";
        var branch=select(source,"calculate",text("input > 0"),"else-start");
        var names=service.describe(branch.context()).variables().stream().map(Variable::name).toList();
        assertTrue(names.containsAll(List.of("input","before")));assertFalse(names.contains("inside"));assertFalse(names.contains("later"));
        var inside=describe(source,"before += inside");assertTrue(inside.variables().stream().anyMatch(v->v.name().equals("inside")));
    }

    @Test void foreachImportAndEditRetainBody() {
        String source="import java.util.List;class A{static int calculate(List<Integer> prices){int sum=0;for(int price:prices){if(price>0)sum+=price;}return sum;}}";
        var selected=select(source,"prices"); // iterable or declaration node selected below explicitly
        selected=select(source,"calculate",n->n.edit()!=null && n.edit().kind().equals("iterable"),null);
        var description=service.describe(selected.context());assertTrue(description.supported(),description.reason());assertEquals("foreach",description.spec().loopKind());
        var result=apply(selected,description.spec());assertTrue(result.source().contains("sum += price"));
        passes(result.source(),"import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;class ATest{@Test void sum(){assertEquals(7,A.calculate(java.util.List.of(-1,3,4)));}}",1);
    }

    @Test void whileAndDoWhileKeepBodies() {
        for(String loop:List.of("while(count<3){count++;}","do{count++;}while(count<3);")) {
            String source="class A{static int calculate(){int count=0;"+loop+"return count;}}";
            var description=describe(source,"count < 3");assertTrue(description.supported(),description.reason());
            var result=apply(select(source,"count < 3"),changed(description.spec(),"condition",Expr.binary("<",Expr.literal("variable","count"),Expr.literal("number","4"))));
            assertTrue(result.source().contains("count++"));
            passes(result.source(),"import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;class ATest{@Test void count(){assertEquals(4,A.calculate());}}",1);
        }
    }

    @Test void typedStringsAreEscapedAndCannotInjectStatements() throws Exception {
        String source="class A{static String calculate(){String text=\"old\";return text;}}";
        var original=describe(source,"String text").spec();
        String payload="\"; throw new RuntimeException(); //\nПривет";
        var result=apply(select(source,"String text"),changed(original,"value",Expr.literal("string",payload)));
        assertEquals(payload,describe(result.source(),"String text").spec().value().value());
        assertTrue(result.source().contains("\\\"; throw"));
        assertEquals(0,new com.github.javaparser.JavaParser().parse(result.source()).getResult().orElseThrow().findAll(com.github.javaparser.ast.stmt.ThrowStmt.class).size());
    }

    @Test void invalidIdentifierTypeAndUnknownNamesAreRejected() {
        var original=describe(demo,"int count").spec();var selection=select(demo,"int count");
        for(String name:List.of("x;bad()","class","_","var","a b")) invalid(()->apply(selection,changed(original,"name",name)));
        invalid(()->apply(selection,changed(original,"type","NoSuchType")));
        invalid(()->apply(selection,changed(original,"value",Expr.literal("variable","notInScope"))));
        invalid(()->apply(selection,changed(original,"value",Expr.literal("string","text"))));
    }

    @Test void referencedVariableAndLoopCounterCannotBeSilentlyRenamed() {
        var original=describe(demo,"int count").spec();
        invalid(()->apply(select(demo,"int count"),changed(original,"name","renamed")));
        var loop=describe(demo,"i < 2").spec();
        var renamed=changed(changed(loop,"name","index"),"condition",Expr.binary("<",Expr.literal("variable","index"),Expr.literal("number","2")));
        invalid(()->apply(select(demo,"i < 2"),renamed));
    }

    @Test void staleRevisionAndWrongModeAreRejected() {
        var selection=select(demo,"int count");var c=selection.context();var spec=service.describe(c).spec();
        var error=assertThrows(SourceException.class,()->service.apply(new Apply(c.fileName(),c.source(),"old",c.methodId(),c.nodeId(),null,"edit",spec)));
        assertEquals(HttpStatus.CONFLICT,error.status());
        invalid(()->service.apply(new Apply(c.fileName(),c.source(),c.revision(),c.methodId(),c.nodeId(),null,"insert",spec)));
    }

    @Test void unsupportedExpressionsRemainUnchanged() {
        String source="class A{int calculate(int[] values){int x=values[0];return x;}}";
        var description=describe(source,"int x");assertFalse(description.supported());assertNull(description.spec());
        assertTrue(description.reason().contains("Java"));
        assertTrue(source.contains("values[0]"));
    }

    @Test void byteNarrowingWorksAndFloatingBitwiseFails() {
        String source="class A{byte calculate(){byte value=0;return value;}}";
        var selection=select(source,"byte value");var spec=service.describe(selection.context()).spec();
        var result=apply(selection,changed(spec,"value",Expr.literal("number","127")));assertTrue(result.source().contains("byte value = 127"));
        invalid(()->apply(selection,changed(spec,"value",Expr.literal("number","128"))));
        invalid(()->apply(selection,changed(spec,"value",Expr.binary("&",Expr.literal("number","2.0"),Expr.literal("number","1")))));
        invalid(()->apply(selection,changed(spec,"value",Expr.unary("~",Expr.literal("number","2.0")))));
    }

    @Test void primitiveWrapperAndGenericAssignmentsStayDistinct() {
        assertTrue(VisualExpressions.assignable("int","Integer"));
        assertFalse(VisualExpressions.assignable("List<String>","List<Integer>"));
        assertFalse(VisualExpressions.assignable("Integer","long"));
        assertTrue(VisualExpressions.assignable("double","int"));
    }

    @Test void literalHexAndUnderscoreImportGetsUsableDecimalValue() {
        for(String number:List.of("0xff","1_000","0b1010")) {
            String source="class A{int calculate(){int value="+number+";return value;}}";
            var description=describe(source,"int value");assertTrue(description.supported());
            assertDoesNotThrow(()->apply(select(source,"int value"),description.spec()));
        }
    }

    @Test void finalVariableCannotBeAssignedAndStaticContextHidesInstanceMembers() throws Exception {
        String source="class A{int field;void instance(){} static int calculate(){final int value=1;return value;}}";
        var insertion=select(source,"calculate",text("return value"),"before");var options=service.describe(insertion.context());
        assertFalse(options.variables().stream().anyMatch(v->v.name().equals("field")));
        assertFalse(options.methods().stream().anyMatch(m->m.id().startsWith("instance(")));
        var action=spec("{\"kind\":\"action\",\"target\":\"value\",\"operator\":\"++\"}");
        invalid(()->apply(insertion,action));
    }

    @Test void selectedOverloadCannotSilentlyInvokeAnotherSignature() throws Exception {
        String source="class A{static int calculate(){return 0;}static int choose(int x){return 1;}static int choose(double x){return 2;}}";
        var insertion=select(source,"calculate",text("return 0"),"before");
        Spec wrong=spec("{\"kind\":\"call\",\"callId\":\"choose(double)\",\"arguments\":[{\"kind\":\"number\",\"value\":\"1\"}]}");
        invalid(()->apply(insertion,wrong));
        Spec correct=spec("{\"kind\":\"call\",\"callId\":\"choose(double)\",\"arguments\":[{\"kind\":\"number\",\"value\":\"1.0\"}]}");
        assertTrue(apply(insertion,correct).source().contains("choose(1.0)"));
    }
}
