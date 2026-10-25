package dev.blueprints.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import dev.blueprints.flow.FlowGraphBuilder;
import dev.blueprints.roundtrip.RoundTripService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SourceAnalyzerTest {
    private final RoundTripService service = new RoundTripService();

    private AnalysisModels.Report report(String methods) {
        return service.convert("Example.java", "class Example { " + methods + " }").analysis();
    }
    private AnalysisModels.Complexity complexity(String method) {
        return report(method).methods().getFirst().complexity();
    }
    private List<AnalysisModels.Finding> findings(String method, String rule) {
        return report(method).findings().stream().filter(f -> f.rule().equals(rule)).toList();
    }

    @Test void sequentialTraversalsStayLinearAndNestedBecomeQuadratic() {
        var sequential = complexity("int f(int[] a){int s=0;for(int x:a)s+=x;for(int x:a)s+=x;return s;}");
        var nested = complexity("int f(int[] a){int s=0;for(int x:a)for(int y:a)s+=x*y;return s;}");
        assertThat(sequential.loopEstimate()).isEqualTo("O(n)");
        assertThat(sequential.methodEstimate()).isEqualTo("O(n)");
        assertThat(nested.loopEstimate()).isEqualTo("O(n²)");
        assertThat(nested.methodEstimate()).isEqualTo("O(n²)");
        assertThat(nested.variables()).hasSize(1);
        assertThat(findings("int f(int[] a){int s=0;for(int x:a)for(int y:a)s+=x*y;return s;}", "NESTED_TRAVERSAL"))
                .hasSize(1);
    }
    @Test void partialCostlyTraversalNeverPrintsNullAsAnEstimate() {
        var value = report("int f(int n){int s=0;for(int i=0;i<n;i++)for(int j=0;j<n;j++)s++;while(n>0)n--;return s;}");
        assertThat(value.methods().getFirst().complexity().loopEstimate()).isNull();
        assertThat(value.findings()).filteredOn(f -> f.rule().equals("NESTED_TRAVERSAL")).hasSize(1)
                .allSatisfy(f -> assertThat(f.message()).doesNotContain("null").contains("неизвестна"));
    }
    @Test void independentDimensionsAreNotCollapsedIntoNSquared() {
        var sequential = complexity("int f(int n,int m){int s=0;for(int i=0;i<n;i++)s++;for(int j=0;j<m;j++)s++;return s;}");
        var nested = complexity("int f(int n,int m){int s=0;for(int i=0;i<n;i++)for(int j=0;j<m;j++)s++;return s;}");
        assertThat(sequential.loopEstimate()).isEqualTo("O(n + m)");
        assertThat(nested.loopEstimate()).contains("n", "m", "·").doesNotContain("²");
        assertThat(nested.variables()).hasSize(2);
    }
    @Test void sameArrayDimensionIsSharedBetweenForAndForEach() {
        var value = complexity("int f(int[] a){int s=0;for(int x:a)for(int i=0;i<a.length;i++)s+=x;return s;}");
        assertThat(value.loopEstimate()).isEqualTo("O(n²)");
        assertThat(value.variables()).hasSize(1);
    }
    @Test void constantBoundsStayConstant() {
        var value = complexity("int f(){int s=0;for(int i=0;i<10;i++)for(int j=0;j<100;j++)s++;return s;}");
        assertThat(value.loopEstimate()).isEqualTo("O(1)");
        assertThat(value.methodEstimate()).isEqualTo("O(1)");
        assertThat(value.variables()).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {
            "for(int i=1;i<n;i*=2)s++;",
            "for(int i=n;i>1;i/=2)s++;",
            "for(int i=n;i>=1;i/=2)s++;"
    })
    void recognizesLogarithmicSteps(String loop) {
        var value = complexity("int f(int n){int s=0;" + loop + "return s;}");
        assertThat(value.loopEstimate()).isEqualTo("O(log n)");
        assertThat(value.methodEstimate()).isEqualTo("O(log n)");
    }
    @Test void linearTermDominatesLogTermAndConstants() {
        var value = complexity("int f(int n){int s=0;for(int i=1;i<n;i*=2)s++;for(int j=0;j<n;j++)s++;return s;}");
        assertThat(value.loopEstimate()).isEqualTo("O(n)");
    }
    @ParameterizedTest @ValueSource(strings = {
            "for(int i=0;i<n;i--)s++;",
            "for(int i=0;i<n;i+=0)s++;",
            "for(int i=0;i<n;i*=2)s++;",
            "for(int i=n;i>=0;i/=2)s++;",
            "for(int i=0;i<n;i++){i--;s++;}",
            "for(int i=0;i<n;i++){n++;s++;}",
            "for(int i=0;i<n;i++)for(int j=0;j<i;j++)s++;",
            "for(;;){s++;break;}",
            "while(n>0){n--;s++;}",
            "do{s++;}while(n>0);"
    })
    void uncertainLoopsNeverReceiveConstantOrLinearCertificate(String loop) {
        var value = complexity("int f(int n){int s=0;" + loop + "return s;}");
        assertThat(value.loopEstimate()).isNull();
        assertThat(value.methodEstimate()).isNull();
        assertThat(value.unknownReasons()).isNotEmpty();
    }
    @Test void rowsOfJaggedArrayAreDependentDimensions() {
        var value = complexity("int f(int[][] grid){int s=0;for(int[] row:grid)for(int x:row)s+=x;return s;}");
        assertThat(value.loopEstimate()).isNull();
        assertThat(value.methodEstimate()).isNull();
    }
    @Test void modifyingCollectionSizeMakesTraversalUnknown() {
        var value = complexity("void f(java.util.List<Integer> a){for(int i=0;i<a.size();i++)a.add(i);}");
        assertThat(value.loopEstimate()).isNull();
        assertThat(value.methodEstimate()).isNull();
    }
    @Test void unknownCallKeepsLoopEstimateButNotWholeMethodEstimate() {
        var value = complexity("void f(int n){for(int i=0;i<n;i++)for(int j=0;j<n;j++)work(i,j);} void work(int x,int y){}");
        assertThat(value.loopEstimate()).isEqualTo("O(n²)");
        assertThat(value.methodEstimate()).isNull();
        assertThat(value.unknownReasons()).anyMatch(s -> s.contains("вызовов"));
    }
    @ParameterizedTest @ValueSource(strings = {
            "int f(int n){return f(n-1);}",
            "void f(int[] a){java.util.Arrays.sort(a);}",
            "int[] f(int n){return new int[n];}",
            "String f(String a,String b){return a+b;}",
            "Object f(){return new Object();}",
            "String value; void f(){value += value;}"
    })
    void callsAllocationsAndStringsAreNotDeclaredConstantTime(String method) {
        assertThat(complexity(method).methodEstimate()).isNull();
    }
    @Test void noBodyHasNoEstimateAndNoFalseProblems() {
        var value = service.convert("Example.java", "interface Example { int calculate(int n); }").analysis();
        assertThat(value.methods()).hasSize(1);
        assertThat(value.methods().getFirst().complexity().loopEstimate()).isNull();
        assertThat(value.methods().getFirst().complexity().methodEstimate()).isNull();
        assertThat(value.findings()).isEmpty();
    }
    @Test void ignoresNestedClassAndLambdaBodiesInOuterMethod() {
        var value = report("void outer(){class Inner{void nested(){while(true){}}} Runnable work=()->{while(true){}};}");
        var outer = value.methods().stream().filter(m -> m.signature().startsWith("outer")).findFirst().orElseThrow();
        assertThat(outer.statementCount()).isEqualTo(1);
        assertThat(outer.maxNesting()).isZero();
        assertThat(outer.complexity().loopEstimate()).isEqualTo("O(1)");
        assertThat(value.findings()).filteredOn(f -> f.methodId().equals(outer.methodId())).isEmpty();
        assertThat(value.methods()).hasSize(2);
    }
    @Test void longMethodThresholdCountsOperatorsNotLinesOrEmptyBlocks() {
        String forty = "void f(){int n=0;" + "n++;".repeat(39) + "}";
        assertThat(findings(forty, "LONG_METHOD")).isEmpty();
        assertThat(findings("void f(){int n=0;" + "n++;".repeat(40) + "}", "LONG_METHOD")).hasSize(1);
        assertThat(findings("void f(){;;;;{}{}" + "\n".repeat(100) + "}", "LONG_METHOD")).isEmpty();
    }
    @Test void deepNestingNeedsFourControlsNotBraces() {
        assertThat(findings("void f(int n){if(n>0)if(n>1)if(n>2)if(n>3)n++;}", "DEEP_NESTING")).hasSize(1);
        assertThat(findings("void f(int n){if(n>0){{{{n++;}}}}}", "DEEP_NESTING")).isEmpty();
    }
    @Test void commentOnlyCatchIsStillEmptyButActionIsNot() {
        assertThat(findings("void f(){try{}catch(Exception e){/* consciously ignored */}}", "EMPTY_CATCH")).hasSize(1);
        assertThat(findings("void f(){try{}catch(Exception e){System.err.println(e);}}", "EMPTY_CATCH")).isEmpty();
    }
    @Test void identicalBranchesIgnoreCommentsButPreserveSideEffectWarning() {
        var result = findings("void f(int n){if(check()){/* yes */ n++;}else{/* no */ n++;}} boolean check(){return true;}", "IDENTICAL_BRANCHES");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().recommendation()).contains("побочные эффекты");
        assertThat(findings("void f(int n){if(n>0)n++;else n--;}", "IDENTICAL_BRANCHES")).isEmpty();
    }
    @Test void duplicatesRequireThreeNonoverlappingActionsAndCompareAstNotStrippedStrings() {
        assertThat(findings("int f(int x){x++;x*=2;x-=3;/* note */x++;x*=2;x-=3;return x;}", "DUPLICATE_SEQUENCE")).hasSize(1);
        assertThat(findings("int f(int x){x++;x*=2;x++;x*=2;return x;}", "DUPLICATE_SEQUENCE")).isEmpty();
        assertThat(findings("int f(int x){x++;x++;x++;x++;return x;}", "DUPLICATE_SEQUENCE")).isEmpty();
        assertThat(findings("void f(){String x; x=\"//a\";x+=\"a\";x+=\"b\";x=\"//b\";x+=\"a\";x+=\"b\";}", "DUPLICATE_SEQUENCE")).isEmpty();
    }
    @Test void everyFindingLinksToExistingMethodAndNodesIncludingOverview() {
        var response = service.convert("Example.java", "class Example{void f(){int n=0;" + "n++;".repeat(400) + "}}");
        assertThat(response.methods().getFirst().mode()).isEqualTo("overview");
        assertThat(response.analysis().findings()).isNotEmpty().allSatisfy(finding -> {
            var flow = response.methods().stream().filter(m -> m.id().equals(finding.methodId())).findFirst().orElseThrow();
            assertThat(finding.nodeIds()).isNotEmpty();
            assertThat(flow.nodes()).extracting(n -> n.id()).containsAll(finding.nodeIds());
        });
    }
    @Test void mapsAllCopiesOfFinallyCondition() {
        var response = service.convert("Example.java",
                "class Example{int f(int x){try{if(x>0)return x;return -x;}finally{if(x>0)x++;else x++;}}}");
        var finding = response.analysis().findings().stream().filter(f -> f.rule().equals("IDENTICAL_BRANCHES")).findFirst().orElseThrow();
        assertThat(finding.nodeIds()).hasSizeGreaterThan(1);
        var flow = response.methods().getFirst();
        assertThat(flow.nodes().stream().filter(n -> finding.nodeIds().contains(n.id()))).allMatch(n -> n.range().equals(finding.range()));
    }
    @Test void analysisDoesNotMutateAstOrCommentsAndIsRepeatable() {
        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        var unit = parser.parse("class Example{/** saved */int f(int n){if(n>0){/* a */return n;}else{/* b */return n;}}}").getResult().orElseThrow();
        String before = unit.toString();
        var flows = new FlowGraphBuilder().build(unit);
        var analyzer = new SourceAnalyzer();
        var first = analyzer.analyze(unit, flows);
        var second = analyzer.analyze(unit, flows);
        assertThat(unit.toString()).isEqualTo(before);
        assertThat(second).isEqualTo(first);
    }
    @Test void limitsDiagnosticVolumeWithoutBreakingConversion() {
        String methods = "";
        for (int i=0;i<230;i++) methods += "int f"+i+"(int x){if(x>0)return x;else return x;}";
        var value = report(methods);
        assertThat(value.findings()).hasSize(200);
        assertThat(value.truncated()).isTrue();
        assertThat(value.methods()).hasSize(230);
    }
    @Test void deeplyNestedExpressionsProduceAnExplicitIncompleteReport() {
        String expression = "n";
        for (int i=0;i<125;i++) expression = "(" + expression + "+1)";
        var value = report("int f(int n){return " + expression + ";}");
        assertThat(value.truncated()).isTrue();
        assertThat(value.methods().getFirst().complexity().methodEstimate()).isNull();
    }
}
