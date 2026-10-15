package com.arcade.player.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    /**
     * Numeric request fields must be JSON numbers: {@code {"amount": "10"}} is refused instead of
     * being read as 10. The refusal becomes a 400 VALIDATION_ERROR in GlobalExceptionHandler.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictNumbers() {
        return builder -> builder.postConfigurer(mapper -> {
            mapper.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            mapper.coercionConfigFor(LogicalType.Float).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        });
    }
}
