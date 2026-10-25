package dev.blueprints.flow;

import dev.blueprints.roundtrip.RoundTripService;
import dev.blueprints.roundtrip.SourceException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static dev.blueprints.flow.BlockEditModels.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockEditServiceTest {
    final RoundTripService converter = new RoundTripService();
    final BlockEditService service = new BlockEditService(converter);
    private Request request(String source, String kind, String replacement) {
        var conversion = converter.convert("A.java", source);
        for (var method : conversion.methods()) for (var node : method.nodes())
            if (node.edit() != null && node.edit().kind().equals(kind))
                return new Request("A.java", source, conversion.revision(), method.id(), node.id(), replacement);
        throw new AssertionError("No editable " + kind);
    }
    @Test void changesConditionInAstAndRebuildsSourceRanges() {
        String code = "class A {\nvoid f(int x) {\nif (x > 0) work();\n}\n}";
        var result = service.edit(request(code, "condition", "x >= 10 && x < 100"));
        assertTrue(result.source().contains("if (x >= 10 && x < 100)"));
        assertTrue(result.conversion().structureEquivalent());
        var node = result.conversion().methods().getFirst().nodes().stream()
                .filter(n -> n.edit() != null && n.edit().kind().equals("condition")).findFirst().orElseThrow();
        assertTrue(result.source().lines().toList().get(node.range().startLine() - 1).contains("if (x >= 10"));
        assertEquals(result.conversion().methods().getFirst().id(), result.selectedMethodId());
    }
    @Test void changesOnlyChosenActionWhenLabelsRepeat() {
        String code = "class A {void f(){ work(); work(); }}";
        var conversion = converter.convert("A.java", code);
        var method = conversion.methods().getFirst();
        var nodes = method.nodes().stream().filter(n -> n.edit() != null).toList();
        var result = service.edit(new Request("A.java", code, conversion.revision(), method.id(), nodes.getFirst().id(), "changed();"));
        assertEquals(1, result.source().split("changed\\(\\)", -1).length - 1);
        assertEquals(1, result.source().split("work\\(\\)", -1).length - 1);
    }
    @Test void preservesLeadingInlineAndDuplicateComments() {
        String code = "// файл\nclass A {\n/** Метод */ void f(){\n// заметка\nint x = 1; // рядом\n// заметка\nwork();\n}}";
        var result = service.edit(request(code, "statement", "other();"));
        assertTrue(result.source().contains("// файл"));
        assertTrue(result.source().contains("Метод"));
        assertTrue(result.source().contains("// рядом"));
        assertEquals(2, result.source().split("// заметка", -1).length - 1);
    }
    @Test void keepsCommentsInsideReplacedExpression() {
        var result = service.edit(request("class A { void f(int x){if(x /* причина */ > 0) work();}}",
                "condition", "x > 10"));
        assertTrue(result.source().contains("причина"));
        assertTrue(result.source().contains("x > 10"));
    }
    @Test void changesForInitializerAndUpdateAndIterable() {
        var init = service.edit(request("class A {void f(){for(int i=0;i<10;i++)work();}}", "for-init", "int i = 2"));
        assertTrue(init.source().contains("int i = 2"));
        var update = service.edit(request(init.source(), "for-update", "i += 2"));
        assertTrue(update.source().contains("i += 2"));
        var each = service.edit(request("class A {void f(){for(int x:items)work();}}", "iterable", "otherItems"));
        assertTrue(each.source().contains(": otherItems"));
    }
    @Test void changesReturnAndThrowWithoutChangingBlockKind() {
        assertTrue(service.edit(request("class A {int f(){return 1;}}", "return", "return 2;")).source().contains("return 2;"));
        assertTrue(service.edit(request("class A {void f(){throw new RuntimeException();}}", "throw",
                "throw new IllegalArgumentException();")).source().contains("IllegalArgumentException"));
        assertThrows(SourceException.class, () -> service.edit(request("class A {int f(){return 1;}}", "return", "work();")));
    }
    @Test void rejectsMalformedCodeAndMultipleStatements() {
        String source = "class A {void f(){work();}}";
        for (String bad : List.of("x = ;", "work(); other();", "if (true) work();", "{work();}", ""))
            assertThrows(SourceException.class, () -> service.edit(request(source, "statement", bad)), bad);
        assertThrows(SourceException.class, () -> service.edit(request("class A {void f(){if(true)work();}}",
                "condition", "true); other(); if(false")));
        assertTrue(converter.convert("A.java", source).generatedSource().contains("work();"));
    }
    @Test void rejectsExtraForHeaderParts() {
        String code = "class A {void f(){for(int i=0;i<10;i++)work();}}";
        for (String bad : List.of("i++; work()", "i++, j++", "i++){}; work(); for(;;"))
            assertThrows(SourceException.class, () -> service.edit(request(code, "for-update", bad)), bad);
    }
    @Test void rejectsStaleRevisionAndForgedIds() {
        String code = "class A {void f(){work();}}";
        Request original = request(code, "statement", "other();");
        assertEquals(HttpStatus.CONFLICT, assertThrows(SourceException.class, () -> service.edit(
                new Request("A.java", code + " ", original.revision(), original.methodId(), original.nodeId(), "other();"))).status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(SourceException.class, () -> service.edit(
                new Request("A.java", code, original.revision(), "missing", original.nodeId(), "other();"))).status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(SourceException.class, () -> service.edit(
                new Request("A.java", code, original.revision(), original.methodId(), "missing", "other();"))).status());
    }
    @Test void refusesReadOnlyBlocksAndKeepsFullUntruncatedText() {
        String code = "class A {void f(){print(\"" + "д".repeat(160) + "\");}}";
        var conversion = converter.convert("A.java", code);
        var m = conversion.methods().getFirst();
        var action = m.nodes().stream().filter(n -> n.edit()!=null).findFirst().orElseThrow();
        assertTrue(action.label().endsWith("…"));
        assertTrue(action.edit().text().contains("д".repeat(160)));
        var start = m.nodes().stream().filter(n -> n.kind().equals("start")).findFirst().orElseThrow();
        assertThrows(SourceException.class, () -> service.edit(new Request("A.java", code, conversion.revision(), m.id(), start.id(), "work();")));
    }
    @Test void handlesBomCrlfAndSuccessiveEditsInOverloadedMethod() {
        String code = "\uFEFFclass A {\r\nvoid f(){}\r\nvoid f(int x){if(x>0) work();}\r\n}";
        var first = service.edit(request(code, "condition", "x > 5"));
        var second = service.edit(request(first.source(), "statement", "updated();"));
        assertEquals(2, second.conversion().methods().size());
        assertEquals(second.conversion().methods().get(1).id(), second.selectedMethodId());
        assertTrue(second.source().contains("x > 5"));
        assertTrue(second.source().contains("updated();"));
        assertNotEquals(first.conversion().revision(), second.conversion().revision());
    }
    @Test void validatesRequiredFieldsAndSize() {
        assertThrows(SourceException.class, () -> service.edit(new Request(null,null,null,null,null,null)));
        var r = request("class A{void f(){work();}}", "statement", "x".repeat(20001));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, assertThrows(SourceException.class, () -> service.edit(r)).status());
    }

    @Test void editedValidFixtureStillCompiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        String code = "class A {static int total(int[] prices){int sum=0; for(int price:prices){if(price>0)sum+=price;}return sum;}}";
        var condition = service.edit(request(code, "condition", "price >= 100"));
        var action = service.edit(request(condition.source(), "statement", "sum += price * 2;"));
        var file = directory.resolve("A.java");
        java.nio.file.Files.writeString(file, action.source());
        int status = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-encoding", "UTF-8", "-d", directory.toString(), file.toString());
        assertEquals(0, status);
        assertTrue(action.source().contains("price >= 100"));
        assertTrue(action.source().contains("sum += price * 2"));
    }
}
