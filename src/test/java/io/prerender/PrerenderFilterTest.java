package io.prerender;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives the filter inside a real servlet container (embedded Jetty), hit by a real
 * HTTP client. The upstream Prerender service is faked with WireMock. Catches
 * container-level behaviour (filter chain wiring, request URL/query, status and
 * header propagation, static-asset pass-through) that Mockito on servlet objects
 * would miss.
 */
class PrerenderFilterTest {

    private static final String BOT_UA = "Mozilla/5.0 (compatible; Googlebot/2.1)";
    private static final String BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final String PRERENDERED_HTML = "<html><body>prerendered</body></html>";
    private static final String ORIGINAL = "original";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private static Server jetty;
    private static String baseUrl;
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeAll
    static void startJetty() throws Exception {
        jetty = new Server(0);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        FilterHolder filter = new FilterHolder(new PrerenderFilter());
        filter.setInitParameter("prerenderToken", "test-token");
        filter.setInitParameter("prerenderServiceUrl", wireMock.baseUrl());
        context.addFilter(filter, "/*", EnumSet.of(DispatcherType.REQUEST));
        context.addServlet(new ServletHolder(new OriginalServlet()), "/*");

        jetty.setHandler(context);
        jetty.start();
        int port = ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @AfterAll
    static void stopJetty() throws Exception {
        if (jetty != null) jetty.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    private HttpResponse<String> send(String method, String path, String userAgent, String... extraHeaders) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("User-Agent", userAgent);
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            b.header(extraHeaders[i], extraHeaders[i + 1]);
        }
        HttpRequest req = "POST".equals(method)
            ? b.POST(HttpRequest.BodyPublishers.noBody()).build()
            : b.GET().build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void browserRequest_passesThrough() throws Exception {
        HttpResponse<String> res = send("GET", "/", BROWSER_UA);

        assertEquals(200, res.statusCode());
        assertEquals(ORIGINAL, res.body());
    }

    @Test
    void botRequest_receivesPrerenderedResponse() throws Exception {
        wireMock.stubFor(get(anyUrl())
            .willReturn(aResponse().withStatus(200).withBody(PRERENDERED_HTML)));

        HttpResponse<String> res = send("GET", "/about", BOT_UA);

        assertEquals(200, res.statusCode());
        assertEquals(PRERENDERED_HTML, res.body());
    }

    @Test
    void botRequest_staticAsset_passesThrough() throws Exception {
        HttpResponse<String> res = send("GET", "/styles.css", BOT_UA);

        assertEquals(200, res.statusCode());
        assertEquals(ORIGINAL, res.body());
    }

    @Test
    void escapedFragment_triggersPrerender() throws Exception {
        wireMock.stubFor(get(anyUrl())
            .willReturn(aResponse().withStatus(200).withBody(PRERENDERED_HTML)));

        HttpResponse<String> res = send("GET", "/?_escaped_fragment_=", BROWSER_UA);

        assertEquals(200, res.statusCode());
        assertEquals(PRERENDERED_HTML, res.body());
    }

    @Test
    void xBufferbot_triggersPrerender() throws Exception {
        wireMock.stubFor(get(anyUrl())
            .willReturn(aResponse().withStatus(200).withBody(PRERENDERED_HTML)));

        HttpResponse<String> res = send("GET", "/", BROWSER_UA, "X-Bufferbot", "true");

        assertEquals(200, res.statusCode());
        assertEquals(PRERENDERED_HTML, res.body());
    }

    @Test
    void postRequest_passesThrough() throws Exception {
        HttpResponse<String> res = send("POST", "/", BOT_UA);

        assertEquals(200, res.statusCode());
        assertEquals(ORIGINAL, res.body());
    }

    @Test
    void networkError_fallsBackToNormalResponse() throws Exception {
        wireMock.stubFor(get(anyUrl())
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> res = send("GET", "/", BOT_UA);

        assertEquals(200, res.statusCode());
        assertEquals(ORIGINAL, res.body());
    }

    public static class OriginalServlet extends HttpServlet {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setStatus(200);
            resp.setContentType("text/plain");
            resp.getWriter().write(ORIGINAL);
        }
    }
}
