package com.example.payment.infra;

import com.example.payment.model.OrderPaymentCard;
import com.example.payment.model.OrderPayment;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    private ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule()); 
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return objectMapper;
    }

    // RedisTemplate for OrderPaymentCard
    @Bean
    public RedisTemplate<String, OrderPaymentCard> orderPaymentCardRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, OrderPaymentCard> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        Jackson2JsonRedisSerializer<OrderPaymentCard> serializer = new Jackson2JsonRedisSerializer<>(OrderPaymentCard.class);
        serializer.setObjectMapper(objectMapper());

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);

        return template;
    }

    // RedisTemplate for OrderPayment
    @Bean
    public RedisTemplate<String, OrderPayment> orderPaymentRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, OrderPayment> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        Jackson2JsonRedisSerializer<OrderPayment> serializer = new Jackson2JsonRedisSerializer<>(OrderPayment.class);
        serializer.setObjectMapper(objectMapper());

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);

        return template;
    }
}
