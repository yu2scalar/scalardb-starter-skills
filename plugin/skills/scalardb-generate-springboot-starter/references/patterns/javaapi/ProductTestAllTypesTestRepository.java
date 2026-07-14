package com.example.demo_java_api.repository;

import com.example.demo_java_api.model.ProductTestAllTypesTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.scalar.db.api.*;
import com.scalar.db.exception.transaction.*;
import com.scalar.db.io.Key;
import org.springframework.stereotype.Repository;

@Repository
public class ProductTestAllTypesTestRepository {

    private int scanLimit = 100; // Default scan limit
    
    public void setScanLimit(int scanLimit) {
        this.scanLimit = scanLimit;
    }
    
    public int getScanLimit() {
        return scanLimit;
    }

    // Get Record by Partition & Clustering Key
    public ProductTestAllTypesTest getProductTestAllTypesTest(DistributedTransaction transaction, ProductTestAllTypesTest allTypesTest) throws CrudException {
        Key partitionKey = allTypesTest.getPartitionKey();
        Key clusteringKey = allTypesTest.getClusteringKey();
        Get get = Get.newBuilder()
            .namespace(ProductTestAllTypesTest.NAMESPACE)
            .table(ProductTestAllTypesTest.TABLE)
            .partitionKey(partitionKey)
            
            .clusteringKey(clusteringKey)
            .projections(ProductTestAllTypesTest.INT_VALUE, ProductTestAllTypesTest.BIGINT_VALUE, ProductTestAllTypesTest.FLOAT_VALUE, ProductTestAllTypesTest.BOOLEAN_VALUE, ProductTestAllTypesTest.DOUBLE_VALUE, ProductTestAllTypesTest.TEXT_VALUE, ProductTestAllTypesTest.BLOB_VALUE, ProductTestAllTypesTest.DATE_VALUE, ProductTestAllTypesTest.TIME_VALUE, ProductTestAllTypesTest.TIMESTAMP_VALUE, ProductTestAllTypesTest.TIMESTAMPTZ_VALUE)
            .build();
        Optional<Result> result = transaction.get(get);
        if (result.isEmpty()) {
            throw new RuntimeException("No record found in ProductTestAllTypesTest");
        }
        return buildProductTestAllTypesTest(result.get());
    }

    // Insert Record
    public ProductTestAllTypesTest insertProductTestAllTypesTest(DistributedTransaction transaction, ProductTestAllTypesTest allTypesTest) throws CrudException {
        Key partitionKey = allTypesTest.getPartitionKey();
        Key clusteringKey = allTypesTest.getClusteringKey();
        Insert insert = Insert.newBuilder()
            .namespace(ProductTestAllTypesTest.NAMESPACE)
            .table(ProductTestAllTypesTest.TABLE)
            .partitionKey(partitionKey)
            .clusteringKey(clusteringKey)
            .booleanValue(ProductTestAllTypesTest.BOOLEAN_VALUE, allTypesTest.getBooleanValue())
            .doubleValue(ProductTestAllTypesTest.DOUBLE_VALUE, allTypesTest.getDoubleValue())
            .textValue(ProductTestAllTypesTest.TEXT_VALUE, allTypesTest.getTextValue())
            .blobValue(ProductTestAllTypesTest.BLOB_VALUE, allTypesTest.getBlobValue())
            .dateValue(ProductTestAllTypesTest.DATE_VALUE, allTypesTest.getDateValue())
            .timeValue(ProductTestAllTypesTest.TIME_VALUE, allTypesTest.getTimeValue())
            .timestampValue(ProductTestAllTypesTest.TIMESTAMP_VALUE, allTypesTest.getTimestampValue())
            .timestampTZValue(ProductTestAllTypesTest.TIMESTAMPTZ_VALUE, allTypesTest.getTimestamptzValue())
            .build();
        transaction.insert(insert);
        return allTypesTest;
    }

    // Update Record
    public ProductTestAllTypesTest updateProductTestAllTypesTest(DistributedTransaction transaction, ProductTestAllTypesTest allTypesTest) throws CrudException {
        Key partitionKey = allTypesTest.getPartitionKey();
        Key clusteringKey = allTypesTest.getClusteringKey();
        MutationCondition condition = ConditionBuilder.updateIfExists();

        Update update = Update.newBuilder()
            .namespace(ProductTestAllTypesTest.NAMESPACE)
            .table(ProductTestAllTypesTest.TABLE)
            .partitionKey(partitionKey)
            .clusteringKey(clusteringKey)
            .booleanValue(ProductTestAllTypesTest.BOOLEAN_VALUE, allTypesTest.getBooleanValue())
            .doubleValue(ProductTestAllTypesTest.DOUBLE_VALUE, allTypesTest.getDoubleValue())
            .textValue(ProductTestAllTypesTest.TEXT_VALUE, allTypesTest.getTextValue())
            .blobValue(ProductTestAllTypesTest.BLOB_VALUE, allTypesTest.getBlobValue())
            .dateValue(ProductTestAllTypesTest.DATE_VALUE, allTypesTest.getDateValue())
            .timeValue(ProductTestAllTypesTest.TIME_VALUE, allTypesTest.getTimeValue())
            .timestampValue(ProductTestAllTypesTest.TIMESTAMP_VALUE, allTypesTest.getTimestampValue())
            .timestampTZValue(ProductTestAllTypesTest.TIMESTAMPTZ_VALUE, allTypesTest.getTimestamptzValue())
            .condition(condition)
            .build();
        transaction.update(update);
        return allTypesTest;
    }

    // Upsert Record
    public ProductTestAllTypesTest upsertProductTestAllTypesTest(DistributedTransaction transaction, ProductTestAllTypesTest allTypesTest) throws CrudException {
        Key partitionKey = allTypesTest.getPartitionKey();
        Key clusteringKey = allTypesTest.getClusteringKey();
        Upsert upsert = Upsert.newBuilder()
            .namespace(ProductTestAllTypesTest.NAMESPACE)
            .table(ProductTestAllTypesTest.TABLE)
            .partitionKey(partitionKey)
            .clusteringKey(clusteringKey)
            .booleanValue(ProductTestAllTypesTest.BOOLEAN_VALUE, allTypesTest.getBooleanValue())
            .doubleValue(ProductTestAllTypesTest.DOUBLE_VALUE, allTypesTest.getDoubleValue())
            .textValue(ProductTestAllTypesTest.TEXT_VALUE, allTypesTest.getTextValue())
            .blobValue(ProductTestAllTypesTest.BLOB_VALUE, allTypesTest.getBlobValue())
            .dateValue(ProductTestAllTypesTest.DATE_VALUE, allTypesTest.getDateValue())
            .timeValue(ProductTestAllTypesTest.TIME_VALUE, allTypesTest.getTimeValue())
            .timestampValue(ProductTestAllTypesTest.TIMESTAMP_VALUE, allTypesTest.getTimestampValue())
            .timestampTZValue(ProductTestAllTypesTest.TIMESTAMPTZ_VALUE, allTypesTest.getTimestamptzValue())
            .build();
        transaction.upsert(upsert);
        return allTypesTest;
    }

    // Delete Record
    public void deleteProductTestAllTypesTest(DistributedTransaction transaction, ProductTestAllTypesTest allTypesTest) throws CrudException {
        Key partitionKey = allTypesTest.getPartitionKey();
        Key clusteringKey = allTypesTest.getClusteringKey();
        MutationCondition condition = ConditionBuilder.deleteIfExists();
        Delete delete = Delete.newBuilder()
            .namespace(ProductTestAllTypesTest.NAMESPACE)
            .table(ProductTestAllTypesTest.TABLE)
            .partitionKey(partitionKey)
            
            .clusteringKey(clusteringKey)
            .condition(condition)
            .build();
        transaction.delete(delete);
    }

    // Scan All Records
    public List<ProductTestAllTypesTest> getProductTestAllTypesTestListAll(DistributedTransaction transaction) throws CrudException {
        Scan scan = Scan.newBuilder()
            .namespace(ProductTestAllTypesTest.NAMESPACE)
            .table(ProductTestAllTypesTest.TABLE)
            .all()
            .projections(ProductTestAllTypesTest.INT_VALUE, ProductTestAllTypesTest.BIGINT_VALUE, ProductTestAllTypesTest.FLOAT_VALUE, ProductTestAllTypesTest.BOOLEAN_VALUE, ProductTestAllTypesTest.DOUBLE_VALUE, ProductTestAllTypesTest.TEXT_VALUE, ProductTestAllTypesTest.BLOB_VALUE, ProductTestAllTypesTest.DATE_VALUE, ProductTestAllTypesTest.TIME_VALUE, ProductTestAllTypesTest.TIMESTAMP_VALUE, ProductTestAllTypesTest.TIMESTAMPTZ_VALUE)
            .limit(scanLimit)
            .build();
        List<Result> results = transaction.scan(scan);
        List<ProductTestAllTypesTest> allTypesTestList = new ArrayList<>();
        for (Result result : results) {
            allTypesTestList.add(buildProductTestAllTypesTest(result));
        }
        return allTypesTestList;
    }

    // Scan Records by Partition Key
    public List<ProductTestAllTypesTest> getProductTestAllTypesTestListByPk(DistributedTransaction transaction, Key partitionKey) throws CrudException {
        Scan scan = Scan.newBuilder()
            .namespace(ProductTestAllTypesTest.NAMESPACE)
            .table(ProductTestAllTypesTest.TABLE)
            .partitionKey(partitionKey)
            .projections(ProductTestAllTypesTest.INT_VALUE, ProductTestAllTypesTest.BIGINT_VALUE, ProductTestAllTypesTest.FLOAT_VALUE, ProductTestAllTypesTest.BOOLEAN_VALUE, ProductTestAllTypesTest.DOUBLE_VALUE, ProductTestAllTypesTest.TEXT_VALUE, ProductTestAllTypesTest.BLOB_VALUE, ProductTestAllTypesTest.DATE_VALUE, ProductTestAllTypesTest.TIME_VALUE, ProductTestAllTypesTest.TIMESTAMP_VALUE, ProductTestAllTypesTest.TIMESTAMPTZ_VALUE)
            .limit(scanLimit)
            .build();
        List<Result> results = transaction.scan(scan);
        List<ProductTestAllTypesTest> allTypesTestList = new ArrayList<>();
        for (Result result : results) {
            allTypesTestList.add(buildProductTestAllTypesTest(result));
        }
        return allTypesTestList;
    }

    // Object Builder from ScalarDB Result
    private ProductTestAllTypesTest buildProductTestAllTypesTest(Result result) {
        return ProductTestAllTypesTest.builder()
            .intValue(result.getInt(ProductTestAllTypesTest.INT_VALUE))
            .bigintValue(result.getBigInt(ProductTestAllTypesTest.BIGINT_VALUE))
            .floatValue(result.getFloat(ProductTestAllTypesTest.FLOAT_VALUE))
            .booleanValue(result.getBoolean(ProductTestAllTypesTest.BOOLEAN_VALUE))
            .doubleValue(result.getDouble(ProductTestAllTypesTest.DOUBLE_VALUE))
            .textValue(result.getText(ProductTestAllTypesTest.TEXT_VALUE))
            .blobValue(result.getBlobAsBytes(ProductTestAllTypesTest.BLOB_VALUE))
            .dateValue(result.getDate(ProductTestAllTypesTest.DATE_VALUE))
            .timeValue(result.getTime(ProductTestAllTypesTest.TIME_VALUE))
            .timestampValue(result.getTimestamp(ProductTestAllTypesTest.TIMESTAMP_VALUE))
            .timestamptzValue(result.getTimestampTZ(ProductTestAllTypesTest.TIMESTAMPTZ_VALUE))
            .build();
    }
}