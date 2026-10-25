package dev.blueprints.optimization;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.stmt.IfStmt;
import dev.blueprints.roundtrip.RoundTripService;
import dev.blueprints.roundtrip.SourceException;
import java.io.ByteArrayOutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import static dev.blueprints.optimization.OptimizationModels.*;
import static org.assertj.core.api.Assertions.*;

class OptimizationServiceTest {
    private final RoundTripService converter = new RoundTripService();
    private final OptimizationService service = new OptimizationService(converter);
    @TempDir Path temporary;

    private String source(String body) { return "public class Example {" + body + "}"; }
    private String revision(String source) { return converter.convert("Example.java", source).revision(); }
    private Options options(String source) { return service.options(new OptionsRequest("Example.java", source, revision(source))); }
    private Candidate candidate(String source, String rule) {
        return options(source).candidates().stream().filter(c -> c.rule().equals(rule)).findFirst().orElseThrow();
    }
    private Preview preview(String source, String rule) {
        var candidate = candidate(source, rule);
        assertThat(candidate.available()).as(candidate.reason()).isTrue();
        return service.preview(new PreviewRequest("Example.java", source, revision(source), candidate.id()));
    }
    private static void status(Runnable action, HttpStatus expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SourceException.class, error -> assertThat(error.status()).isEqualTo(expected));
    }
    private Object invoke(String text, Class<?>[] types, Object... arguments) throws Exception {
        Path directory = Files.createTempDirectory(temporary, "compile-");
        Path file = directory.resolve("Example.java");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        var errors = new ByteArrayOutputStream();
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, errors, "-encoding", "UTF-8",
                "--release", "21", "-d", directory.toString(), file.toString());
        assertThat(result).as(errors.toString(StandardCharsets.UTF_8)).isZero();
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            return loader.loadClass("Example").getMethod("run", types).invoke(null, arguments);
        }
    }
    private Map<String, Long> comments(String text) {
        var unit = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(text).getResult().orElseThrow();
        List<Comment> all = new ArrayList<>(unit.getAllContainedComments()); unit.getComment().ifPresent(all::add);
        return all.stream().collect(java.util.stream.Collectors.groupingBy(c -> c.getClass().getSimpleName()+":"+(c instanceof com.github.javaparser.ast.comments.JavadocComment j ? j.parse().toText() : c.getContent()).strip(),
                java.util.stream.Collectors.counting()));
    }

    @Test void removesIdenticalBranchesAndRebuildsFreshGraphWithoutChangingOriginal() throws Exception {
        String original = source("public static int run(int value){if(value>0){return value*2;}else{return value*2;}}");
        Options before = options(original);
        var preview = preview(original, "IDENTICAL_BRANCHES");
        assertThat(preview.source()).doesNotContain("if (");
        assertThat(preview.beforeFragment()).contains("if");
        assertThat(preview.afterFragment()).contains("return value * 2");
        assertThat(preview.revision()).isEqualTo(before.revision());
        assertThat(preview.conversion().revision()).isNotEqualTo(preview.revision());
        assertThat(preview.conversion()).isEqualTo(converter.convert("Example.java", preview.source()));
        assertThat(options(original)).isEqualTo(before);
        assertThat(preview(original, "IDENTICAL_BRANCHES")).isEqualTo(preview);
        var method = preview.conversion().methods().stream().filter(m -> m.id().equals(preview.selectedMethodId())).findFirst().orElseThrow();
        assertThat(method.nodes()).anyMatch(n -> n.id().equals(preview.selectedNodeId()) && n.range().startLine() > 1);
        for (int value : new int[]{-2, 0, 3})
            assertThat(invoke(preview.source(), new Class<?>[]{int.class}, value))
                    .isEqualTo(invoke(original, new Class<?>[]{int.class}, value));
    }

    @ParameterizedTest @ValueSource(strings = {
            "value > 0", "(value + 2) * 3 >= 1", "value == -value", "(value << 2) > 0",
            "(value & 1) == 0", "~value > 0", "(long)value * 1000L > 1L",
            "value > 0 ? true : false", "!(value > 0) || value < 100", "true"
    })
    void acceptsOnlySupportedPrimitiveExpressions(String condition) {
        String original = source("int run(int value){if("+condition+")return value;else return value;}");
        assertThat(candidate(original, "IDENTICAL_BRANCHES").available()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {
            "int run(Integer value){if(value>0)return 1;else return 1;}",
            "int run(Boolean value){if(value)return 1;else return 1;}",
            "int run(Object value){if(value==null)return 1;else return 1;}",
            "int run(int[] value){if(value[0]>0)return 1;else return 1;}",
            "int value; int run(){if(value>0)return 1;else return 1;}",
            "int value; int run(){if(this.value>0)return 1;else return 1;}",
            "int run(int value){if(value++>0)return 1;else return 1;}",
            "int run(int value){if((value=2)>0)return 1;else return 1;}",
            "int run(int value){if(1/value>0)return 1;else return 1;}",
            "int run(int value){if(value%2==0)return 1;else return 1;}",
            "int run(int value){if(check(value))return 1;else return 1;}boolean check(int n){return true;}",
            "int run(int value){int value=2;if(value>0)return 1;else return 1;}",
            "int run(int value){class Inner{int value;}if(value>0)return 1;else return 1;}",
            "int run(int value){java.util.function.IntSupplier task=()->{if(value>0)return 1;else return 1;};return 0;}",
            "int run(int... value){if(value>0)return 1;else return 1;}"
    })
    void rejectsUnsafeBoxedUnknownAndShadowedConditions(String method) {
        String original = source(method);
        Candidate candidate = candidate(original, "IDENTICAL_BRANCHES");
        assertThat(candidate.available()).isFalse();
        assertThat(candidate.reason()).isNotBlank();
        status(() -> service.preview(new PreviewRequest("Example.java", original, revision(original), candidate.id())), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test void commentsFromRemovedBranchAndIdenticalCopiesSurviveExactly() {
        String original = source("""
                /** документация */
                int run(int value) {
                    // перед условием
                    if (/* условие */ value > 0) {
                        /* одна и та же запись */
                        // первая ветка
                        return value;
                    } else {
                        /* одна и та же запись */
                        // вторая ветка
                        return value;
                    }
                }
                """);
        var changed = preview(original, "IDENTICAL_BRANCHES");
        assertThat(comments(changed.source())).isEqualTo(comments(original));
        assertThat(changed.source()).contains("вторая ветка", "первая ветка", "условие", "документация");
        assertThat(changed.afterFragment()).contains("вторая ветка");
    }

    @Test void mergesConditionsWithEffectsInOrderAndKeepsShortCircuit() throws Exception {
        String original = source("""
                static int trace;
                static boolean first(int a){trace=trace*10+1;return a>0;}
                static boolean second(int b){trace=trace*10+2;return b>0;}
                public static int run(int a,int b){
                    trace=0;
                    if(first(a)){if(second(b)){trace+=100;}}
                    return trace;
                }
                """);
        var changed = preview(original, "MERGE_NESTED_IF");
        assertThat(changed.afterFragment()).contains("(first(a)) && (second(b))");
        for (int[] input : new int[][]{{0,1},{1,0},{1,1}})
            assertThat(invoke(changed.source(), new Class<?>[]{int.class,int.class}, input[0], input[1]))
                    .isEqualTo(invoke(original, new Class<?>[]{int.class,int.class}, input[0], input[1]));
        assertThat(invoke(changed.source(), new Class<?>[]{int.class,int.class},0,1)).isEqualTo(1);
        assertThat(invoke(changed.source(), new Class<?>[]{int.class,int.class},1,0)).isEqualTo(12);
        assertThat(invoke(changed.source(), new Class<?>[]{int.class,int.class},1,1)).isEqualTo(112);
    }

    @Test void mergingKeepsAllCommentsAndReducesDeepNesting() {
        String original = source("""
                int run(int n) {
                    // outer
                    if (n>0) {
                        // between
                        if (/* inner */ n>1) {
                            if(n>2) {
                                if(n>3) return n;
                            }
                            // inner end
                        }
                        // outer end
                    }
                    return 0;
                }
                """);
        var changed = preview(original, "MERGE_NESTED_IF");
        assertThat(comments(changed.source())).isEqualTo(comments(original));
        assertThat(changed.conversion().analysis().methods().getFirst().maxNesting()).isEqualTo(3);
        assertThat(changed.conversion().analysis().findings()).noneMatch(f -> f.rule().equals("DEEP_NESTING"));
    }

    @ParameterizedTest @ValueSource(strings = {
            "int run(boolean a,boolean b){if(a){if(b)return 1;}else return 2;return 0;}",
            "int run(boolean a,boolean b){if(a){if(b)return 1;else return 2;}return 0;}",
            "int run(Object a){if(a instanceof String s){if(s.length()>0)return 1;}return 0;}"
    })
    void rejectsElseAndPatternMatchingInMerge(String method) {
        String original = source(method);
        var candidate = candidate(original, "MERGE_NESTED_IF");
        assertThat(candidate.available()).isFalse();
        status(() -> service.preview(new PreviewRequest("Example.java", original, revision(original), candidate.id())), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ParameterizedTest @ValueSource(strings = {
            "int run(boolean a,boolean b){int n=0;if(a){n++;if(b)return n;}return 0;}",
            "int run(boolean a,boolean b){if(a){;if(b)return 1;}return 0;}",
            "int run(boolean a,boolean b){if(a){inside:if(b)return 1;}return 0;}"
    })
    void doesNotMergeAcrossOtherStatementsOrLabels(String method) {
        assertThat(options(source(method)).candidates()).noneMatch(c -> c.rule().equals("MERGE_NESTED_IF") && c.available());
    }

    @Test void commonBlockPreservesScopeLabelsOuterElseAndFinally() throws Exception {
        String original = source("""
                public static int run(int value, boolean outer){
                    int result=0;
                    try {
                        exit: {
                            if(outer) {
                                if(value>0) {int local=7;result=local;break exit;}
                                else {int local=7;result=local;break exit;}
                            } else result=2;
                        }
                    } finally {result+=10;}
                    return result;
                }
                """);
        var changed = preview(original, "IDENTICAL_BRANCHES");
        for (boolean outer : new boolean[]{false,true})
            assertThat(invoke(changed.source(),new Class<?>[]{int.class,boolean.class},0,outer))
                    .isEqualTo(invoke(original,new Class<?>[]{int.class,boolean.class},0,outer));
        assertThat(changed.source()).contains("int local = 7", "exit:", "finally", "else");
    }

    @Test void mergedBlockPreservesLoopContinueLabelAndTryFinally() throws Exception {
        String original = source("""
                public static int run(int value){
                    int count=0;
                    outer: for(int i=0;i<3;i++) {
                        try {
                            if(value>0) {if(i<2) {continue outer;}}
                            count+=10;
                        } finally {count++;}
                    }
                    return count;
                }
                """);
        var changed = preview(original, "MERGE_NESTED_IF");
        for (int value : new int[]{0,1})
            assertThat(invoke(changed.source(),new Class<?>[]{int.class},value))
                    .isEqualTo(invoke(original,new Class<?>[]{int.class},value));
    }

    @Test void refusesStaleAndTamperedCandidatesAndIdsAreBoundToSource() {
        String original = source("int run(int n){if(n>0)return n;else return n;}");
        Candidate candidate = candidate(original, "IDENTICAL_BRANCHES");
        String revision = revision(original);
        status(() -> service.options(new OptionsRequest("Example.java",original+" ",revision)),HttpStatus.CONFLICT);
        status(() -> service.preview(new PreviewRequest("Example.java",original+" ",revision,candidate.id())),HttpStatus.CONFLICT);
        status(() -> service.preview(new PreviewRequest("Example.java",original,revision,"opt-tampered")),HttpStatus.UNPROCESSABLE_ENTITY);
        String changed = original + "\n";
        status(() -> service.preview(new PreviewRequest("Example.java",changed,revision(changed),candidate.id())),HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(candidate(changed,"IDENTICAL_BRANCHES").id()).isNotEqualTo(candidate.id());
    }

    @Test void manualRecommendationsAreVisibleUnavailableAndCatalogueIsBounded() {
        String original = source("void run(){try{}catch(Exception e){}}");
        assertThat(options(original).candidates()).anySatisfy(c -> {
            assertThat(c.rule()).isEqualTo("EMPTY_CATCH");
            assertThat(c.available()).isFalse();
            assertThat(c.reason()).isNotBlank();
        });
        StringBuilder many = new StringBuilder();
        for(int i=0;i<120;i++)many.append("int f").append(i).append("(int n){if(n>0)return n;else return n;}");
        assertThat(options(source(many.toString())).candidates()).hasSize(100);
    }

    @Test void graphLinksReferenceRealNodesIncludingMultipleFinallyCopies() {
        String original = source("int run(int n){try{if(n>0)return n;return -n;}finally{if(n>0)n++;else n++;}}");
        var response = converter.convert("Example.java", original);
        Candidate candidate = candidate(original,"IDENTICAL_BRANCHES");
        assertThat(candidate.nodeIds()).hasSizeGreaterThan(1);
        var flow = response.methods().stream().filter(m->m.id().equals(candidate.methodId())).findFirst().orElseThrow();
        assertThat(flow.nodes()).extracting(n->n.id()).containsAll(candidate.nodeIds());
        var changed = preview(original, "IDENTICAL_BRANCHES");
        assertThat(changed.conversion().methods().getFirst().nodes()).anyMatch(n -> n.id().equals(changed.selectedNodeId()));
    }

    @ParameterizedTest @ValueSource(strings = {
            "Object run(boolean b){if(b){return new Object(){};}else{return new Object(){};}}",
            "Object run(boolean b){if(b){class Local{}return new Local();}else{class Local{}return new Local();}}",
            "Object run(boolean b){if(b){record Local(int x){}return new Local(1);}else{record Local(int x){}return new Local(1);}}",
            "java.util.function.IntSupplier run(boolean b){if(b){return ()->1;}else{return ()->1;}}",
            "java.util.function.IntSupplier run(boolean b){if(b){return this::hashCode;}else{return this::hashCode;}}"
    })
    void preservesDistinctLocalTypesAndFunctionalCreationSitesByRefusingMerge(String method) {
        String original = source(method);
        Candidate candidate = candidate(original, "IDENTICAL_BRANCHES");
        assertThat(candidate.available()).isFalse();
        assertThat(candidate.reason()).contains("идентичность");
        status(() -> service.preview(new PreviewRequest("Example.java",original,revision(original),candidate.id())),HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test void ordinaryObjectCreationDoesNotIntroduceADistinctLocalType() {
        String original = source("Object run(boolean b){if(b){return new Object();}else{return new Object();}}");
        assertThat(candidate(original,"IDENTICAL_BRANCHES").available()).isTrue();
        assertThat(preview(original,"IDENTICAL_BRANCHES").source()).contains("return new Object();");
    }

    @Test void selectedNodeAfterMergePointsToMergedConditionNotNextStatement() {
        String original = source("int run(int n){int result=0;if(n>0){if(n<10){result=1;}}return result;}");
        Preview changed = preview(original,"MERGE_NESTED_IF");
        assertThat(changed.afterFragment()).contains("&&").doesNotContain("return result");
        var flow = changed.conversion().methods().stream().filter(m->m.id().equals(changed.selectedMethodId())).findFirst().orElseThrow();
        assertThat(flow.nodes()).anyMatch(n -> n.id().equals(changed.selectedNodeId()) && n.kind().equals("condition") && n.label().contains("&&"));
    }
}
