package dev.blueprints.roundtrip;

import com.github.javaparser.JavaParser;
import dev.blueprints.flow.FlowGraphBuilder;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.visitor.NoCommentEqualsVisitor;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.printer.configuration.DefaultPrinterConfiguration;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static dev.blueprints.roundtrip.RoundTripModels.*;

@Service
public class RoundTripService {
    public static final int MAX_SOURCE_BYTES = 1024 * 1024;

    public Response convert(String fileName, String source) {
        String safeName = validateName(fileName);
        if (source == null || source.isBlank()) {
            throw new SourceException(HttpStatus.BAD_REQUEST, "Добавьте Java-код: файл пуст.");
        }
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new SourceException(HttpStatus.PAYLOAD_TOO_LARGE, "Размер кода превышает 1 МБ.");
        }
        // BOM иногда появляется в UTF-8 файлах, сохранённых редакторами Windows.
        String normalized = source.startsWith("\uFEFF") ? source.substring(1) : source;
        if (normalized.isBlank()) {
            throw new SourceException(HttpStatus.BAD_REQUEST, "Добавьте Java-код: файл пуст.");
        }

        // Новый parser для каждого запроса: общий изменяемый StaticJavaParser не нужен.
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setCharacterEncoding(StandardCharsets.UTF_8));
        CompilationUnit original = parse(parser, normalized);

        // Код действительно создаётся из AST. Исходный текст здесь не возвращается.
        String generated = new DefaultPrettyPrinter(new DefaultPrinterConfiguration())
                .print(original).replace("\r\n", "\n");
        if (!generated.endsWith("\n")) generated += "\n";

        CompilationUnit restored = parse(parser, generated);
        // Комментарии могут переформатироваться принтером; сравниваем структуру кода.
        boolean equivalent = NoCommentEqualsVisitor.equals(original, restored);
        List<Declaration> declarations = new ArrayList<>();
        List<TypeDeclaration<?>> types = new ArrayList<>();
        original.walk(node -> { if (node instanceof TypeDeclaration<?> type) types.add(type); });
        types.forEach(type ->
                declarations.add(new Declaration("type", type.getNameAsString(),
                        type.getBegin().map(p -> p.line).orElse(1))));
        original.findAll(MethodDeclaration.class).forEach(method ->
                declarations.add(new Declaration("method", method.getNameAsString() + "()",
                        method.getBegin().map(p -> p.line).orElse(1))));
        original.findAll(ConstructorDeclaration.class).forEach(constructor ->
                declarations.add(new Declaration("constructor", constructor.getNameAsString() + "()",
                        constructor.getBegin().map(p -> p.line).orElse(1))));
        declarations.sort(Comparator.comparingInt(Declaration::line));

        Statistics statistics = new Statistics(
                types.size(),
                original.findAll(MethodDeclaration.class).size(),
                original.findAll(ConstructorDeclaration.class).size(),
                lineCount(normalized), lineCount(generated), original.stream().count());
        var flows = new FlowGraphBuilder().build(original);
        return new Response(safeName, generated, "Java 21", true, equivalent,
                statistics, List.copyOf(declarations), flows, revision(normalized), new dev.blueprints.flow.StructureGraphBuilder().build(original),
                new dev.blueprints.analysis.SourceAnalyzer().analyze(original, flows));
    }

    private String revision(String source) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private CompilationUnit parse(JavaParser parser, String source) {
        var result = parser.parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            List<Diagnostic> diagnostics = result.getProblems().stream()
                    .limit(20).map(this::diagnostic).toList();
            throw new SourceException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Не удалось разобрать Java-код. Проверьте отмеченные строки.", diagnostics);
        }
        return result.getResult().orElseThrow();
    }

    private Diagnostic diagnostic(Problem problem) {
        var position = problem.getLocation()
                .flatMap(range -> range.getBegin().getRange()).map(range -> range.begin);
        return new Diagnostic(problem.getMessage(),
                position.map(p -> p.line).orElse(null),
                position.map(p -> p.column).orElse(null));
    }

    private String validateName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new SourceException(HttpStatus.BAD_REQUEST, "Укажите имя файла с расширением .java.");
        }
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.length() <= 5 || name.length() > 255
                || !name.toLowerCase(Locale.ROOT).endsWith(".java")) {
            throw new SourceException(HttpStatus.BAD_REQUEST, "Выберите файл с расширением .java.");
        }
        return name;
    }

    private int lineCount(String source) {
        return (int) source.lines().count();
    }
}
