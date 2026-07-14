package com.example.demo_java_api.model;

import lombok.*;
import com.scalar.db.io.Key;
import java.time.*;
import java.nio.ByteBuffer;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductTestAllTypesTest {

    public static final String NAMESPACE = "product_test";
    public static final String TABLE = "all_types_test";
    public static final String INT_VALUE = "int_value";
    public static final String BIGINT_VALUE = "bigint_value";
    public static final String FLOAT_VALUE = "float_value";
    public static final String BOOLEAN_VALUE = "boolean_value";
    public static final String DOUBLE_VALUE = "double_value";
    public static final String TEXT_VALUE = "text_value";
    public static final String BLOB_VALUE = "blob_value";
    public static final String DATE_VALUE = "date_value";
    public static final String TIME_VALUE = "time_value";
    public static final String TIMESTAMP_VALUE = "timestamp_value";
    public static final String TIMESTAMPTZ_VALUE = "timestamptz_value";

    private Integer intValue;
    private Long bigintValue;
    private Float floatValue;
    private Boolean booleanValue;
    private Double doubleValue;
    private String textValue;
    private byte[] blobValue;
    private LocalDate dateValue;
    private LocalTime timeValue;
    private LocalDateTime timestampValue;
    private Instant timestamptzValue;

    public Key getPartitionKey() {
        return Key.newBuilder().addInt(INT_VALUE, getIntValue()).build();
    }

    public Key getClusteringKey() {
        return Key.newBuilder().addBigInt(BIGINT_VALUE, getBigintValue()).addFloat(FLOAT_VALUE, getFloatValue()).build();
    }
}
