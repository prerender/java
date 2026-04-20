package io.prerender;

import java.util.List;

class PrerenderConfig {

    static final List<String> CRAWLER_USER_AGENTS = List.of(
        "googlebot", "yahoo", "bingbot", "baiduspider",
        "facebookexternalhit", "twitterbot", "rogerbot", "linkedinbot",
        "embedly", "quora link preview", "showyoubot", "outbrain",
        "pinterest", "slackbot", "w3c_validator", "perplexity",
        "oai-searchbot", "chatgpt-user", "gptbot", "claudebot", "amazonbot"
    );

    static final List<String> EXTENSIONS_TO_IGNORE = List.of(
        ".js", ".css", ".xml", ".less", ".png", ".jpg", ".jpeg", ".gif",
        ".pdf", ".doc", ".txt", ".ico", ".rss", ".zip", ".mp3", ".rar",
        ".exe", ".wmv", ".avi", ".ppt", ".mpg", ".mpeg", ".tif", ".wav",
        ".mov", ".psd", ".ai", ".xls", ".mp4", ".m4a", ".swf", ".dat",
        ".dmg", ".iso", ".flv", ".m4v", ".torrent", ".ttf", ".woff", ".svg"
    );

    private static final String DEFAULT_SERVICE_URL = "https://service.prerender.io/";

    private final String token;
    private final String serviceUrl;

    PrerenderConfig(String token, String serviceUrl) {
        this.token = token;
        this.serviceUrl = (serviceUrl != null && !serviceUrl.isBlank())
            ? serviceUrl
            : DEFAULT_SERVICE_URL;
    }

    static PrerenderConfig fromInitParams(String initToken, String initServiceUrl) {
        return new PrerenderConfig(
            resolve(initToken, "PRERENDER_TOKEN"),
            resolve(initServiceUrl, "PRERENDER_SERVICE_URL")
        );
    }

    private static String resolve(String initParam, String envVar) {
        return (initParam != null && !initParam.isBlank()) ? initParam : System.getenv(envVar);
    }

    String getToken() { return token; }

    String getServiceUrl() { return serviceUrl; }

    static boolean isBot(String userAgent) {
        String ua = userAgent.toLowerCase();
        return CRAWLER_USER_AGENTS.stream().anyMatch(ua::contains);
    }

    static boolean isStaticAsset(String path) {
        String lower = path.toLowerCase();
        return EXTENSIONS_TO_IGNORE.stream().anyMatch(lower::endsWith);
    }
}
