package easel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTest {

    private Eval eval;
    private Server server;
    private HttpClient http;
    private URI mcp;
    private URI health;
    private URI debugEval;

    @BeforeEach
    void start() throws Exception {
        eval = new Eval(Eval.class.getClassLoader(), EvalTest.classpath());
        server = new Server(
                new InetSocketAddress("127.0.0.1", 0),
                eval::eval,
                Logger.getLogger("easel-test")
        );
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        URI base = URI.create("http://127.0.0.1:" + server.address().getPort());
        mcp = base.resolve("/mcp");
        health = base.resolve("/health");
        debugEval = base.resolve("/eval");
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        if (eval != null) {
            eval.close();
        }
    }

    @Test
    void bindsLoopbackOnly() {
        assertTrue(server.address().getAddress().isLoopbackAddress());
        assertEquals("127.0.0.1", server.address().getAddress().getHostAddress());
    }

    @Test
    void healthOk() throws Exception {
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(health).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, res.statusCode());
        JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
        assertTrue(body.get("ok").getAsBoolean());
    }

    @Test
    void initializeExposesInstructionsAndOnlyEvalTool() throws Exception {
        JsonObject init = rpc(mcp, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}");
        JsonObject result = init.getAsJsonObject("result");
        assertEquals("easel", result.getAsJsonObject("serverInfo").get("name").getAsString());
        assertTrue(result.get("instructions").getAsString().contains("Nothing is cleaned for you"));
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));

        HttpResponse<String> initialized = http.send(
                post(mcp, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(202, initialized.statusCode());

        JsonObject list = rpc(mcp, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JsonArray tools = list.getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(1, tools.size());
        JsonObject tool = tools.get(0).getAsJsonObject();
        assertEquals("eval", tool.get("name").getAsString());
        assertTrue(tool.get("description").getAsString().contains("HandlerList.unregisterAll"));
        assertTrue(tool.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("code"));
    }

    @Test
    void pingAndUnknownMethod() throws Exception {
        JsonObject pong = rpc(mcp, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}");
        assertTrue(pong.has("result"));
        JsonObject unknown = rpc(mcp, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"nope\"}");
        assertEquals(-32601, unknown.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void toolsCallEval() throws Exception {
        JsonObject call = rpc(mcp, """
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"eval","arguments":{"code":"System.out.print(\\"x\\"); 1+1"}}}
                """);
        JsonObject result = call.getAsJsonObject("result");
        assertFalse(result.get("isError").getAsBoolean());
        JsonObject structured = result.getAsJsonObject("structuredContent");
        assertEquals("2", structured.get("value").getAsString());
        assertEquals("x", structured.get("stdout").getAsString());
        String text = result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("\"value\":\"2\""));
    }

    @Test
    void debugEvalAndPersistenceAcrossHttp() throws Exception {
        JsonObject first = json(http.send(
                post(debugEval, "{\"code\":\"int n = 9;\"}"),
                HttpResponse.BodyHandlers.ofString()
        ));
        assertTrue(first.get("exception").isJsonNull());
        JsonObject second = json(http.send(
                post(debugEval, "{\"code\":\"n\"}"),
                HttpResponse.BodyHandlers.ofString()
        ));
        assertEquals("9", second.get("value").getAsString());
    }

    @Test
    void getMcpIs405() throws Exception {
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(mcp).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(405, res.statusCode());
    }

    private JsonObject rpc(URI uri, String body) throws Exception {
        HttpResponse<String> res = http.send(post(uri, body), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        return json(res);
    }

    private static JsonObject json(HttpResponse<String> res) {
        JsonElement el = JsonParser.parseString(res.body());
        return el.getAsJsonObject();
    }

    private static HttpRequest post(URI uri, String body) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
