package com.example;

import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class StateWriter {
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public StateWriter(final DynamoDbClient dynamoDbClient,
                       @Value("${dynamodb.table.cart-state}") final String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    public void save(Object item) {
        this.dynamoDbClient.putItem(PutItemRequest.builder().tableName(this.tableName).build());
    }
}
