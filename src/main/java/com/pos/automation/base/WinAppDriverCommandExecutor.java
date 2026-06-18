package com.pos.automation.base;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.appium.java_client.remote.AppiumCommandExecutor;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.remote.*;
import org.openqa.selenium.remote.codec.w3c.W3CHttpCommandCodec;
import org.openqa.selenium.remote.codec.w3c.W3CHttpResponseCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Sends WinAppDriver session creation as a raw JWP POST containing only {@code desiredCapabilities}.
 *
 * The compatibility gap between Selenium 4 and WinAppDriver 1.x:
 *
 *   Problem A — Selenium 4.x {@code NewSessionPayload.validate()} rejects bare capability names
 *     like {@code app} and {@code deviceName}: not W3C standard and no vendor prefix.
 *
 *   Problem B — Appium Java Client's {@code AppiumCommandExecutor} adds the {@code appium:} prefix
 *     to all caps and sends a dual-format body (W3C + JWP). WinAppDriver 1.x reads the W3C section
 *     first, finds {@code appium:app} but not plain {@code app}, and returns HTTP 400.
 *
 *   Problem C — Selenium 4.13+ changed {@code startSession} to pass capabilities under a
 *     non-standard internal key, making extraction from command parameters unreliable.
 *
 * This executor bypasses all three by accepting the capabilities at construction time and firing
 * a raw HTTP POST with ONLY {@code {"desiredCapabilities": {...}}} — no W3C section. WinAppDriver
 * finds plain {@code app} where it expects it and creates the session. {@code AppiumCommandExecutor}'s
 * public {@code setCommandCodec()} / {@code setResponseCodec()} wire W3C codecs for subsequent
 * commands (WinAppDriver 1.2.1 understands W3C command format once a session is open).
 */
public class WinAppDriverCommandExecutor extends AppiumCommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(WinAppDriverCommandExecutor.class);
    private static final Gson GSON = new Gson();

    // Shared across all sessions — avoids allocating a new thread pool on every NEW_SESSION call.
    private static final java.net.http.HttpClient HTTP_CLIENT = java.net.http.HttpClient.newHttpClient();

    // WinAppDriver 1.x returns JSONWP element refs {"ELEMENT":"id"}.
    // Selenium 4's W3C codec passes them through as plain Maps — casting to WebElement then fails.
    // We rewrite the ref key to W3C format so ElementLocation can deserialise them correctly.
    private static final String W3C_ELEMENT_KEY = "element-6066-11e4-a52e-4f735466cecf";

    private final String baseUrl;
    private final Capabilities sessionCaps;

    public WinAppDriverCommandExecutor(URL url, Capabilities caps) {
        super(Collections.emptyMap(), url);
        this.baseUrl = url.toString().replaceAll("/$", "");
        this.sessionCaps = caps;
    }

    @Override
    public Response execute(Command command) throws WebDriverException {
        if (DriverCommand.NEW_SESSION.equals(command.getName())) {
            try {
                return createJwpSession();
            } catch (IOException e) {
                throw new WebDriverException("WinAppDriver session creation failed: " + e.getMessage(), e);
            }
        }
        // W3C codec encodes switchToWindow as {handle: "..."} but WinAppDriver 1.x
        // uses JWP format and expects {name: "..."}.
        if (DriverCommand.SWITCH_TO_WINDOW.equals(command.getName())) {
            Object handle = command.getParameters().get("handle");
            if (handle != null) {
                command = new Command(command.getSessionId(), DriverCommand.SWITCH_TO_WINDOW,
                        Map.of("name", handle));
            }
        }
        Response resp = super.execute(command);

        // Convert JSONWP element refs {"ELEMENT":"id"} to W3C format so ElementLocation
        // can deserialise them instead of throwing ClassCastException.
        if (DriverCommand.FIND_ELEMENTS.equals(command.getName())) {
            resp.setValue(fixElementRefsList(resp.getValue()));
        } else if (DriverCommand.FIND_ELEMENT.equals(command.getName())) {
            resp.setValue(fixSingleElementRef(resp.getValue()));
        }

        return resp;
    }

    private static Object fixElementRefsList(Object value) {
        if (!(value instanceof List)) return value;
        return ((List<?>) value).stream()
                .map(WinAppDriverCommandExecutor::fixSingleElementRef)
                .toList();
    }

    private static Object fixSingleElementRef(Object element) {
        if (!(element instanceof Map)) return element;
        Map<?, ?> map = (Map<?, ?>) element;
        if (map.containsKey(W3C_ELEMENT_KEY)) return element;
        Object elementId = map.get("ELEMENT");
        if (elementId == null) return element;
        return Map.of(W3C_ELEMENT_KEY, elementId.toString());
    }

    // -------------------------------------------------------------------------

    private Response createJwpSession() throws IOException {
        String jsonBody = GSON.toJson(Map.of("desiredCapabilities", sessionCaps.asMap()));
        log.info("JWP session payload: {}", jsonBody);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/session"))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> httpResp;
        try {
            httpResp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("WinAppDriver session creation interrupted", e);
        }

        log.info("WinAppDriver response [HTTP {}]: {}", httpResp.statusCode(), httpResp.body());
        return parseAndWireSession(httpResp);
    }

    private Response parseAndWireSession(HttpResponse<String> httpResp) throws IOException {
        JsonObject json = GSON.fromJson(httpResp.body(), JsonObject.class);

        // JWP response: {"status": 0, "sessionId": "...", "value": {...capabilities...}}
        int status = json.has("status") ? json.get("status").getAsInt() : -1;
        JsonElement sidElem = json.get("sessionId");
        String sessionId = (sidElem != null && !sidElem.isJsonNull()) ? sidElem.getAsString() : null;

        if (status != 0 || sessionId == null || sessionId.isBlank()) {
            String msg = extractErrorMessage(json, httpResp.body());
            log.error("WinAppDriver rejected session (HTTP {}, status={}): {}",
                    httpResp.statusCode(), status, msg);
            throw new IOException("WinAppDriver session creation failed: " + msg);
        }

        // AppiumCommandExecutor exposes public setCommandCodec / setResponseCodec.
        // Wire W3C codecs: WinAppDriver 1.2.1 handles W3C command format once a session exists.
        // Without this call the inherited commandCodec/responseCodec fields stay null,
        // causing NPE on the first non-session command.
        setCommandCodec(new W3CHttpCommandCodec());
        setResponseCodec(new W3CHttpResponseCodec());

        log.info("WinAppDriver JWP session started: {}", sessionId);

        Response resp = new Response(new SessionId(sessionId));
        resp.setSessionId(sessionId);
        if (json.has("value") && json.get("value").isJsonObject()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> capMap = GSON.fromJson(json.get("value"), Map.class);
            resp.setValue(capMap);
        } else {
            resp.setValue(Collections.emptyMap());
        }
        return resp;
    }

    private static String extractErrorMessage(JsonObject json, String rawBody) {
        try {
            if (json.has("value") && json.get("value").isJsonObject()) {
                JsonObject val = json.getAsJsonObject("value");
                if (val.has("message")) return val.get("message").getAsString();
            }
        } catch (Exception ignored) { /* fall through */ }
        return rawBody;
    }
}
