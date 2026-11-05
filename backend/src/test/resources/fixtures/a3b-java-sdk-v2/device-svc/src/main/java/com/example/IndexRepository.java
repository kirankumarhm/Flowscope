package com.example;

import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

public class IndexRepository {
    private final DynamoDbClient dynamoDbClient;
    private final String orderTable;
    private final String skuTable;

    public IndexRepository(
            final DynamoDbClient dynamoDbClient,
            @Value("${dynamodb.table.order-index}") final String orderTable,
            @Value("${dynamodb.table.sku-index}") final String skuTable) {
        this.dynamoDbClient = dynamoDbClient;
        this.orderTable = orderTable;
        this.skuTable = skuTable;
    }

    public Object byOrder(final String key) {
        return getByKey(this.orderTable, key);
    }

    public Object bySku(final String key) {
        return getByKey(this.skuTable, key);
    }

    private Object getByKey(final String tableName, final String key) {
        final GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .build();
        return this.dynamoDbClient.getItem(request);
    }
}
