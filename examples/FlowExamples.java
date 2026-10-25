// Примеры для выбора метода и проверки стрелок на схеме.
public class FlowExamples {
    public static int sumPositive(int[] values) {
        int sum = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] < 0) {
                continue;
            }
            if (sum > 1000) {
                break;
            }
            sum += values[i];
        }
        return sum;
    }

    // Перегрузка: то же имя, другие параметры.
    public static int sumPositive(int first, int second) {
        if (first < 0) {
            return second;
        } else {
            return first + second;
        }
    }

    public static int countdown(int count) {
        do {
            count--;
        } while (count > 0);
        return count;
    }

    // finally выполняется и при return: на схеме виден соответствующий маршрут.
    public static int withFinally() {
        try {
            return 1;
        } finally {
            System.out.println("Завершили");
        }
    }
}
