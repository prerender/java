package io.prerender;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PrerenderFilter implements Filter {

    private static final Logger logger = Logger.getLogger(PrerenderFilter.class.getName());

    private HttpClient httpClient;
    private PrerenderConfig config;

    public PrerenderFilter() {}

    PrerenderFilter(HttpClient httpClient, PrerenderConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    @Override
    public void init(FilterConfig filterConfig) {
        this.httpClient = HttpClient.newHttpClient();
        this.config = PrerenderConfig.fromInitParams(
            filterConfig.getInitParameter("prerenderToken"),
            filterConfig.getInitParameter("prerenderServiceUrl")
        );
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;

        if (!shouldPrerender(httpReq)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            sendPrerendered(httpReq, httpRes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            chain.doFilter(request, response);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Prerender service unreachable, falling back", e);
            chain.doFilter(request, response);
        }
    }

    @Override
    public void destroy() {}

    private boolean shouldPrerender(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        if (PrerenderConfig.isStaticAsset(request.getRequestURI())) return false;
        if (request.getParameter("_escaped_fragment_") != null) return true;
        if (request.getHeader("X-Bufferbot") != null) return true;
        String ua = request.getHeader("User-Agent");
        return ua != null && !ua.isBlank() && PrerenderConfig.isBot(ua);
    }

    private void sendPrerendered(HttpServletRequest request, HttpServletResponse response)
            throws IOException, InterruptedException {
        HttpResponse<String> prerenderResponse = httpClient.send(
            buildPrerenderRequest(buildApiUrl(request), request.getHeader("User-Agent")),
            HttpResponse.BodyHandlers.ofString()
        );
        response.setStatus(prerenderResponse.statusCode());
        response.getWriter().write(prerenderResponse.body());
    }

    private String buildApiUrl(HttpServletRequest request) {
        String serviceUrl = config.getServiceUrl();
        if (!serviceUrl.endsWith("/")) serviceUrl += "/";
        String url = request.getRequestURL().toString();
        String qs = request.getQueryString();
        return serviceUrl + (qs != null && !qs.isBlank() ? url + "?" + qs : url);
    }

    private HttpRequest buildPrerenderRequest(String apiUrl, String userAgent) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("User-Agent", userAgent != null ? userAgent : "")
            .GET();
        if (config.getToken() != null && !config.getToken().isBlank()) {
            builder.header("X-Prerender-Token", config.getToken());
        }
        return builder.build();
    }
}
