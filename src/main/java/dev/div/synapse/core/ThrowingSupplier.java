package dev.div.synapse.core;

/** A {@link java.util.function.Supplier} whose body may throw checked exceptions. */
@FunctionalInterface
public interface ThrowingSupplier<T> {
    T get() throws Exception;
}
