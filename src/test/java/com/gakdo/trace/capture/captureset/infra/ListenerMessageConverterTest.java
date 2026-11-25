// 이 파일이 확인하는 것: 다른 서버(rental-server 등)가 RabbitMQ로 보낸 이벤트를 capture-server 리스너가 제대로 받는지.
// 자바로 만든 다른 서버는 메시지에 자기 클래스 이름(__TypeId__)을 붙여 보내는데, 우리 서버에는 그 클래스가 없다.
// 그래도 이벤트를 받아야 하므로, 클래스 이름을 무시하는 변환기를 쓰는지 실제 Spring 경로로 확인한다.

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
    // 테스트 도구: 받은 메시지를 그대로 기억해 두는 가짜 리스너.
    public static class Recorder {
        Message received;

        public void onMessage(Message message) {
            received = message;
        }
    }

    // [상황] 우리 서버에 없는 클래스 이름이 붙은 "시점 등급 완료" 이벤트가 온다. 운영과 같은 리스너 설정으로 받는다.
    // [기대] 리스너가 이벤트를 받고, 본문(rentalId 등)을 그대로 읽을 수 있다.
    @Test
    void 다른_서버의_자바_클래스_이름이_붙은_이벤트도_리스너가_받는다() throws Exception {
        SimpleRabbitListenerContainerFactory factory = new AmqpConfig().rabbitListenerContainerFactory(
                new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()), mock(ConnectionFactory.class));
        MessageConverter converter = (MessageConverter) ReflectionTestUtils.getField(factory, "messageConverter");
        Recorder recorder = new Recorder();

        adapter(recorder, converter).onMessage(foreignEvent(), null);

        assertThat(new String(recorder.received.getBody(), StandardCharsets.UTF_8)).contains("\"rentalId\"");
    }

    // [상황] 비교용으로, 기본 JSON 변환기로 같은 이벤트를 받는다.
    // [기대] 보낸 쪽 클래스를 찾다가 실패하고 리스너는 아무것도 받지 못한다(그래서 운영 설정이 필요하다는 근거).
    @Test
    void 기본_JSON_변환기라면_보낸_쪽_클래스를_찾다가_실패한다() throws Exception {
        Recorder recorder = new Recorder();
        MessagingMessageListenerAdapter adapter = adapter(recorder, new Jackson2JsonMessageConverter(new ObjectMapper()));

        assertThatThrownBy(() -> adapter.onMessage(foreignEvent(), null)).hasRootCauseInstanceOf(ClassNotFoundException.class);
        assertThat(recorder.received).isNull();
    }

    // 테스트 도구: 가짜 리스너를 Spring 리스너 어댑터에 연결하고, 시험할 변환기를 끼운다.
    private static MessagingMessageListenerAdapter adapter(Recorder recorder, MessageConverter converter) throws Exception {
        Method method = Recorder.class.getMethod("onMessage", Message.class);
        DefaultMessageHandlerMethodFactory handlers = new DefaultMessageHandlerMethodFactory();
        handlers.afterPropertiesSet();
        MessagingMessageListenerAdapter adapter = new MessagingMessageListenerAdapter(recorder, method);
        adapter.setHandlerAdapter(new HandlerAdapter(handlers.createInvocableHandlerMethod(recorder, method)));
        adapter.setMessageConverter(converter);
        return adapter;
    }

    // 테스트 도구: 다른 서버가 보낸 것처럼 남의 클래스 이름 헤더를 붙인 JSON 이벤트를 만든다.
    private static Message foreignEvent() {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setReceivedRoutingKey("trace.checkpoint.graded");
        props.setHeader("__TypeId__", "com.gakdo.trace.other.NotHere");
        return new Message("{\"rentalId\":\"x\"}".getBytes(StandardCharsets.UTF_8), props);
    }
}
