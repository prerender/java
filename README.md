# prerender-java

Jakarta Servlet Filter for [Prerender.io](https://prerender.io). Intercepts requests from bots and crawlers and serves prerendered HTML, so your JavaScript-rendered app is fully indexable by search engines and social media scrapers.

Compatible with any **Jakarta EE** application server — Tomcat 10+, Jetty 11+, Spring Boot 3+, Quarkus, Micronaut.

Requires **Java 17+**.

## Installation

### Maven

```xml
<dependency>
    <groupId>io.prerender</groupId>
    <artifactId>prerender-java</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.prerender:prerender-java:1.0.0'
```

## Setup

### Option 1: Environment variables (recommended)

```bash
export PRERENDER_TOKEN=your-token
```

Register the filter in `web.xml`:

```xml
<filter>
    <filter-name>PrerenderFilter</filter-name>
    <filter-class>io.prerender.PrerenderFilter</filter-class>
</filter>
<filter-mapping>
    <filter-name>PrerenderFilter</filter-name>
    <url-pattern>/*</url-pattern>
</filter-mapping>
```

### Option 2: web.xml init-params

```xml
<filter>
    <filter-name>PrerenderFilter</filter-name>
    <filter-class>io.prerender.PrerenderFilter</filter-class>
    <init-param>
        <param-name>prerenderToken</param-name>
        <param-value>your-token</param-value>
    </init-param>
</filter>
<filter-mapping>
    <filter-name>PrerenderFilter</filter-name>
    <url-pattern>/*</url-pattern>
</filter-mapping>
```

### Spring Boot

```java
@Bean
public FilterRegistrationBean<PrerenderFilter> prerenderFilter() {
    FilterRegistrationBean<PrerenderFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new PrerenderFilter());
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
}
```

Set `PRERENDER_TOKEN` as an environment variable before starting the app.

## Settings

| Setting | Init-param | Env var | Default |
|---------|------------|---------|---------|
| Token | `prerenderToken` | `PRERENDER_TOKEN` | none |
| Service URL | `prerenderServiceUrl` | `PRERENDER_SERVICE_URL` | `https://service.prerender.io/` |

Init-params take precedence over environment variables.

## Self-hosted Prerender

```bash
export PRERENDER_SERVICE_URL=http://your-prerender-server:3000
```

## How it works

Requests are prerendered when **all** of the following are true:

- The HTTP method is `GET`
- The `User-Agent` matches a known bot/crawler (Googlebot, Bingbot, Twitterbot, GPTBot, ClaudeBot, etc.)  
  — OR the URL contains `_escaped_fragment_`  
  — OR the `X-Bufferbot` header is present
- The URL does not end with a static asset extension (`.js`, `.css`, `.png`, etc.)

Everything else passes through to your normal servlet chain.

If the Prerender service is unreachable, the filter falls back gracefully and serves the normal response.

## License

MIT
