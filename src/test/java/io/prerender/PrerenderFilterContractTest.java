package io.prerender;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Contract tests against the shared mock server.
 * Spec: https://github.com/prerender/integration-contract
 *
 * CI fetches mock-server.mjs into the repo root; locally:
 *   curl -fsSL -o mock-server.mjs https://raw.githubusercontent.com/prerender/integration-contract/main/mock-server.mjs
 */
@ExtendWith(MockitoExtension.class)
class PrerenderFilterContractTest {

    private static final String BOT_UA = "Mozilla/5.0 (compatible; Googlebot/2.1)";
    private static final String BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final String TOKEN = "test-token-abc123";
    private static final Pattern UUID_V4 = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        Pattern.CASE_INSENSITIVE
    );

    private static Process mockProcess;
    private static String mockUrl;
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    private StringWriter responseWriter;
    private PrerenderFilter filter;

    @BeforeAll
    static void startMock() throws Exception {
        Path mockPath = Paths.get(System.getProperty(
            "mockServerPath",
            System.getenv().getOrDefault("MOCK_SERVER_PATH", "mock-server.mjs")
        ));
        if (!Files.exists(mockPath)) {
            throw new IllegalStateException(
                "mock-server.mjs not found at " + mockPath.toAbsolutePath()
                + "; fetch it via curl from prerender/integration-contract"
            );
        }
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        ProcessBuilder pb = new ProcessBuilder("node", mockPath.toString())
            .redirectErrorStream(true);
        pb.environment().put("PORT", String.valueOf(port));
        mockProcess = pb.start();
        mockUrl = "http://127.0.0.1:" + port;
        waitForHealth();
    }

    @AfterAll
    static void stopMock() {
        if (mockProcess != null) mockProcess.destroy();
    }

    @BeforeEach
    void resetAndBuildFilter() throws Exception {
        httpClient.send(
            HttpRequest.newBuilder(URI.create(mockUrl + "/__reset")).POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding()
        );
        responseWriter = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    private void useToken(String token) {
        filter = new PrerenderFilter(HttpClient.newHttpClient(), new PrerenderConfig(token, mockUrl));
    }

    private static void waitForHealth() throws Exception {
        for (int i = 0; i < 50; i++) {
            try {
                HttpResponse<String> r = httpClient.send(
                    HttpRequest.newBuilder(URI.create(mockUrl + "/__health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                );
                if (r.statusCode() == 200) return;
            } catch (Exception ignored) {}
            Thread.sleep(100);
        }
        throw new IllegalStateException("mock server at " + mockUrl + " did not become ready");
    }

    private JsonNode recordedRequests() throws Exception {
        HttpResponse<String> r = httpClient.send(
            HttpRequest.newBuilder(URI.create(mockUrl + "/__requests")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        return mapper.readTree(r.body());
    }

    private void stubBotRequest(String uri) {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getParameter("_escaped_fragment_")).thenReturn(null);
        when(request.getHeader("X-Bufferbot")).thenReturn(null);
        when(request.getHeader("User-Agent")).thenReturn(BOT_UA);
        when(request.getRequestURL()).thenReturn(new StringBuffer("http://example.com" + uri));
        when(request.getQueryString()).thenReturn(null);
    }

    @Test
    void botRequest_emitsOutgoingRequestWithRequiredHeaders() throws Exception {
        useToken(TOKEN);
        stubBotRequest("/blog/post-1");

        filter.doFilter(request, response, chain);

        JsonNode recorded = recordedRequests();
        assertEquals(1, recorded.size(), "exactly one request should reach the mock");
        JsonNode r = recorded.get(0);
        assertEquals("GET", r.get("method").asText());
        assertTrue(r.get("url").asText().endsWith("/blog/post-1"));
        JsonNode headers = r.get("headers");
        assertEquals(BOT_UA, headers.get("user-agent").asText());
        assertEquals(TOKEN, headers.get("x-prerender-token").asText());
        assertEquals("Java", headers.get("x-prerender-int-type").asText());
        assertTrue(
            headers.get("x-prerender-int-version").asText().matches("^\\d+\\.\\d+\\.\\d+.*"),
            "Int-Version should be semver"
        );
        assertTrue(
            UUID_V4.matcher(headers.get("x-prerender-request-id").asText()).matches(),
            "Request-Id should be a UUID v4"
        );
    }

    @Test
    void browserRequest_emitsNoOutgoingRequest() throws Exception {
        useToken(TOKEN);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/");
        when(request.getParameter("_escaped_fragment_")).thenReturn(null);
        when(request.getHeader("X-Bufferbot")).thenReturn(null);
        when(request.getHeader("User-Agent")).thenReturn(BROWSER_UA);

        filter.doFilter(request, response, chain);

        assertEquals(0, recordedRequests().size());
    }

    @Test
    void staticAsset_emitsNoOutgoingRequest() throws Exception {
        useToken(TOKEN);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/styles.css");

        filter.doFilter(request, response, chain);

        assertEquals(0, recordedRequests().size());
    }

    @Test
    void tokenOmitted_whenUnconfigured() throws Exception {
        useToken(null);
        stubBotRequest("/");

        filter.doFilter(request, response, chain);

        JsonNode headers = recordedRequests().get(0).get("headers");
        assertFalse(headers.has("x-prerender-token"), "X-Prerender-Token must not be sent when unconfigured");
    }

    @Test
    void requestId_isUniquePerOutgoingRequest() throws Exception {
        useToken(TOKEN);
        stubBotRequest("/");

        filter.doFilter(request, response, chain);
        filter.doFilter(request, response, chain);

        JsonNode recorded = recordedRequests();
        assertEquals(2, recorded.size());
        assertNotEquals(
            recorded.get(0).get("headers").get("x-prerender-request-id").asText(),
            recorded.get(1).get("headers").get("x-prerender-request-id").asText()
        );
    }
}
