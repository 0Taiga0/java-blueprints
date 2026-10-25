package dev.blueprints.check;

import java.util.*;
import static dev.blueprints.check.CheckModels.*;

/** Сравниваем утверждения JUnit, а не время запуска или служебный текст консоли. */
final class BehaviorComparison {
    private record Key(String className, String name) {}
    private static Map<Key, List<TestCase>> index(Result result) {
        Map<Key, List<TestCase>> index = new LinkedHashMap<>();
        for (TestCase test : result.tests())
            index.computeIfAbsent(new Key(test.className(), test.name()), ignored -> new ArrayList<>()).add(test);
        return index;
    }

    static Comparison compare(Result before, Result after) {
        var left = index(before);
        var right = index(after);
        Set<Key> keys = new LinkedHashSet<>(left.keySet()); keys.addAll(right.keySet());
        List<TestComparison> rows = new ArrayList<>();
        int unchanged = 0, regressions = 0, fixed = 0, unresolved = 0;
        boolean ambiguous = false;
        for (Key key : keys) {
            var originals = left.getOrDefault(key, List.of());
            var currents = right.getOrDefault(key, List.of());
            TestCase old = originals.size() == 1 ? originals.getFirst() : null;
            TestCase current = currents.size() == 1 ? currents.getFirst() : null;
            String change;
            if (originals.size() > 1 || currents.size() > 1) { change = "ambiguous"; ambiguous = true; }
            else if (old == null || current == null) change = "missing";
            else if (old.status().equals("passed") && current.status().equals("failed")) change = "regression";
            else if (old.status().equals("failed") && current.status().equals("passed")) change = "fixed";
            else if (old.status().equals("passed") && current.status().equals("passed")) change = "unchanged";
            else if (old.status().equals("failed") && current.status().equals("failed")) change = "both_failed";
            else change = "skipped";
            switch (change) {
                case "unchanged" -> unchanged++;
                case "regression" -> regressions++;
                case "fixed" -> fixed++;
                default -> unresolved++;
            }
            rows.add(new TestComparison(key.name(), key.className(), change, old, current));
        }
        List<String> reasons = new ArrayList<>();
        if (before.skipped() > 0 || after.skipped() > 0) reasons.add("Часть тестов пропущена: полная проверка не выполнена.");
        if (before.outputTruncated() || after.outputTruncated()) reasons.add("Один из отчётов или выводов сокращён: данных для полного сравнения недостаточно.");
        if (!left.keySet().equals(right.keySet())) reasons.add("Набор обнаруженных тестов различается. Отсутствующий тест не считается пройденным.");
        if (ambiguous) reasons.add("Есть тесты с одинаковыми именами внутри одного класса. Задайте уникальные имена для однозначного сопоставления.");
        if (before.tests().isEmpty() || after.tests().isEmpty()) reasons.add("Для одной из версий нет результатов отдельных тестов.");
        if (incompleteRun(before)) reasons.add("До изменений: " + before.message());
        if (incompleteRun(after)) reasons.add("После изменений: " + after.message());

        String status, message;
        if (before.status().equals("tests_failed") || before.status().equals("compile_error") || before.failed() > 0) {
            status = "baseline_failed";
            message = "Исходная версия уже содержит ошибки. Совпадение поведения не подтверждено, даже если новая версия стала лучше.";
        } else if (regressions > 0 || (before.status().equals("passed") && after.status().equals("tests_failed"))) {
            status = "changed";
            message = "После изменений появились ошибки тестов. Откройте строки отчёта и сравните ожидаемый и полученный ответ.";
        } else if (!before.status().equals("passed") || !after.status().equals("passed") ||
                   !reasons.isEmpty() || unresolved > 0 || unchanged == 0) {
            status = "inconclusive";
            message = "Сравнение не завершено полностью. Устраните причины ниже и повторите запуск.";
        } else {
            status = "matched";
            message = "Обе версии прошли один и тот же набор тестов. Проверяемое этими тестами поведение сохранилось.";
        }
        return new Comparison(status, message, List.copyOf(reasons), before, after, List.copyOf(rows),
                unchanged, regressions, fixed, unresolved);
    }

    private static boolean incompleteRun(Result result) {
        return !Set.of("passed", "tests_failed").contains(result.status());
    }
}
