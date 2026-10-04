package com.loadpilot.backend.service.execution;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Corps limite a "limit" octets (debut du corps, decode en UTF-8) + taille totale reelle recue.
 * Le reste du corps est lu puis ignore au fil de l'eau : jamais plus de "limit" octets gardes en
 * memoire, quelle que soit la taille de la reponse.
 */
public record CappedBody(String text, long totalBytes, boolean truncated) {

    public static CappedBody of(String full, int limit) {
        if (full == null) return new CappedBody(null, 0, false);
        byte[] bytes = full.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) return new CappedBody(full, bytes.length, false);
        return new CappedBody(new String(bytes, 0, limit, StandardCharsets.UTF_8), bytes.length, true);
    }

    /** BodyHandler qui ne garde que les "limit" premiers octets du corps. */
    public static HttpResponse.BodyHandler<CappedBody> handler(int limit) {
        return info -> new Subscriber(limit);
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<CappedBody> {
        private final int limit;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final CompletableFuture<CappedBody> result = new CompletableFuture<>();
        private long total;

        Subscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<CappedBody> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int remaining = buffer.remaining();
                total += remaining;
                int room = limit - kept.size();
                if (room > 0) {
                    byte[] chunk = new byte[Math.min(room, remaining)];
                    buffer.get(chunk);
                    kept.write(chunk, 0, chunk.length);
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(new CappedBody(kept.toString(StandardCharsets.UTF_8), total, total > limit));
        }
    }
}
