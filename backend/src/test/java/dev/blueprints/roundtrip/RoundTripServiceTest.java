package dev.blueprints.roundtrip;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class RoundTripServiceTest {
    private final RoundTripService service = new RoundTripService();

    @TempDir Path temporary;

    @Test
    void generatesCodeFromAstAndKeepsRussianTextAndStructure() {
        String source = "public class Greeting{/** Привет */public static void main(String[] a){System.out.println(\"Привет, мир!\");}}";
        var result = service.convert("Greeting.java", source);
        assertThat(result.generatedSource()).isNotEqualTo(source).contains("Привет, мир!", "public class Greeting");
        assertThat(result.reparsed()).isTrue();
        assertThat(result.structureEquivalent()).isTrue();
        assertThat(result.statistics().types()).isEqualTo(1);
        assertThat(result.statistics().methods()).isEqualTo(1);
        assertThat(result.declarations()).extracting(RoundTripModels.Declaration::name)
                .containsExactly("Greeting", "main()");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PriceCalculator.java", "LanguageFeatures.java"})
    void originalAndGeneratedExamplesBothCompile(String fileName) throws Exception {
        String source = Files.readString(Path.of("../examples", fileName), StandardCharsets.UTF_8);
        var result = service.convert(fileName, source);
        assertThat(result.structureEquivalent()).isTrue();
        compile("original", fileName, source);
        compile("generated", fileName, result.generatedSource());
    }

    private void compile(String directory, String fileName, String source) throws Exception {
        Path output = Files.createDirectories(temporary.resolve(directory));
        Path input = output.resolve(fileName);
        Files.writeString(input, source, StandardCharsets.UTF_8);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("Tests require JDK, not JRE").isNotNull();
        int status = compiler.run(null, null, null, "-encoding", "UTF-8",
                "--release", "21", "-d", output.toString(), input.toString());
        assertThat(status).as("Generated source must compile").isZero();
    }

    @Test
    void handlesMoreThanOneThousandLines() {
        String source = "class Large { int sum() { int s = 0;\n" + "s += 1;\n".repeat(1100) + "return s; }}";
        var result = service.convert("Large.java", source);
        assertThat(result.structureEquivalent()).isTrue();
        assertThat(result.statistics().sourceLines()).isGreaterThan(1000);
    }

    @Test
    void acceptsUtf8BomAndRemovesClientPathFromName() {
        var result = service.convert("C:\\fakepath\\Hello.java", "\uFEFFclass Hello {}");
        assertThat(result.fileName()).isEqualTo("Hello.java");
        assertThat(result.structureEquivalent()).isTrue();
    }

    @Test
    void reportsSyntaxLocationWithoutReturningFakeSuccess() {
        assertThatThrownBy(() -> service.convert("Broken.java", "class Broken {\n int value = ;\n}"))
                .isInstanceOfSatisfying(SourceException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(422);
                    assertThat(error.diagnostics()).isNotEmpty();
                    assertThat(error.diagnostics().getFirst().line()).isEqualTo(2);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\uFEFF"})
    void rejectsEmptyCode(String source) {
        assertThatThrownBy(() -> service.convert("Empty.java", source)).isInstanceOf(SourceException.class);
    }

    @Test
    void rejectsWrongExtension() {
        assertThatThrownBy(() -> service.convert("Hello.txt", "class Hello {}"))
                .isInstanceOf(SourceException.class).hasMessageContaining(".java");
    }

    @Test
    void enforcesUtf8ByteLimit() {
        String source = "//" + "я".repeat(RoundTripService.MAX_SOURCE_BYTES / 2);
        assertThatThrownBy(() -> service.convert("Large.java", source))
                .isInstanceOfSatisfying(SourceException.class,
                        error -> assertThat(error.status().value()).isEqualTo(413));
    }
}
