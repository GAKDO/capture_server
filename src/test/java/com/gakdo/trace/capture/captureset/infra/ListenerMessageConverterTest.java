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
        // 리스너가 받은 메시지를 담아 두는 칸 (받았는지 나중에 확인하려고)
        Message received;

        // 메시지를 받으면 불리는 메서드
        public void onMessage(Message message) {
            // 받은 메시지를 칸에 저장한다
            received = message;
        }
    }

    // [상황] 우리 서버에 없는 클래스 이름이 붙은 "시점 등급 완료" 이벤트가 온다. 운영과 같은 리스너 설정으로 받는다.
    // [기대] 리스너가 이벤트를 받고, 본문(rentalId 등)을 그대로 읽을 수 있다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시 (여기선 메서드 이름이 한글 설명을 대신함)
    @Test
    void 다른_서버의_자바_클래스_이름이_붙은_이벤트도_리스너가_받는다() throws Exception {
        // 준비: 운영 코드(AmqpConfig)가 만드는 리스너 설정을 그대로 만든다. RabbitMQ 연결은 가짜(mock)로 넣는다
        SimpleRabbitListenerContainerFactory factory = new AmqpConfig().rabbitListenerContainerFactory(
                new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties()), mock(ConnectionFactory.class));
        // 준비: 그 설정 안에 숨겨진(private) 메시지 변환기를 꺼낸다 (ReflectionTestUtils = 테스트에서 private 칸을 읽게 해 주는 도구)
        MessageConverter converter = (MessageConverter) ReflectionTestUtils.getField(factory, "messageConverter");
        // 준비: 받은 메시지를 기록할 가짜 리스너를 만든다
        Recorder recorder = new Recorder();

        // 실행: 운영 변환기를 낀 리스너 경로로 "이 서버에 없는 클래스 이름"이 붙은 이벤트를 흘려보낸다
        adapter(recorder, converter).onMessage(foreignEvent(), null);

        // (도구) assertThat(글자).contains(부분) = 글자 안에 그 부분이 들어 있어야 한다는 검사
        // 확인: 리스너가 메시지를 받았고, 본문 JSON에 "rentalId" 칸이 그대로 들어 있어야 한다
        assertThat(new String(recorder.received.getBody(), StandardCharsets.UTF_8)).contains("\"rentalId\"");
    }

    // [상황] 비교용으로, 기본 JSON 변환기로 같은 이벤트를 받는다.
    // [기대] 보낸 쪽 클래스를 찾다가 실패하고 리스너는 아무것도 받지 못한다(그래서 운영 설정이 필요하다는 근거).
    @Test
    void 기본_JSON_변환기라면_보낸_쪽_클래스를_찾다가_실패한다() throws Exception {
        // 준비: 받은 메시지를 기록할 가짜 리스너를 만든다
        Recorder recorder = new Recorder();
        // 준비: 비교용으로 스프링 기본 JSON 변환기(Jackson2JsonMessageConverter)를 낀 리스너 경로를 만든다
        MessagingMessageListenerAdapter adapter = adapter(recorder, new Jackson2JsonMessageConverter(new ObjectMapper()));

        // (도구) assertThatThrownBy(코드) = 그 코드를 실행하면 예외가 나야 한다는 검사. hasRootCauseInstanceOf = 가장 밑바닥 원인 예외의 종류
        // 실행+확인: 같은 이벤트를 흘려보내면 "클래스를 찾을 수 없음(ClassNotFoundException)"이 원인인 예외가 나야 한다
        assertThatThrownBy(() -> adapter.onMessage(foreignEvent(), null)).hasRootCauseInstanceOf(ClassNotFoundException.class);
        // 확인: 리스너는 메시지를 받지 못했어야(null) 한다 = 운영 설정이 없으면 이벤트를 놓친다는 증거
        assertThat(recorder.received).isNull();
    }

    // 테스트 도구: 가짜 리스너를 Spring 리스너 어댑터에 연결하고, 시험할 변환기를 끼운다.
    // 도우미: 가짜 리스너와 변환기를 받아 실제 스프링이 쓰는 리스너 연결 경로(어댑터)를 조립한다
    private static MessagingMessageListenerAdapter adapter(Recorder recorder, MessageConverter converter) throws Exception {
        // 리스너의 onMessage 메서드 정보를 꺼낸다 (스프링이 이 메서드를 부르게 하려고)
        Method method = Recorder.class.getMethod("onMessage", Message.class);
        // 메시지를 메서드 인자로 바꿔 넣어 주는 스프링 기본 도우미를 만든다
        DefaultMessageHandlerMethodFactory handlers = new DefaultMessageHandlerMethodFactory();
        // 도우미 초기화를 마친다 (스프링이 원래 자동으로 해 주는 단계를 직접 부름)
        handlers.afterPropertiesSet();
        // 리스너 객체와 메서드를 묶어 메시지 수신 어댑터를 만든다
        MessagingMessageListenerAdapter adapter = new MessagingMessageListenerAdapter(recorder, method);
        // 어댑터가 메시지를 받으면 가짜 리스너의 onMessage를 부르게 연결한다
        adapter.setHandlerAdapter(new HandlerAdapter(handlers.createInvocableHandlerMethod(recorder, method)));
        // 이번에 시험할 메시지 변환기를 끼운다 (운영용 또는 기본 JSON용)
        adapter.setMessageConverter(converter);
        // 조립한 어댑터를 돌려준다
        return adapter;
    }

    // 테스트 도구: 다른 서버가 보낸 것처럼 남의 클래스 이름 헤더를 붙인 JSON 이벤트를 만든다.
    // 도우미: 다른 자바 서버가 보낸 것 같은 이벤트 메시지를 만든다
    private static Message foreignEvent() {
        // 메시지 머리말(헤더) 묶음을 만든다
        MessageProperties props = new MessageProperties();
        // 본문이 JSON이라고 표시한다
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        // 등급 매김 이벤트(trace.checkpoint.graded)로 받은 것처럼 경로 이름을 붙인다
        props.setReceivedRoutingKey("trace.checkpoint.graded");
        // 보낸 쪽 클래스 이름 헤더를 이 서버에 없는 클래스 이름으로 붙인다 (문제 상황 재현)
        props.setHeader("__TypeId__", "com.gakdo.trace.other.NotHere");
        // 본문 {"rentalId":"x"}와 위 헤더로 메시지를 만들어 돌려준다
        return new Message("{\"rentalId\":\"x\"}".getBytes(StandardCharsets.UTF_8), props);
    }
}
