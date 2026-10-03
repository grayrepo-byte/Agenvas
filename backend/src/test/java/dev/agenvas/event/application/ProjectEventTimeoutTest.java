package dev.agenvas.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.agenvas.shared.error.ApiExceptionHandler;
import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.shared.i18n.I18nConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/** Exercises the real emitter callbacks and MVC exception resolution after an SSE timeout. */
class ProjectEventTimeoutTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timeoutReleasesSubscriptionWithoutWritingProblemDetailIntoTheStream(boolean committed) throws Exception {
        var captured = new ListAppender<ILoggingEvent>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        captured.start();
        root.addAppender(captured);
        var meters = new SimpleMeterRegistry();
        try {
            var hub = new ProjectEventHub(mock(ProjectEventService.class), Clock.systemUTC(),
                    new SseProperties(null), meters);
            try {
                var controller = new StreamController(hub);
                var messages = new ApiMessages(new I18nConfiguration().messageSource(), new ObjectMapper());
                var mvc = MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new ApiExceptionHandler(messages))
                        // MockHttpServletResponse permits header changes after commit; Tomcat does not.
                        .addFilters((request, response, chain) -> chain.doFilter(request,
                                new HttpServletResponseWrapper((HttpServletResponse) response) {
                                    @Override
                                    public void setHeader(String name, String value) {
                                        if (!isCommitted()) super.setHeader(name, value);
                                    }

                                    @Override
                                    public void setContentType(String type) {
                                        if (!isCommitted()) super.setContentType(type);
                                    }

                                    @Override
                                    public void setStatus(int status) {
                                        if (!isCommitted()) super.setStatus(status);
                                    }
                                })).build();
                var opened = mvc.perform(get("/test/events"))
                        .andExpect(request().asyncStarted()).andReturn();
                if (committed) controller.emitter.send(SseEmitter.event().comment("heartbeat"));
                assertThat(opened.getResponse().isCommitted()).isEqualTo(committed);
                var context = (MockAsyncContext) opened.getRequest().getAsyncContext();
                for (var listener : context.getListeners()) {
                    listener.onTimeout(new AsyncEvent(context));
                }
                assertThat(opened.getAsyncResult()).isNull();
                var completed = mvc.perform(asyncDispatch(opened)).andReturn();
                assertThat(completed.getResolvedException()).isNull();
                assertThat(completed.getResponse().getStatus()).isEqualTo(HttpServletResponse.SC_OK);
                assertThat(completed.getResponse().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
                assertThat(completed.getResponse().getContentAsString()).isEqualTo(committed ? ":heartbeat\n\n" : "");
                // Completion and shutdown after timeout must not release the same connection twice.
                for (var listener : context.getListeners()) {
                    listener.onComplete(new AsyncEvent(context));
                }
                hub.stop();
                assertThat(meters.get("agenvas.sse.connections.active").gauge().value()).isZero();
                assertThat(meters.get("agenvas.sse.connections.closed").counter().count()).isEqualTo(1);
                assertThat(captured.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                        .isEmpty();
            } finally {
                hub.stop();
            }
        } finally {
            meters.close();
            root.detachAppender(captured);
            captured.stop();
        }
    }

    @RestController
    static class StreamController {
        private final ProjectEventHub hub;
        private SseEmitter emitter;

        StreamController(ProjectEventHub hub) {
            this.hub = hub;
        }

        @GetMapping(value = "/test/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter events() {
            emitter = hub.subscribe(UUID.randomUUID(), UUID.randomUUID(), 0);
            return emitter;
        }
    }
}
