package com.nequi.ticketing.infrastructure.persistence.dynamodb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/** Creates missing tables. Only enabled for local development; cloud tables are managed by Terraform. */
public class DynamoTableBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(DynamoTableBootstrap.class);

    private final DynamoDbAsyncClient client;
    private final DynamoTables tables;

    public DynamoTableBootstrap(DynamoDbAsyncClient client, DynamoTables tables) {
        this.client = client;
        this.tables = tables;
    }

    public Mono<Void> ensureTables() {
        return Flux.fromIterable(DynamoSchema.tableDefinitions(tables)).concatMap(this::ensure).then();
    }

    private Mono<Void> ensure(CreateTableRequest definition) {
        return Mono.fromFuture(() -> client.describeTable(
                        DescribeTableRequest.builder().tableName(definition.tableName()).build()))
                .then()
                .onErrorResume(ResourceNotFoundException.class, e -> create(definition));
    }

    private Mono<Void> create(CreateTableRequest definition) {
        LOG.info("Creating DynamoDB table {}", definition.tableName());
        return Mono.fromFuture(() -> client.createTable(definition))
                .then()
                .onErrorResume(ResourceInUseException.class, e -> Mono.empty());
    }
}
