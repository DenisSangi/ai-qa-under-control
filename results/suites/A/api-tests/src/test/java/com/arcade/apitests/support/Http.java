package com.arcade.apitests.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thin thread-safe HTTP client; every response remembers its request for readable failure messages. */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private Http() {
    }

    public static Resp get(String path) {
        return send("GET", path, null, Map.of());
    }

    public static Resp post(String path, String json) {
        return send("POST", path, json, Map.of());
    }

    public static Resp post(String path, String json, Map<String, String> headers) {
        return send("POST", path, json, headers);
    }

    public static Resp put(String path, String json) {
        return send("PUT", path, json, Map.of());
    }

    public static Resp delete(String path) {
        return send("DELETE", path, null, Map.of());
    }

    /** {@code path} is relative to the API base URL unless it is absolute. */
    public static Resp send(String method, String path, String body, Map<String, String> headers) {
        String url = path.startsWith("http") ? path : Env.API + path;
        Map<String, String> all = new LinkedHashMap<>();
        if (body != null) {
            all.put("Content-Type", "application/json");
        }
        all.putAll(headers);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        all.forEach(builder::header);
        String described = method + " " + url + " " + all + (body == null ? "" : " " + body);
        try {
            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(response.statusCode(), response.headers().map(), response.body(), described);
        } catch (IOException e) {
            throw new UncheckedIOException("request failed: " + described, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted: " + described, e);
        }
    }

    /**
     * Sends the request over a plain socket with header values written byte for byte as ISO-8859-1.
     * The JDK client replaces non-ASCII header characters with '?', which would hide what a server
     * does with real non-ASCII bytes.
     */
    public static Resp raw(String method, String path, String body, Map<String, String> headers) {
        URI uri = URI.create(Env.API + path);
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        byte[] content = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder()
                .append(method).append(' ').append(uri.getRawPath()).append(" HTTP/1.1\r\n")
                .append("Host: ").append(uri.getHost()).append(':').append(port).append("\r\n");
        headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        if (body != null) {
            head.append("Content-Type: application/json\r\n");
        }
        head.append("Content-Length: ").append(content.length).append("\r\nConnection: close\r\n\r\n");
        String described = "RAW " + method + " " + uri + " " + headers + (body == null ? "" : " " + body);
        try (Socket socket = new Socket(uri.getHost(), port)) {
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(content);
            out.flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = Integer.parseInt(response.substring(9, 12));
            String rest = response.substring(response.indexOf("\r\n\r\n") + 4);
            // the body may be chunked: keep the JSON document inside
            int start = rest.indexOf('{');
            int end = rest.lastIndexOf('}');
            String json = start >= 0 && end > start ? rest.substring(start, end + 1) : rest;
            return new Resp(status, Map.of(), json, described);
        } catch (IOException e) {
            throw new UncheckedIOException("request failed: " + described, e);
        }
    }

    public record Resp(int status, Map<String, java.util.List<String>> headers, String body, String request) {

        public JsonNode json() {
            return Json.parse(body);
        }

        public String code() {
            return body == null || body.isBlank() ? null : Json.text(json(), "code");
        }

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream())
                    .findFirst().orElse(null);
        }

        @Override
        public String toString() {
            return "\n  request:  " + request + "\n  response: " + status + " " + body;
        }
    }
}
