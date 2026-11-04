package com.gakdo.trace.capture.captureset.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.adapter.HandlerAdapter;
import org.springframework.amqp.rabbit.listener.adapter.MessagingMessageListenerAdapter;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 다른 서버(자바 생산자)가 __TypeId__ 헤더를 붙여 보낸 이벤트를 리스너가 실제 Spring 경로로 받는지 본다. */
class ListenerMessageConverterTest {

    /** 리스너와 같은 모양(Message를 그대로 받음) */
    public static class Recorder {
        Message received;

        public void onMessage(Message message) {
            received = message;
        }
    }

    @Test
    void 다른_서버의_자바_클래스_이름이_붙은_이벤트도_리스너가_받는다() throws Exception {
        SimpleRabbitListenerContainerFactory factory = new AmqpConfig().rabbitListenerContainerFactory(
                new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()), mock(ConnectionFactory.class));
        MessageConverter converter = (MessageConverter) ReflectionTestUtils.getField(factory, "messageConverter");
        Recorder recorder = new Recorder();

        adapter(recorder, converter).onMessage(foreignEvent(), null);

        assertThat(new String(recorder.received.getBody(), StandardCharsets.UTF_8)).contains("\"rentalId\"");
    }

    @Test
    void 기본_JSON_변환기라면_보낸_쪽_클래스를_찾다가_실패한다() throws Exception {
        Recorder recorder = new Recorder();
        MessagingMessageListenerAdapter adapter = adapter(recorder, new Jackson2JsonMessageConverter(new ObjectMapper()));

        assertThatThrownBy(() -> adapter.onMessage(foreignEvent(), null)).hasRootCauseInstanceOf(ClassNotFoundException.class);
        assertThat(recorder.received).isNull();
    }

    private static MessagingMessageListenerAdapter adapter(Recorder recorder, MessageConverter converter) throws Exception {
        Method method = Recorder.class.getMethod("onMessage", Message.class);
        DefaultMessageHandlerMethodFactory handlers = new DefaultMessageHandlerMethodFactory();
        handlers.afterPropertiesSet();
        MessagingMessageListenerAdapter adapter = new MessagingMessageListenerAdapter(recorder, method);
        adapter.setHandlerAdapter(new HandlerAdapter(handlers.createInvocableHandlerMethod(recorder, method)));
        adapter.setMessageConverter(converter);
        return adapter;
    }

    private static Message foreignEvent() {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setReceivedRoutingKey("trace.checkpoint.graded");
        props.setHeader("__TypeId__", "com.gakdo.trace.other.NotHere");
        return new Message("{\"rentalId\":\"x\"}".getBytes(StandardCharsets.UTF_8), props);
    }
}
