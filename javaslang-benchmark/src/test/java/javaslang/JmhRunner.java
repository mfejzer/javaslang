package javaslang;

import javaslang.collection.*;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.util.Collection;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class JmhRunner {
    /**
     * Runs all the available benchmarks in precision mode.
     * Note: it takes about 3 hours.
     */
    public static void main(String[] args) {
        final Array<Class<?>> CLASSES = Array.of(
                ArrayBenchmark.class,
                BitSetBenchmark.class,
                CharSeqBenchmark.class,
                HashSetBenchmark.class,
                ListBenchmark.class,
                PriorityQueueBenchmark.class,
                VectorBenchmark.class
        );
        runDebugWithAsserts(CLASSES);
        runSlowNoAsserts(CLASSES);
    }

    /** enables debugging and assertions for benchmarks and production code - the speed results will be totally unreliable */
    public static void runDebugWithAsserts(Array<Class<?>> groups) {
        final Array<String> classNames = groups.map(Class::getCanonicalName);
        run(classNames, 0, 1, 1, ForkJvm.DISABLE, VerboseMode.SILENT, Assertions.ENABLE, PrintInlining.DISABLE);

        MemoryUsage.printAndReset();
    }

    @SuppressWarnings("unused")
    public static void runQuickNoAsserts(Array<Class<?>> groups) {
        runAndReport(groups, 5, 5, 10, ForkJvm.ENABLE, VerboseMode.NORMAL, Assertions.DISABLE, PrintInlining.DISABLE);
    }

    @SuppressWarnings("unused")
    public static void runNormalNoAsserts(Array<Class<?>> groups) {
        runAndReport(groups, 10, 10, 200, ForkJvm.ENABLE, VerboseMode.NORMAL, Assertions.DISABLE, PrintInlining.DISABLE);
    }

    @SuppressWarnings("unused")
    public static void runSlowNoAsserts(Array<Class<?>> groups) {
        runAndReport(groups, 25, 15, 500, ForkJvm.ENABLE, VerboseMode.EXTRA, Assertions.DISABLE, PrintInlining.DISABLE);
    }

    public static void runAndReport(Array<Class<?>> groups, int warmupIterations, int measurementIterations, int millis, ForkJvm forkJvm, VerboseMode silent, Assertions assertions, PrintInlining printInlining) {
        final Array<String> classNames = groups.map(Class::getCanonicalName);
        final Array<RunResult> results = run(classNames, warmupIterations, measurementIterations, millis, forkJvm, silent, assertions, printInlining);
        BenchmarkPerformanceReporter.of(classNames, results).print();
    }

    private static Array<RunResult> run(Array<String> classNames, int warmupIterations, int measurementIterations, int millis, ForkJvm forkJvm, VerboseMode verboseMode, Assertions assertions, PrintInlining printInlining) {
        try {
            final ChainedOptionsBuilder builder = new OptionsBuilder()
                    .shouldDoGC(true)
                    .verbosity(verboseMode)
                    .shouldFailOnError(true)
                    .mode(Mode.Throughput)
                    .timeUnit(TimeUnit.SECONDS)
                    .warmupTime(TimeValue.milliseconds(millis))
                    .warmupIterations(warmupIterations)
                    .measurementTime(TimeValue.milliseconds(millis))
                    .measurementIterations(measurementIterations)
                    .forks(forkJvm.forkCount)
                  /* We are using 4Gb and setting NewGen to 100% to avoid GC during testing.
                     Any GC during testing will destroy the iteration (i.e. introduce unreliable noise in the measurement), which should get ignored as an outlier */
                    .jvmArgsAppend("-XX:+UseG1GC", "-Xss100m", "-Xms4g", "-Xmx4g", "-XX:MaxGCPauseMillis=1000", "-XX:+UnlockExperimentalVMOptions", "-XX:G1NewSizePercent=100", "-XX:G1MaxNewSizePercent=100", assertions.vmArg);
            classNames.forEach(builder::include);

            if (printInlining == PrintInlining.ENABLE) {
                builder.jvmArgsAppend("-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintInlining"); /* might help in deciding when the JVM is properly warmed up - or where to optimize the code */
            }

            return Array.ofAll(new Runner(builder.build()).run());
        } catch (RunnerException e) {
            throw new RuntimeException(e);
        }
    }

    /* Options */
    private enum ForkJvm {
        ENABLE(1),
        DISABLE(0);

        final int forkCount;

        ForkJvm(int forkCount) {
            this.forkCount = forkCount;
        }
    }

    private enum Assertions {
        ENABLE("-enableassertions"),
        DISABLE("-disableassertions");

        final String vmArg;

        Assertions(String vmArg) {
            this.vmArg = vmArg;
        }
    }

    private enum PrintInlining {
        ENABLE,
        DISABLE;
    }

    /* Helper methods */

    public static Integer[] getRandomValues(int size, int seed) {
        return getRandomValues(size, seed, false);
    }

    public static Integer[] getRandomValues(int size, int seed, boolean nonNegative) {
        return getRandomValues(size, nonNegative, new Random(seed));
    }

    public static Integer[] getRandomValues(int size, boolean nonNegative, Random random) {
        final Integer[] results = new Integer[size];
        for (int i = 0; i < size; i++) {
            final int value = random.nextInt(size) - (nonNegative ? 0 : (size / 2));
            results[i] = value;
        }
        return results;
    }

    /** used for dead code elimination and correctness assertion inside the benchmarks */
    public static int aggregate(int x, int y) {
        return x ^ y;
    }

    /** simplifies construction of immutable collections - with assertion and memory benchmarking */
    public static <T extends Collection<?>, R> R create(Function1<T, R> function, T source, Function1<R, Boolean> assertion) {
        return create(function, source, source.size(), assertion);
    }

    @SuppressWarnings("unchecked")
    public static <T, R> R create(Function1<T, R> function, T source, int elementCount, Function1<R, Boolean> assertion) {
        final R result = function.apply(source);
        assert assertion.apply(result);

        MemoryUsage.storeMemoryUsages(source, elementCount, result);

        return result;
    }
}
