/*
 * This file is part of the CurseForge Java API library and is licensed under
 * the MIT license:
 *
 * MIT License
 *
 * Copyright (c) 2022 Matyrobbrt
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.matyrobbrt.curseforgeapi.request;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.matyrobbrt.curseforgeapi.annotation.Nonnull;
import io.github.matyrobbrt.curseforgeapi.annotation.Nullable;
import io.github.matyrobbrt.curseforgeapi.util.ExceptionFunction;
import io.github.matyrobbrt.curseforgeapi.util.Pair;
import io.github.matyrobbrt.curseforgeapi.util.Utils;

/**
 * An object used for async requests.
 * 
 * @author     matyrobbrt
 *
 * @param  <T> the response type
 */
public final class AsyncRequest<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncRequest.class);
    @Nonnull
    private static Executor futureExecutor = Executors
        .newSingleThreadExecutor(r -> new Thread(r, "AsyncRequestHandler"));

    public static void setFutureExecutor(@Nonnull Executor executor) {
        futureExecutor = Objects.requireNonNull(executor);
    }

    /**
     * Makes an {@link AsyncRequest} with the result asynchronously supplied by the
     * {@code supplier}.
     * 
     * @param  <T>      the type of the request
     * @param  supplier the supplier which supplies the value
     * @return          the request
     */
    public static <T> AsyncRequest<T> of(@Nonnull Supplier<T> supplier) {
        return new AsyncRequest<>(() -> CompletableFuture.supplyAsync(supplier));
    }

    /**
     * Makes an {@link AsyncRequest} which has been completed with the provided
     * {@code value}.
     * 
     * @param  <T>   the type of the request
     * @param  value the value which the request will return
     * @return       the request
     */
    public static <T> AsyncRequest<T> of(T value) {
        return new AsyncRequest<>(() -> CompletableFuture.completedFuture(value));
    }

    /**
     * Makes an {@link AsyncRequest} which if the provided {@code value} is null,
     * has been failed with a {@link NullPointerException}, otherwise completed with
     * the provided {@code value}.
     * 
     * @param  <T>   the type of the request
     * @param  value the value which the request will return, or null to fail the
     *               request with a {@link NullPointerException}
     * @return       the request
     */
    public static <T> AsyncRequest<T> ofNullable(@Nullable T value) {
        return new AsyncRequest<>(() -> value == null ? CompletableFuture.failedFuture(new NullPointerException())
            : CompletableFuture.completedFuture(value));
    }

    /**
     * @param  <T>
     * @return     an empty async request
     */
    public static <T> AsyncRequest<T> empty() {
        return new AsyncRequest<>(() -> CompletableFuture.failedFuture(new EmptyRequestException()));
    }

    private final Supplier<CompletableFuture<? super T>> future;

    public AsyncRequest(Supplier<CompletableFuture<? super T>> future) {
        this.future = future;
    }

    /**
     * Maps this request
     * 
     * @param  <U>    the new type of the request
     * @param  mapper the mapper to use
     * @return        the request
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public <U> AsyncRequest<U> map(@Nonnull Function<? super T, ? super U> mapper) {
        return new AsyncRequest<>(
            () -> future.get().thenCompose(o -> CompletableFuture.completedFuture(mapper.apply((T) o))));
    }

    /**
     * Intermediate operator that returns a modified {@link AsyncRequest}.
     *
     * @param  flatMap The mapping function to apply to the action result, must
     *                 return an {@link AsyncRequest}
     *
     * @param  <O>     The target output type
     *
     * @return         {@link AsyncRequest} for the mapped type
     *
     * @see            #map(Function)
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public <U> AsyncRequest<U> flatMap(@Nonnull Function<? super T, AsyncRequest<U>> mapper) {
        return new AsyncRequest<>(() -> future.get().thenCompose(o -> mapper.apply((T) o).future.get()));
    }

    /**
     * Intermediate operator that returns a modified {@link AsyncRequest}. <br>
     * Opposed to {@link #flatMap(Function)}, this method allows the mapper to throw
     * exceptions, which will be caught and a failed future will be thrown in such
     * cases.
     *
     * @param  flatMap The mapping function to apply to the action result, must
     *                 return an {@link AsyncRequest}
     *
     * @param  <O>     The target output type
     * @param  <O>     The exception type thrown by the mapper
     *
     * @return         {@link AsyncRequest} for the mapped type
     *
     * @see            #flatMap(Function)
     * @see            #map(Function)
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public <U, E extends Exception> AsyncRequest<U> flatMapWithException(
        @Nonnull ExceptionFunction<? super T, AsyncRequest<U>, E> mapper) {
        return new AsyncRequest<>(() -> future.get().thenCompose(o -> {
            try {
                return mapper.apply((T) o).future.get();
            } catch (Exception e) {
                Utils.sneakyThrow(e);
                return null; // theoretically, this will never be reached
            }
        }));
    }

    /**
     * Merges this request with the {@code other} one, making the new type of the
     * request a {@link Pair}.
     * 
     * @param  <U>   the type of the other request
     * @param  other the request to merge with
     * @return       a new request, whose type is a {@link Pair} of {@code T} and
     *               {@code U}
     */
    public <U> AsyncRequest<Pair<T, U>> and(@Nonnull AsyncRequest<U> other) {
        return new AsyncRequest<>(() -> future.get().thenCombine(other.future.get(), Pair::of));
    }

    /**
     * Completes this request by blocking the current thread until the value is
     * returned, and then returning it.
     * 
     * @return                      the value returned by the request
     * @throws InterruptedException
     * @throws ExecutionException
     */
    @SuppressWarnings("unchecked")
    public T get() throws InterruptedException, ExecutionException {
        return (T) future.get().get();
    }

    /**
     * Queues the action for later completion.
     */
    public void queue() {
        queue(null, null);
    }

    /**
     * Queues the action for later completion.
     * 
     * @param onSuccess the consumer to run when the action succeeded
     */
    public void queue(@Nullable Consumer<? super T> onSuccess) {
        queue(onSuccess, null);
    }

    /**
     * Queues the action for later completion.
     * 
     * @param onSuccess the consumer to run when the action succeeded
     * @param onFailure the consumer to run when the action fails, or is empty (it
     *                  fails with the {@link EmptyRequestException})
     */
    @SuppressWarnings("unchecked")
    public void queue(@Nullable Consumer<? super T> onSuccess, @Nullable Consumer<? super Throwable> onFailure) {
        futureExecutor.execute(() -> {
            try {
                final var tFuture = this.future.get().whenComplete((o, t) -> {
                    if (o != null && onSuccess != null) {
                        onSuccess.accept((T) o);
                    }
                    if (t != null && onFailure != null) {
                        onFailure.accept(t);
                    }
                });
                while (!tFuture.isDone()) {
                    Thread.sleep(100);
                }
                tFuture.get();
            } catch (ExecutionException e) {
                if (onFailure != null) {
                    onFailure.accept(e.getCause());
                }
            } catch (InterruptedException e) {
                LOGGER.error("InterruptedException while awaiting request!", e);
                throw new RuntimeException(e);
            }
        });
    }

    public static final class EmptyRequestException extends Exception {

        private static final long serialVersionUID = 586245362476362933L;

    }

}
