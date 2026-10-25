package dev.blueprints.check;

import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import static dev.blueprints.check.CheckModels.TestCase;

/** Читаем только результаты, без свойств JVM; XML не может загружать внешние файлы. */
final class JunitReportReader {
    record Report(List<TestCase> tests, int passed, int failed, int skipped, boolean truncated) {}

    static Report read(Path reports) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        Path xml = reports.resolve("TEST-junit-jupiter.xml");
        if (!Files.isRegularFile(xml, LinkOption.NOFOLLOW_LINKS) || Files.size(xml) > 2 * 1024 * 1024)
            throw new IllegalStateException("JUnit не создал читаемый отчёт или отчёт слишком большой.");
        var document = factory.newDocumentBuilder().parse(xml.toFile());
        var cases = document.getElementsByTagName("testcase");
        List<TestCase> tests = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;
        boolean truncated = false;
        for (int i = 0; i < cases.getLength(); i++) {
            Element item = (Element) cases.item(i);
            String status = "passed", message = "";
            Element problem = first(item, "failure");
            if (problem == null) problem = first(item, "error");
            if (problem != null) {
                status = "failed"; failed++;
                message = problem.getAttribute("message") + "\n" + problem.getTextContent();
            } else if ((problem = first(item, "skipped")) != null) {
                status = "skipped"; skipped++;
                message = problem.getAttribute("message") + problem.getTextContent();
            } else passed++;
            double seconds;
            try { seconds = Double.parseDouble(item.getAttribute("time")); } catch (NumberFormatException e) { seconds = 0; }
            if (!Double.isFinite(seconds)) seconds = 0;
            truncated |= item.getAttribute("name").length() > 300 || item.getAttribute("classname").length() > 300 || message.strip().length() > 4000;
            if (tests.size() < 200) tests.add(new TestCase(limit(item.getAttribute("name"), 300),
                limit(item.getAttribute("classname"), 300), status, limit(message.strip(), 4000), seconds));
        }
        return new Report(List.copyOf(tests), passed, failed, skipped, truncated || cases.getLength() > tests.size());
    }
    private static Element first(Element parent, String tag) {
        var items = parent.getElementsByTagName(tag);
        return items.getLength() == 0 ? null : (Element) items.item(0);
    }
    private static String limit(String text, int max) { return text.length() <= max ? text : text.substring(0, max) + "…"; }
}
