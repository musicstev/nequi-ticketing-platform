package com.nequi.ticketing.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Non-blocking AWS clients on Netty. Credentials come from the default provider chain (task role in
 * AWS, environment variables locally); nothing is hardcoded. The endpoint override exists only for
 * DynamoDB Local / LocalStack.
 */
@Configuration(proxyBeanMethods = false)
class AwsClientsConfig {

    @Bean
    DynamoDbAsyncClient dynamoDbAsyncClient(AppProperties props) {
        var builder = DynamoDbAsyncClient.builder()
                .region(Region.of(props.aws().region()))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(200)
                        .connectionTimeout(Duration.ofSeconds(2)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(10))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3)));
        if (StringUtils.hasText(props.dynamodb().endpoint())) {
            builder.endpointOverride(URI.create(props.dynamodb().endpoint()));
        }
        return builder.build();
    }

    @Bean
    SqsAsyncClient sqsAsyncClient(AppProperties props) {
        var builder = SqsAsyncClient.builder()
                .region(Region.of(props.aws().region()))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(50)
                        .connectionTimeout(Duration.ofSeconds(2)))
                // Attempt timeout must exceed the long-poll wait time.
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(45))
                        .apiCallAttemptTimeout(Duration.ofSeconds(30)));
        if (StringUtils.hasText(props.sqs().endpoint())) {
            builder.endpointOverride(URI.create(props.sqs().endpoint()));
        }
        return builder.build();
    }
}
