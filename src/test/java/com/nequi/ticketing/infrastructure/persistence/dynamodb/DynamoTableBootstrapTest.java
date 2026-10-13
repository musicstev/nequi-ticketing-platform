package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoTestSupport.failed;
import static com.nequi.ticketing.infrastructure.persistence.dynamodb.DynamoTestSupport.ok;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

class DynamoTableBootstrapTest {

    private final DynamoDbAsyncClient client = mock(DynamoDbAsyncClient.class);
    private final DynamoTableBootstrap bootstrap = new DynamoTableBootstrap(client, DynamoTables.defaults());

    @Test
    void createsOnlyMissingTables() {
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(ok(DescribeTableResponse.builder().build()))
                .thenReturn(failed(ResourceNotFoundException.builder().message("missing").build()))
                .thenReturn(ok(DescribeTableResponse.builder().build()))
                .thenReturn(ok(DescribeTableResponse.builder().build()));
        when(client.createTable(any(CreateTableRequest.class))).thenReturn(ok(CreateTableResponse.builder().build()));

        StepVerifier.create(bootstrap.ensureTables()).verifyComplete();
        verify(client, times(1)).createTable(any(CreateTableRequest.class));
    }

    @Test
    void toleratesConcurrentCreationByAnotherInstance() {
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(failed(ResourceNotFoundException.builder().message("missing").build()));
        when(client.createTable(any(CreateTableRequest.class)))
                .thenReturn(failed(ResourceInUseException.builder().message("exists").build()));

        StepVerifier.create(bootstrap.ensureTables()).verifyComplete();
        verify(client, times(4)).createTable(any(CreateTableRequest.class));
    }

    @Test
    void propagatesUnexpectedFailures() {
        when(client.describeTable(any(DescribeTableRequest.class))).thenReturn(failed(new IllegalStateException("boom")));
        StepVerifier.create(bootstrap.ensureTables()).expectError(IllegalStateException.class).verify();
        verify(client, never()).createTable(any(CreateTableRequest.class));
    }
}
