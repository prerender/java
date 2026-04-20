package io.prerender;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PrerenderFilterTest {

    private static final String BOT_UA = "Mozilla/5.0 (compatible; Googlebot/2.1)";
    private static final String BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final String PRERENDERED_HTML = "<html><body>prerendered</body></html>";

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    private StringWriter responseWriter;
    private PrerenderFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        wireMock.resetAll();
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
        PrerenderConfig config = new PrerenderConfig(null, "http://localhost:" + wireMock.getPort());
        filter = new PrerenderFilter(HttpClient.newHttpClient(), config);
    }

    @Test
    void browserRequest_passesThrough() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/");
        when(request.getParameter("_escaped_fragment_")).thenReturn(null);
        when(request.getHeader("X-Bufferbot")).thenReturn(null);
        when(request.getHeader("User-Agent")).thenReturn(BROWSER_UA);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void botRequest_receivesPrerenderedResponse() throws Exception {
        wireMock.stubFor(get(anyUrl())
            .willReturn(aResponse().withStatus(200).withBody(PRERENDERED_HTML)));

        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/");
        when(request.getParameter("_escaped_fragment_")).thenReturn(null);
        when(request.getHeader("X-Bufferbot")).thenReturn(null);
        when(request.getHeader("User-Agent")).thenReturn(BOT_UA);
        when(request.getRequestURL()).thenReturn(new StringBuffer("http://example.com/"));
        when(request.getQueryString()).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(response).setStatus(200);
        verify(chain, never()).doFilter(any(), any());
        assertEquals(PRERENDERED_HTML, responseWriter.toString());
    }

    @Test
    void botRequest_staticAsset_passesThrough() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/styles.css");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void escapedFragment_triggersPrerender() throws Exception {
        wireMock.stubFor(get(anyUrl())
            .willReturn(aResponse().withStatus(200).withBody(PRERENDERED_HTML)));

        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/");
        when(request.getParameter("_escaped_fragment_")).thenReturn("");
        when(request.getHeader("User-Agent")).thenReturn(BROWSER_UA);
        when(request.getRequestURL()).thenReturn(new StringBuffer("http://example.com/"));
        when(request.getQueryString()).thenReturn("_escaped_fragment_=");

        filter.doFilter(request, response, chain);

        verify(response).setStatus(200);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void xBufferbot_triggersPrerender() throws Exception {
        wireMock.stubFor(get(anyUrl())
            .willReturn(aResponse().withStatus(200).withBody(PRERENDERED_HTML)));

        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/");
        when(request.getParameter("_escaped_fragment_")).thenReturn(null);
        when(request.getHeader("X-Bufferbot")).thenReturn("true");
        when(request.getHeader("User-Agent")).thenReturn(BROWSER_UA);
        when(request.getRequestURL()).thenReturn(new StringBuffer("http://example.com/"));
        when(request.getQueryString()).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(response).setStatus(200);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void postRequest_passesThrough() throws Exception {
        when(request.getMethod()).thenReturn("POST");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void networkError_fallsBackToNormalResponse() throws Exception {
        wireMock.stubFor(get(anyUrl())
            .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/");
        when(request.getParameter("_escaped_fragment_")).thenReturn(null);
        when(request.getHeader("X-Bufferbot")).thenReturn(null);
        when(request.getHeader("User-Agent")).thenReturn(BOT_UA);
        when(request.getRequestURL()).thenReturn(new StringBuffer("http://example.com/"));
        when(request.getQueryString()).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
