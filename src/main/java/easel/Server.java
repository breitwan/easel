package easel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Server implements AutoCloseable {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 8765;
    public static final long EVAL_TIMEOUT_SEC = 15;

    static final String INSTRUCTIONS = """
            You are talking to a live Paper server through one tool: eval(code).
            eval runs Java in a persistent JShell in the server JVM. Variables survive.
            Look up Paper/Bukkit javadoc yourself.

            Nothing is cleaned for you. Before you change a mechanic, decide:
            - replace or add?
            - unregister previous Listeners / cancel tasks?
            - remove mobs you spawned?
            - revert blocks?

            Keep references if you will undo:

            Listener wave = new Listener() { ... };
            Bukkit.getPluginManager().registerEvents(wave, Bukkit.getPluginManager().getPlugin("easel"));
            BukkitTask tick = Bukkit.getScheduler().runTaskTimer(...);

            Later:
            HandlerList.unregisterAll(wave);
            tick.cancel();
            entity.remove();

            Do not register a second copy of the same listener "to update it".
            Do not block the main thread. Do not call cancelTasks on the easel plugin.
            """;

    private static final String EVAL_DESCRIPTION = """
            Evaluate Java in a persistent JShell in the Paper server JVM. Variables survive across calls. \
            Runs on the Minecraft main thread (wait ~15s). Single argument: code.

            """ + INSTRUCTIONS;

    private static final int MAX_BODY = 1_000_000;
    private static final Set<String> PROTOCOL_VERSIONS = Set.of(
            "2025-03-26",
            "2025-06-18",
            "2025-11-25"
    );
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final HttpServer http;
    private final ExecutorService executor;
    private final Eval.Engine engine;
    private final Logger log;

    public Server(InetSocketAddress bind, Eval.Engine engine, Logger log) throws IOException {
        this.engine = engine;
        this.log = log;
        this.executor = Executors.newCachedThreadPool(new Threads());
        this.http = HttpServer.create(bind, 0);
        http.setExecutor(executor);
        http.createContext("/mcp", this::mcp);
        http.createContext("/eval", this::eval);
        http.createContext("/health", this::health);
        http.start();
    }

    public InetSocketAddress address() {
        return http.getAddress();
    }

    private void mcp(HttpExchange ex) throws IOException {
        try {
            if (!local(ex)) {
                send(ex, 403, "application/json", utf8(error(null, -32600, "forbidden origin or host").toString()));
                return;
            }
            String method = ex.getRequestMethod();
            if ("GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
                ex.getResponseHeaders().set("Allow", "POST");
                send(ex, 405, "text/plain", new byte[0]);
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                send(ex, 405, "text/plain", new byte[0]);
                return;
            }
            byte[] raw = readBody(ex);
            if (raw == null) {
                return;
            }
            String text = new String(raw, StandardCharsets.UTF_8).strip();
            JsonElement root;
            try {
                root = JsonParser.parseString(text);
            } catch (JsonSyntaxException e) {
                sendJson(ex, 400, error(null, -32700, "parse error"));
                return;
            }
            if (root.isJsonArray()) {
                JsonArray out = new JsonArray();
                for (JsonElement el : root.getAsJsonArray()) {
                    JsonObject resp = handle(el);
                    if (resp != null) {
                        out.add(resp);
                    }
                }
                if (out.isEmpty()) {
                    send(ex, 202, "application/json", new byte[0]);
                } else {
                    sendJson(ex, 200, out);
                }
                return;
            }
            JsonObject resp = handle(root);
            if (resp == null) {
                send(ex, 202, "application/json", new byte[0]);
            } else {
                Headers headers = ex.getResponseHeaders();
                if (resp.has("result")
                        && resp.get("result").isJsonObject()
                        && resp.getAsJsonObject("result").has("protocolVersion")) {
                    headers.set("Mcp-Session-Id", UUID.randomUUID().toString());
                }
                sendJson(ex, 200, resp);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "easel /mcp", e);
            sendJson(ex, 500, error(null, -32603, "internal error"));
        }
    }

    private JsonObject handle(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return error(null, -32600, "invalid request");
        }
        JsonObject req = el.getAsJsonObject();
        JsonElement id = req.get("id");
        boolean notification = id == null;
        String method = req.has("method") && req.get("method").isJsonPrimitive()
                ? req.get("method").getAsString()
                : null;
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params")
                : new JsonObject();
        if (method == null || method.isBlank()) {
            return notification ? null : error(id, -32600, "invalid request");
        }
        return switch (method) {
            case "initialize" -> notification ? null : initialize(id, params);
            case "notifications/initialized", "initialized" -> null;
            case "ping" -> notification ? null : result(id, new JsonObject());
            case "tools/list" -> notification ? null : toolsList(id);
            case "tools/call" -> notification ? null : toolsCall(id, params);
            default -> notification ? null : error(id, -32601, "method not found: " + method);
        };
    }

    private JsonObject initialize(JsonElement id, JsonObject params) {
        String requested = "";
        if (params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()) {
            requested = params.get("protocolVersion").getAsString();
        }
        String version = PROTOCOL_VERSIONS.contains(requested) ? requested : "2025-03-26";
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        JsonObject info = new JsonObject();
        info.addProperty("name", "easel");
        info.addProperty("version", "0.1.0");
        JsonObject body = new JsonObject();
        body.addProperty("protocolVersion", version);
        body.add("capabilities", caps);
        body.add("serverInfo", info);
        body.addProperty("instructions", INSTRUCTIONS);
        return result(id, body);
    }

    private JsonObject toolsList(JsonElement id) {
        JsonObject code = new JsonObject();
        code.addProperty("type", "string");
        code.addProperty("description", "Java source to run in the persistent JShell");
        JsonObject props = new JsonObject();
        props.add("code", code);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("code");
        schema.add("required", required);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", "eval");
        tool.addProperty("description", EVAL_DESCRIPTION);
        tool.add("inputSchema", schema);
        JsonArray tools = new JsonArray();
        tools.add(tool);
        JsonObject body = new JsonObject();
        body.add("tools", tools);
        return result(id, body);
    }

    private JsonObject toolsCall(JsonElement id, JsonObject params) {
        String name = params.has("name") && params.get("name").isJsonPrimitive()
                ? params.get("name").getAsString()
                : "";
        if (!"eval".equals(name)) {
            return error(id, -32602, "unknown tool: " + name);
        }
        JsonObject args = new JsonObject();
        if (params.has("arguments") && params.get("arguments").isJsonObject()) {
            args = params.getAsJsonObject("arguments");
        }
        if (!args.has("code") || !args.get("code").isJsonPrimitive()) {
            return error(id, -32602, "missing code");
        }
        String code = args.get("code").getAsString();
        Eval.Result evalResult;
        try {
            evalResult = engine.eval(code);
        } catch (Exception e) {
            log.log(Level.WARNING, "easel eval", e);
            evalResult = Eval.Result.error(e);
        }
        JsonObject payload = toJson(evalResult);
        JsonObject contentItem = new JsonObject();
        contentItem.addProperty("type", "text");
        contentItem.addProperty("text", GSON.toJson(payload));
        JsonArray content = new JsonArray();
        content.add(contentItem);
        JsonObject body = new JsonObject();
        body.add("content", content);
        body.add("structuredContent", payload);
        body.addProperty("isError", evalResult.failed());
        return result(id, body);
    }

    private void eval(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", new byte[0]);
                return;
            }
            byte[] raw = readBody(ex);
            if (raw == null) {
                return;
            }
            String text = new String(raw, StandardCharsets.UTF_8).strip();
            String code;
            try {
                JsonObject body = JsonParser.parseString(text).getAsJsonObject();
                if (!body.has("code") || !body.get("code").isJsonPrimitive()) {
                    sendJson(ex, 400, debugError("missing code"));
                    return;
                }
                code = body.get("code").getAsString();
            } catch (Exception e) {
                sendJson(ex, 400, debugError("expected JSON {\"code\": \"...\"}"));
                return;
            }
            Eval.Result result;
            try {
                result = engine.eval(code);
            } catch (Exception e) {
                log.log(Level.WARNING, "easel /eval", e);
                result = Eval.Result.error(e);
            }
            sendJson(ex, 200, toJson(result));
        } catch (Exception e) {
            log.log(Level.WARNING, "easel /eval", e);
            sendJson(ex, 500, debugError("internal error"));
        }
    }

    private void health(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())
                && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", new byte[0]);
            return;
        }
        JsonObject ok = new JsonObject();
        ok.addProperty("ok", true);
        sendJson(ex, 200, ok);
    }

    static JsonObject toJson(Eval.Result result) {
        JsonObject o = new JsonObject();
        if (result.value() == null) {
            o.add("value", JsonNull.INSTANCE);
        } else {
            o.addProperty("value", result.value());
        }
        JsonArray diags = new JsonArray();
        for (String d : result.diagnostics()) {
            diags.add(d);
        }
        o.add("diagnostics", diags);
        if (result.exception() == null) {
            o.add("exception", JsonNull.INSTANCE);
        } else {
            o.addProperty("exception", result.exception());
        }
        o.addProperty("stdout", result.stdout());
        return o;
    }

    private static JsonObject result(JsonElement id, JsonElement value) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", copyId(id));
        o.add("result", value);
        return o;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", copyId(id));
        o.add("error", err);
        return o;
    }

    private static JsonObject debugError(String message) {
        JsonObject o = new JsonObject();
        o.add("value", JsonNull.INSTANCE);
        o.add("diagnostics", new JsonArray());
        o.addProperty("exception", message);
        o.addProperty("stdout", "");
        return o;
    }

    private static JsonElement copyId(JsonElement id) {
        if (id == null || id.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (id.isJsonPrimitive()) {
            JsonPrimitive p = id.getAsJsonPrimitive();
            if (p.isNumber() || p.isString()) {
                return p;
            }
        }
        return JsonNull.INSTANCE;
    }

    private byte[] readBody(HttpExchange ex) throws IOException {
        Headers headers = ex.getRequestHeaders();
        List<String> lens = headers.get("Content-Length");
        if (lens != null && !lens.isEmpty()) {
            try {
                long n = Long.parseLong(lens.getFirst());
                if (n > MAX_BODY) {
                    send(ex, 413, "text/plain", utf8("payload too large"));
                    return null;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = in.readNBytes(MAX_BODY + 1);
            if (buf.length > MAX_BODY) {
                send(ex, 413, "text/plain", utf8("payload too large"));
                return null;
            }
            return buf;
        }
    }

    private static boolean local(HttpExchange ex) {
        Headers headers = ex.getRequestHeaders();
        String host = first(headers, "Host");
        if (host != null && !host.isBlank() && !localHost(host)) {
            return false;
        }
        String origin = first(headers, "Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            String h = uri.getHost();
            return h != null && localHost(h);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean localHost(String hostPort) {
        String host = hostPort;
        if (host.startsWith("[") && host.contains("]")) {
            host = host.substring(1, host.indexOf(']'));
        } else {
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon) {
                host = host.substring(0, colon);
            }
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1");
    }

    private static String first(Headers headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.getFirst();
    }

    private static void sendJson(HttpExchange ex, int status, JsonElement body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", utf8(GSON.toJson(body)));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Cache-Control", "no-store");
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        http.stop(0);
        executor.shutdownNow();
    }

    private static final class Threads implements ThreadFactory {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread t = new Thread(r, "easel-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
