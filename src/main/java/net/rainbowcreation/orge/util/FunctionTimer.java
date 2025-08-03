package net.rainbowcreation.orge.util;

import java.util.function.Function;

public class FunctionTimer {

    public static class Result<R> {
        public final R value;
        public final long elapsedNano;

        public Result(R value, long elapsedNano) {
            this.value = value;
            this.elapsedNano = elapsedNano;
        }

        @Override
        public String toString() {
            return this.value + " (" + this.elapsedNano + " ns)";// (" + getElapsedMilli() + " ms)";
        }
        /*
        public long getElapsedMilli() {
            return this.elapsedNano / 1000000;
        }
         */
    }

    public static <T, R> Result<R> measure(Function<T, R> func, T arg) {
        long start = System.nanoTime();
        R result = func.apply(arg);
        long end = System.nanoTime();
        return new Result<>(result, end - start);
    }
}
