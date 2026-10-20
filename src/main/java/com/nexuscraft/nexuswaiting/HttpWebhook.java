package com.nexuscraft.nexuswaiting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/** Posts to a Discord webhook with the JDK's own HTTP client; never blocks the server thread. */
final class HttpWebhook implements Webhook {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI uri;

    /** @throws IllegalArgumentException when the address is not a usable URI */
    HttpWebhook(String url) {
        this.uri = URI.create(url.trim());
    }

    @Override
    public void post(String json, Consumer<Boolean> done) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> done.accept(error == null && response.statusCode() / 100 == 2));
        } catch (RuntimeException ex) {
            done.accept(false);
        }
    }
}
