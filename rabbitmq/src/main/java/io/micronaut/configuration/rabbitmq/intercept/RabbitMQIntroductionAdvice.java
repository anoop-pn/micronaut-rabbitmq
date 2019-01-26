/*
 * Copyright 2017-2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micronaut.configuration.rabbitmq.intercept;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AMQP.BasicProperties.Builder;
import com.rabbitmq.client.Channel;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.configuration.rabbitmq.annotation.RabbitClient;
import io.micronaut.configuration.rabbitmq.annotation.RabbitProperty;
import io.micronaut.configuration.rabbitmq.annotation.Binding;
import io.micronaut.configuration.rabbitmq.connect.ChannelPool;
import io.micronaut.configuration.rabbitmq.reactivex.ReactiveChannel;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.messaging.annotation.Body;
import io.micronaut.messaging.annotation.Header;
import io.micronaut.messaging.exceptions.MessagingClientException;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Implementation of the {@link RabbitClient} advice annotation.
 *
 * @author James Kleeh
 * @see RabbitClient
 * @since 1.1.0
 */
@Singleton
public class RabbitMQIntroductionAdvice implements MethodInterceptor<Object, Object> {

    private static final Logger LOG = LoggerFactory.getLogger(RabbitMQIntroductionAdvice.class);

    private final ChannelPool channelPool;
    private final ConversionService<?> conversionService;
    private final Map<String, BiConsumer<Object, Builder>> properties = new HashMap<>();

    /**
     * Default constructor.
     *
     * @param channelPool The pool to retrieve a channel from
     * @param conversionService The conversion service
     */
    public RabbitMQIntroductionAdvice(ChannelPool channelPool,
                               ConversionService<?> conversionService) {
        this.channelPool = channelPool;
        this.conversionService = conversionService;
        properties.put("contentType", (prop, builder) -> builder.contentType((String) prop));
        properties.put("contentEncoding", (prop, builder) -> builder.contentEncoding((String) prop));
        properties.put("deliveryMode", (prop, builder) -> builder.deliveryMode((Integer) prop));
        properties.put("priority", (prop, builder) -> builder.priority((Integer) prop));
        properties.put("correlationId", (prop, builder) -> builder.correlationId((String) prop));
        properties.put("replyTo", (prop, builder) -> builder.replyTo((String) prop));
        properties.put("expiration", (prop, builder) -> builder.expiration((String) prop));
        properties.put("messageId", (prop, builder) -> builder.messageId((String) prop));
        properties.put("timestamp", (prop, builder) -> builder.timestamp(new Date((Integer) prop)));
        properties.put("type", (prop, builder) -> builder.type((String) prop));
        properties.put("userId", (prop, builder) -> builder.userId((String) prop));
        properties.put("appId", (prop, builder) -> builder.appId((String) prop));
        properties.put("clusterId", (prop, builder) -> builder.clusterId((String) prop));
    }

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {

        if (context.hasAnnotation(RabbitClient.class)) {
            AnnotationValue<RabbitClient> client = context.findAnnotation(RabbitClient.class).orElseThrow(() -> new IllegalStateException("No @RabbitClient annotation present on method: " + context));

            String exchange = client.getValue(String.class).orElse("");

            String routingKey = findRoutingKey(context).map(ann -> ann.getRequiredValue(String.class)).orElseThrow(() -> new MessagingClientException("No routing key specified for method: " + context));

            Argument bodyArgument = findBodyArgument(context).orElseThrow(() -> new MessagingClientException("No valid message body argument found for method: " + context));

            Builder builder = new Builder();

            Map<String, Object> headers = new HashMap<>();

            context.getAnnotationValuesByType(Header.class).forEach((header) -> {
                String name = header.get("name", String.class).orElse(null);
                String value = header.getValue(String.class).orElse(null);

                if (StringUtils.isNotEmpty(name) && StringUtils.isNotEmpty(value)) {
                    headers.put(name, value);
                }
            });

            context.getAnnotationValuesByType(RabbitProperty.class).forEach((prop) -> {
                String name = prop.get("name", String.class).orElse(null);
                String value = prop.getValue(String.class).orElse(null);

                if (StringUtils.isNotEmpty(name) && StringUtils.isNotEmpty(value)) {
                    setBasicProperty(builder, name, value);
                }
            });

            Argument[] arguments = context.getArguments();
            Map<String, Object> parameterValues = context.getParameterValueMap();
            for (Argument argument : arguments) {
                AnnotationValue<Header> headerAnn = argument.getAnnotation(Header.class);
                if (headerAnn != null) {
                    Map.Entry<String, Object> entry = getNameAndValue(argument, headerAnn, parameterValues);
                    if (entry.getValue() != null) {
                        headers.put(entry.getKey(), entry.getValue());
                    }
                }

                AnnotationValue<RabbitProperty> propertyAnn = argument.getAnnotation(RabbitProperty.class);
                if (propertyAnn != null) {
                    Map.Entry<String, Object> entry = getNameAndValue(argument, propertyAnn, parameterValues);
                    setBasicProperty(builder, entry.getKey(), entry.getValue());
                }
            }

            if (!headers.isEmpty()) {
                builder.headers(headers);
            }

            AMQP.BasicProperties properties = builder.build();

            if (LOG.isDebugEnabled()) {
                LOG.debug("Sending a message to exchange [{}] with binding [{}] and properties [{}]", exchange, routingKey, properties);
            }

            ReturnType<Object> returnType = context.getReturnType();
            Class javaReturnType = returnType.getType();
            boolean isReactiveReturnType = Publishers.isConvertibleToPublisher(javaReturnType);

            Channel channel = getChannel();

            Object body = parameterValues.get(bodyArgument.getName());
            byte[] converted = conversionService.convert(body, byte[].class).orElseThrow(() -> new MessagingClientException("Could not convert the body argument of type [%s] to a byte[] for publishing"));

            try {
                if (isReactiveReturnType) {

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Determined the method [{}] returns a reactive type. Sending the message with confirms.", context);
                    }

                    ReactiveChannel reactiveChannel = new ReactiveChannel(channel);
                    try {
                        Completable completable = reactiveChannel.publish(exchange, routingKey, properties, converted);

                        return conversionService.convert(completable, javaReturnType).orElseThrow(() -> new MessagingClientException("Could not convert the publish response to the return type of the method"));
                    } finally {
                        reactiveChannel.finish().doOnSuccess(chnl -> {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("The publish has terminated. Returning the channel to the pool");
                            }
                            channelPool.returnChannel(chnl);
                        }).subscribe();
                    }
                } else {

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Determined the method [{}] does not return a reactive type. Sending the message without confirms and returning null.", context);
                    }

                    try {
                        channel.basicPublish(exchange, routingKey, properties, converted);
                    } finally {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Returning the channel to the pool");
                        }
                        channelPool.returnChannel(channel);
                    }
                }
            } catch (Throwable e) {
                throw new MessagingClientException(String.format("Failed to publish a message with exchange: [%s] and routing key [%s]", exchange, routingKey), e);
            }

            return null;
        } else {
            // can't be implemented so proceed
            return context.proceed();
        }
    }

    private Map.Entry<String, Object> getNameAndValue(Argument argument, AnnotationValue<?> annotationValue, Map<String, Object> parameterValues) {
        String argumentName = argument.getName();
        String name = annotationValue.get("name", String.class).orElse(annotationValue.getValue(String.class).orElse(argumentName));
        Object value = parameterValues.get(argumentName);

        return new AbstractMap.SimpleEntry<>(name, value);
    }

    private void setBasicProperty(Builder builder, String name, Object value) {
        if (value != null) {
            BiConsumer<Object, Builder> consumer = properties.get(name);
            if (consumer != null) {
                consumer.accept(value, builder);
            } else {
                throw new MessagingClientException(String.format("Attempted to set property [%s], but could not match the name to any of the com.rabbitmq.client.BasicProperties", name));
            }
        }
    }

    private Optional<AnnotationValue<Binding>> findRoutingKey(ExecutableMethod<?, ?> method) {
        if (method.hasAnnotation(Binding.class)) {
            return Optional.ofNullable(method.getAnnotation(Binding.class));
        } else {
            return Arrays.stream(method.getArguments())
                    .map(arg -> arg.getAnnotation(Binding.class))
                    .filter(Objects::nonNull)
                    .findFirst();
        }
    }

    private Optional<Argument> findBodyArgument(ExecutableMethod<?, ?> method) {
        return Optional.ofNullable(Arrays.stream(method.getArguments())
                .filter(arg -> arg.getAnnotationMetadata().hasAnnotation(Body.class))
                .findFirst()
                .orElseGet(() ->
                        Arrays.stream(method.getArguments())
                                .filter(arg -> !arg.getAnnotationMetadata().hasStereotype(Bindable.class))
                                .findFirst()
                                .orElse(null)
                ));
    }

    private Channel getChannel() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Retrieving a channel from the pool");
        }

        Channel channel = null;
        try {
            channel = channelPool.getChannel();
            return channel;
        } catch (IOException e) {
            throw new MessagingClientException("Could not retrieve a channel from the pool", e);
        } finally {
            if (channel != null) {
                channelPool.returnChannel(channel);
            }
        }
    }
}