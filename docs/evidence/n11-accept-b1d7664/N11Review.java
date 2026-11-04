import com.cryptoautotrader.api.entity.NewsSourceConfigEntity;
import com.cryptoautotrader.api.news.NewsFetchResult;
import com.cryptoautotrader.api.news.source.RssNewsSource;
import com.cryptoautotrader.api.news.source.UpbitNoticeSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Runs only fixed candidate parsers with a non-network HttpClient double. */
public class N11Review {
    static final class Client extends HttpClient {
        final String payload;
        final boolean xml;
        int calls;
        Client(String payload, boolean xml) { this.payload = payload; this.xml = xml; }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { throw new AssertionError("No TLS allowed"); }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            if (!"127.0.0.1".equals(request.uri().getHost())) throw new AssertionError("Unexpected host");
            calls++;
            T body = (T) (xml ? new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)) : payload);
            return new HttpResponse<T>() {
                public int statusCode() { return 200; }
                public HttpRequest request() { return request; }
                public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
                public T body() { return body; }
                public Optional<SSLSession> sslSession() { return Optional.empty(); }
                public URI uri() { return request.uri(); }
                public Version version() { return Version.HTTP_1_1; }
            };
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new AssertionError("No async/network allowed");
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                               HttpResponse.PushPromiseHandler<T> pushHandler) {
            throw new AssertionError("No async/network allowed");
        }
    }

    static Map<String,Object> check(String name, String payload, boolean xml, String expectedStatus, int expectedItems) throws Exception {
        Object source = xml ? new RssNewsSource() : new UpbitNoticeSource(new ObjectMapper());
        Client client = new Client(payload, xml);
        Field field = source.getClass().getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(source, client);
        NewsSourceConfigEntity config = NewsSourceConfigEntity.builder()
                .sourceId("review").sourceType(xml ? "RSS" : "UPBIT_NOTICE")
                .category("CRYPTO").url("http://127.0.0.1/review").build();
        NewsFetchResult result = xml ? ((RssNewsSource)source).fetch(config) : ((UpbitNoticeSource)source).fetch(config);
        return Map.of("case",name,"input",payload,"expectedStatus",expectedStatus,"expectedItems",expectedItems,
                "status",result.status().name(),"items",result.items().size(),"httpDoubleCalls",client.calls,
                "matches",expectedStatus.equals(result.status().name()) && expectedItems==result.items().size());
    }

    public static void main(String[] args) throws Exception {
        List<Map<String,Object>> results = new ArrayList<>();
        results.add(check("upbit_explicit_failure_empty", "{\"success\":false,\"data\":{\"notices\":[]}}", false, "FAILED", 0));
        results.add(check("upbit_explicit_failure_one", "{\"success\":false,\"data\":{\"notices\":[{\"id\":1,\"title\":\"untrusted\"}]}}", false, "FAILED", 0));
        results.add(check("upbit_success_empty", "{\"success\":true,\"data\":{\"notices\":[]}}", false, "SUCCESS", 0));
        results.add(check("rss_nonfeed_with_channel", "<html><body><channel/></body></html>", true, "FAILED", 0));
        results.add(check("rss_valid_empty", "<rss version=\"2.0\"><channel><title>t</title></channel></rss>", true, "SUCCESS", 0));
        results.add(check("rss_valid_one", "<rss version=\"2.0\"><channel><item><title>a</title><guid>g1</guid></item></channel></rss>", true, "SUCCESS", 1));
        System.out.println("REVIEW_JSON=" + new ObjectMapper().writeValueAsString(results));
        if (results.stream().anyMatch(r -> !Boolean.TRUE.equals(r.get("matches")))) System.exit(1);
    }
}
