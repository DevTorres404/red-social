import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Smoke probe for the broadcast-only feed WebSocket channel.
 *
 * Driven by scripts/phase-feed-ws-smoke.ps1 against the running compose stack.
 * Proves:
 *   - follower B opens /ws/feed with a single-use ticket and gets "ready"
 *   - a replayed ticket is rejected with close code 1008
 *   - B receives like-changed (likeCount, liked, actorId) when C likes A's post
 *   - an idempotent duplicate like is NOT rebroadcast
 *   - B receives comment-created with the comment payload
 *   - outsider D (who does not follow A) receives none of these events
 */
public class PhaseFeedWebSocketSmoke {
    static final class Probe implements WebSocket.Listener {
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        final StringBuilder partial = new StringBuilder();

        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletableFuture<?> onText(WebSocket socket, CharSequence text, boolean last) {
            partial.append(text);
            if (last) { events.add(partial.toString()); partial.setLength(0); }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<?> onClose(WebSocket socket, int status, String reason) {
            events.add("CLOSED:" + status);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket socket, Throwable error) { events.add("ERROR:" + error.getMessage()); }
    }

    static String waitFor(Probe probe, String marker) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            String value = probe.events.poll(1, TimeUnit.SECONDS);
            if (value == null) continue;
            if (value.contains(marker)) return value;
            if (value.startsWith("ERROR:") || value.startsWith("CLOSED:")) {
                throw new IllegalStateException("Expected " + marker + ", received " + value);
            }
        }
        throw new IllegalStateException("Timed out waiting for " + marker);
    }

    static WebSocket connect(HttpClient client, String ticket, Probe probe) throws Exception {
        return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .header("Origin", "http://localhost:3000")
                .subprotocols("orbit-feed", "ticket." + ticket)
                .buildAsync(URI.create("ws://localhost:8080/ws/feed"), probe)
                .get(10, TimeUnit.SECONDS);
    }

    static int rest(HttpClient client, String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:8080" + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body != null
                        ? HttpRequest.BodyPublishers.ofString(body)
                        : HttpRequest.BodyPublishers.noBody());
        if (body != null) builder.header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("postId, C token, C id, B ticket, D ticket required");
        String postId = args[0];
        String cToken = args[1];
        String cId = args[2];
        String bTicket = args[3];
        String dTicket = args[4];
        try (HttpClient client = HttpClient.newHttpClient()) {
            Probe b = new Probe();
            Probe d = new Probe();
            WebSocket socketB = connect(client, bTicket, b);
            WebSocket socketD = connect(client, dTicket, d);
            waitFor(b, "\"type\":\"ready\"");
            waitFor(d, "\"type\":\"ready\"");
            System.out.println("PASS feed socket ready for follower and outsider");

            Probe replay = new Probe();
            WebSocket replayedTicket = connect(client, bTicket, replay);
            String replayRejection = replay.events.poll(5, TimeUnit.SECONDS);
            if (!"CLOSED:1008".equals(replayRejection)) {
                throw new IllegalStateException("Single-use feed ticket accepted twice: " + replayRejection);
            }
            replayedTicket.abort();
            System.out.println("PASS used feed ticket cannot be replayed");

            int like = rest(client, "POST", "/api/posts/" + postId + "/like", cToken, null);
            if (like != 204) throw new IllegalStateException("Like failed with status " + like);
            String likeEvent = waitFor(b, "\"type\":\"like-changed\"");
            if (!likeEvent.contains("\"postId\":\"" + postId + "\"")
                    || !likeEvent.contains("\"actorId\":\"" + cId + "\"")
                    || !likeEvent.contains("\"likeCount\":1")
                    || !likeEvent.contains("\"liked\":true")) {
                throw new IllegalStateException("Unexpected like-changed payload: " + likeEvent);
            }
            System.out.println("PASS follower sees like-changed (likeCount=1, liked=true, actor=C)");

            int relike = rest(client, "POST", "/api/posts/" + postId + "/like", cToken, null);
            if (relike != 204) throw new IllegalStateException("Re-like failed with status " + relike);
            String duplicate = b.events.poll(2, TimeUnit.SECONDS);
            if (duplicate != null) throw new IllegalStateException("Duplicate like rebroadcast: " + duplicate);
            System.out.println("PASS idempotent duplicate like is not rebroadcast");

            String commentText = "fase feed comentario en vivo";
            int comment = rest(client, "POST", "/api/posts/" + postId + "/comments", cToken,
                    "{\"text\":\"" + commentText + "\"}");
            if (comment != 201) throw new IllegalStateException("Comment failed with status " + comment);
            String commentEvent = waitFor(b, "\"type\":\"comment-created\"");
            if (!commentEvent.contains("\"postId\":\"" + postId + "\"")
                    || !commentEvent.contains("\"commentCount\":1")
                    || !commentEvent.contains("\"authorId\":\"" + cId + "\"")
                    || !commentEvent.contains(commentText)) {
                throw new IllegalStateException("Unexpected comment-created payload: " + commentEvent);
            }
            System.out.println("PASS follower sees comment-created with the comment payload");

            String outsiderEvent = d.events.poll(1, TimeUnit.SECONDS);
            if (outsiderEvent != null) {
                throw new IllegalStateException("Outsider received feed events: " + outsiderEvent);
            }
            System.out.println("PASS outsider receives no like/comment events (visibility pruning)");

            socketB.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            socketD.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        }
    }
}