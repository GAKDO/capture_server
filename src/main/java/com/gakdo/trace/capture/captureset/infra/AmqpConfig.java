package com.gakdo.trace.capture.captureset.infra;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * trace.events(topic)에 이벤트를 낸다. 큐는 소비자(inspect-worker·rental-server)가 붙인다.
 * 채널 트랜잭션을 켜서 서비스의 DB 트랜잭션이 커밋될 때 메시지도 함께 커밋되게 한다(롤백되면 나가지 않음).
 */
@Configuration
public class AmqpConfig {

    public static final String EXCHANGE = "trace.events";

    @Bean
    Declarables captureTopology() {
        return new Declarables(new TopicExchange(EXCHANGE, true, false));
    }

    /** 시각은 ISO 8601 문자열(Spring의 ObjectMapper 설정 그대로). */
    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 리스너는 본문(byte[])을 직접 읽는다. 기본 Jackson 변환기를 리스너에도 쓰면 다른 서버가 붙인 __TypeId__(보낸 쪽 자바 클래스)를
     * 찾다가 ClassNotFound로 메시지를 DLQ에 버린다(11/4 학교 서버에서 확인). 그래서 리스너만 변환하지 않는 변환기로 바꾼다.
     * 발행(RabbitTemplate)은 그대로 JSON이고, 재시도 등 나머지 리스너 설정은 Boot 설정을 그대로 쓴다.
     */
    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(new SimpleMessageConverter());
        return factory;
    }

    @Bean
    RabbitTemplateCustomizer transactedTemplate() {
        return template -> template.setChannelTransacted(true);
    }
}
