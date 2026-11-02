package com.gakdo.trace.capture.captureset.infra;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;
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

    @Bean
    RabbitTemplateCustomizer transactedTemplate() {
        return template -> template.setChannelTransacted(true);
    }
}
