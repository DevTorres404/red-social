import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class PhaseEWebSocketSmoke {
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

    static WebSocket connect(HttpClient client, String conversationId, String ticket, Probe probe) throws Exception {
        return client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .header("Origin", "http://localhost:3000")
                .subprotocols("orbit-chat", "ticket." + ticket)
                .buildAsync(URI.create("ws://localhost:8080/ws/chat/" + conversationId), probe)
                .get(10, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("conversation, A ticket, B ticket, A reconnect ticket, C wrong-conversation ticket required");
        String conversationId = args[0];
        try (HttpClient client = HttpClient.newHttpClient()) {
            Probe a = new Probe();
            Probe b = new Probe();
            WebSocket socketA = connect(client, conversationId, args[1], a);
            WebSocket socketB = connect(client, conversationId, args[2], b);
            waitFor(a, "\"type\":\"ready\"");
            waitFor(b, "\"type\":\"ready\"");
            System.out.println("PASS two authorized sockets ready");

            Probe replay = new Probe();
            WebSocket replayedTicket = connect(client, conversationId, args[1], replay);
            String replayRejection = replay.events.poll(5, TimeUnit.SECONDS);
            if (!"CLOSED:1008".equals(replayRejection)) {
                throw new IllegalStateException("Single-use ticket accepted twice: " + replayRejection);
            }
            replayedTicket.abort();
            System.out.println("PASS used ticket cannot be replayed");

            socketA.sendText("{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"text\":\"   \"}", true)
                    .get(5, TimeUnit.SECONDS);
            waitFor(a, "Message must contain 1 to 1000 characters");
            socketA.sendText("{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"text\":\"" + "x".repeat(1001) + "\"}", true)
                    .get(5, TimeUnit.SECONDS);
            waitFor(a, "Message must contain 1 to 1000 characters");
            if (b.events.poll(300, TimeUnit.MILLISECONDS) != null) {
                throw new IllegalStateException("Invalid message was broadcast");
            }
            System.out.println("PASS blank and oversized messages rejected without broadcast");

            String clientId = UUID.randomUUID().toString();
            String payload = "{\"clientMessageId\":\"" + clientId + "\",\"text\":\"phase E hello\"}";
            socketA.sendText(payload, true).get(5, TimeUnit.SECONDS);
            String aEvent = waitFor(a, "phase E hello");
            String bEvent = waitFor(b, "phase E hello");
            if (!aEvent.contains(clientId) || !bEvent.contains(clientId)) throw new IllegalStateException("Event ID mismatch");
            System.out.println("PASS persistent message delivered to both clients");

            socketA.sendText(payload, true).get(5, TimeUnit.SECONDS);
            waitFor(a, "phase E hello");
            if (b.events.poll(1, TimeUnit.SECONDS) != null) throw new IllegalStateException("Duplicate broadcast to recipient");
            System.out.println("PASS retried client ID is not rebroadcast");

            Probe c = new Probe();
            WebSocket socketC = connect(client, conversationId, args[4], c);
            String rejection = c.events.poll(5, TimeUnit.SECONDS);
            if (!"CLOSED:1008".equals(rejection)) throw new IllegalStateException("Wrong-conversation ticket accepted: " + rejection);
            socketC.abort();
            System.out.println("PASS third user blocked from conversation");

            socketA.sendClose(WebSocket.NORMAL_CLOSURE, "leave").get(5, TimeUnit.SECONDS);
            String missedId = UUID.randomUUID().toString();
            socketB.sendText("{\"clientMessageId\":\"" + missedId + "\",\"text\":\"phase E while offline\"}", true)
                    .get(5, TimeUnit.SECONDS);
            waitFor(b, "phase E while offline");
            Probe reconnected = new Probe();
            WebSocket socketA2 = connect(client, conversationId, args[3], reconnected);
            waitFor(reconnected, "\"type\":\"ready\"");
            System.out.println("PASS reconnect with fresh single-use ticket");
            socketA2.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            socketB.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        }
    }
}
