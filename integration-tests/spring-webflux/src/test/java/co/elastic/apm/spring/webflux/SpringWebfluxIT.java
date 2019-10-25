package co.elastic.apm.spring.webflux;

import co.elastic.apm.agent.MockReporter;
import co.elastic.apm.agent.bci.ElasticApmAgent;
import co.elastic.apm.agent.configuration.SpyConfiguration;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.ElasticApmTracerBuilder;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.report.ReporterConfiguration;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.stagemonitor.configuration.ConfigurationRegistry;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.web.reactive.function.BodyInserters.fromObject;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SpringWebfluxIT {

    private static MockReporter reporter;
    private static ConfigurationRegistry config;
    @LocalServerPort
    private int port;

    @Autowired
    private WebTestClient webTestClient;

    @BeforeClass
    public static void beforeClass() {
        config = SpyConfiguration.createSpyConfig();
        reporter = new MockReporter();
        ElasticApmTracer tracer = new ElasticApmTracerBuilder()
            .configurationRegistry(config)
            .reporter(reporter)
            .build();
        ElasticApmAgent.initInstrumentation(tracer, ByteBuddyAgent.install());
    }

    @Before
    public void setUp() {
        when(config.getConfig(ReporterConfiguration.class).isReportSynchronously()).thenReturn(true);
        reporter.reset();
    }

    @AfterClass
    public static void tearDown() {
        ElasticApmAgent.reset();
    }

    @Test
    public void testAnnotatedController() throws Exception {
        webTestClient
            // Create a GET request to test an endpoint
            .get().uri("/hello")
            .accept(MediaType.TEXT_PLAIN)
            .exchange()
            // and use the dedicated DSL to test assertions against the response
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("Hello World");

        assertNotNull(reporter.getTransactions());
        assertTrue(reporter.getTransactions().size() > 0);
        final Transaction transaction = reporter.getFirstTransaction(500);
        assertThat(transaction.getNameAsString()).isEqualTo("WebfluxApplication#greeting");
    }

    @Test
    public void testRouterFunction() throws Exception {
        webTestClient
            // Create a GET request to test an endpoint
            .get().uri("/hello2")
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            // and use the dedicated DSL to test assertions against the response
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("Hello World");

        assertNotNull(reporter.getTransactions());
        assertTrue(reporter.getTransactions().size() > 0);
        final Transaction transaction = reporter.getFirstTransaction(500);
        assertThat(transaction.getNameAsString()).startsWith("SpringWebfluxIT$WebfluxApplication");
    }

    @RestController
    @SpringBootApplication
    public static class WebfluxApplication {

        public static void main(String[] args) {
            SpringApplication.run(WebfluxApplication.class, args);
        }

        @GetMapping("/hello")
        public Mono<String> greeting() {
            return Mono.just("Hello World");
        }

        @Bean
        public RouterFunction<ServerResponse> route() {

            return RouterFunctions.route(
                RequestPredicates
                    .GET("/hello2")
                    .and(RequestPredicates.accept(MediaType.APPLICATION_JSON)), request -> ServerResponse.ok().body(fromObject("Hello World")));
        }
    }
}
