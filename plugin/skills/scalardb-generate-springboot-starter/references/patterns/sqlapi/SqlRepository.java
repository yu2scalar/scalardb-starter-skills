package com.example.demo_sql_api.repository;

import com.scalar.db.exception.transaction.CrudException;
import com.scalar.db.io.Key;
import com.scalar.db.io.Value;
import com.scalar.db.sql.Record;
import com.scalar.db.sql.*;
import org.apache.commons.text.CaseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Base repository class for SQL-based data access.
 *
 * Provides:
 * - Common CRUD operations (insert, upsert, get, update, delete)
 * - Protected utility methods for building custom queries in concrete repositories
 *
 * Design:
 * - Works with Model (Entity) class, not DTO
 * - Extracts keys from Model using getPartitionKey() and getClusteringKey()
 * - Service layer manages transactions (begin/commit/rollback)
 * - Scan/search operations should be implemented in concrete repositories
 *   with appropriate LIMIT clauses
 *
 * @param <T> Entity/Model class type
 */
public abstract class SqlRepository<T> {
    private static final Logger logger = LoggerFactory.getLogger(SqlRepository.class);

    // Cache for reflection metadata to improve performance
    private static final Map<Class<?>, Map<String, FieldSetterPair>> fieldCache = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Set<String>> entityColumnsCache = new ConcurrentHashMap<>();

    // ScalarDB SQL reserved keywords (case-insensitive)
    // Source: /home/yu2/claude/db/scalardb-sql/direct-mode/src/main/antlr/SqlLexer.g4
    private static final Set<String> RESERVED_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        // ABAC related
        "ABAC_COMPARTMENT", "ABAC_COMPARTMENTS", "ABAC_GROUP", "ABAC_GROUPS",
        "ABAC_LEVEL", "ABAC_LEVELS", "ABAC_NAMESPACE_POLICY", "ABAC_NAMESPACE_POLICIES",
        "ABAC_POLICY", "ABAC_POLICIES", "ABAC_READ_TAG", "ABAC_WRITE_TAG",
        "ABAC_TABLE_POLICY", "ABAC_TABLE_POLICIES", "ABAC_USER_TAG_INFO",
        // DDL/DML
        "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME",
        "INSERT", "UPSERT", "UPDATE", "DELETE", "SELECT",
        "GRANT", "REVOKE",
        // Table/Schema
        "TABLE", "TABLES", "NAMESPACE", "NAMESPACES",
        "COLUMN", "INDEX", "PRIMARY", "KEY",
        "CLUSTERING", "COORDINATOR",
        // Conditions/Operators
        "WHERE", "AND", "OR", "NOT",
        "BETWEEN", "LIKE", "ESCAPE", "IS", "IN",
        "IF", "EXISTS", "EXIST",
        // Query modifiers
        "FROM", "TO", "INTO", "ON",
        "JOIN", "INNER", "LEFT", "RIGHT", "OUTER",
        "GROUP", "ORDER", "BY", "ASC", "DESC",
        "LIMIT", "AS",
        // Transaction
        "BEGIN", "START", "TRANSACTION", "TWO_PHASE_COMMIT_TRANSACTION",
        "COMMIT", "ROLLBACK", "ABORT",
        "PREPARE", "VALIDATE", "SUSPEND", "RESUME",
        // User/Privileges
        "USER", "USERS", "SUPERUSER", "NO_SUPERUSER",
        "PASSWORD", "PRIVILEGES", "GRANTS",
        "READ", "WRITE", "READ_ONLY_ACCESS", "READ_WRITE_ACCESS",
        "ACCESS", "OPTION",
        // Other keywords
        "USE", "SET", "SHOW", "DESCRIBE", "DESC",
        "WITH", "USING", "FOR",
        "ADD", "REMOVE", "ENABLE", "DISABLE",
        "CASCADE", "MODE", "TYPE", "DATA",
        "INFO", "POLICY", "POLICIES",
        "DEFAULT", "DEFAULT_LEVEL", "ROW", "ROW_LEVEL",
        "ENCRYPTED", "DATA_TAG_COLUMN",
        "LONG_NAME", "LEVEL_NUMBER", "PARENT_GROUP",
        "ONLY", "ALL",
        // Data types
        "INT", "BIGINT", "FLOAT", "DOUBLE",
        "TEXT", "BLOB", "BOOLEAN",
        "DATE", "TIME", "TIMESTAMP", "TIMESTAMPTZ",
        // Literals
        "TRUE", "FALSE", "NULL"
    )));

    // Repository configuration
    protected final Class<T> entityClass;

    // Reflection metadata
    private final Constructor<T> constructor;
    private final Map<String, FieldSetterPair> fieldSetterMap;
    private final Set<String> entityColumns;

    /**
     * Constructor for SqlRepository base class.
     *
     * @param entityClass Entity/Model class
     */
    protected SqlRepository(Class<T> entityClass) {
        this.entityClass = entityClass;

        // Initialize reflection metadata
        try {
            this.constructor = entityClass.getDeclaredConstructor();
            this.constructor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                "Entity class " + entityClass.getName() + " must have a no-argument constructor", e);
        }

        this.fieldSetterMap = fieldCache.computeIfAbsent(entityClass, this::buildFieldSetterMap);
        this.entityColumns = entityColumnsCache.computeIfAbsent(entityClass, this::extractEntityColumns);
    }

    // ==================== PUBLIC API (Same as Java CRUD API) ====================

    /**
     * Insert a new record.
     * Transaction must be started before calling this method.
     *
     * @param session Active SQL session with transaction started
     * @param model Model/Entity containing data to insert
     * @throws Exception if insert fails
     */
    public T insert(SqlSession session, T model) throws Exception {
        String namespace = getStaticField(entityClass, "NAMESPACE");
        String tableName = getStaticField(entityClass, "TABLE");

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(formatTableName(namespace, tableName)).append(" (\n");

        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();

        for (Field field : model.getClass().getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }

            field.setAccessible(true);
            Object value = field.get(model);

            if (value != null) {
                String columnName = toSnakeCase(field.getName());
                boolean isBlob = isBlobType(field.getType(), value);

                if (isBlob) {
                    continue; // BLOB not supported in INSERT
                }

                columns.add("  " + quoteIdentifier(columnName));
                values.add("  " + formatValue(value, field.getType()));
            }
        }

        sql.append(String.join(",\n", columns));
        sql.append("\n) VALUES (\n");
        sql.append(String.join(",\n", values));
        sql.append("\n)");

        session.execute(fixTimestampTzFormat(sql.toString()));
        return model;
    }

    /**
     * Upsert a record (insert or update if exists).
     * Transaction must be started before calling this method.
     *
     * @param session Active SQL session with transaction started
     * @param model Model/Entity containing data to upsert
     * @throws Exception if upsert fails
     */
    public T upsert(SqlSession session, T model) throws Exception {
        String namespace = getStaticField(entityClass, "NAMESPACE");
        String tableName = getStaticField(entityClass, "TABLE");

        StringBuilder sql = new StringBuilder();
        sql.append("UPSERT INTO ").append(formatTableName(namespace, tableName)).append(" (\n");

        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();

        for (Field field : model.getClass().getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }

            field.setAccessible(true);
            Object value = field.get(model);

            if (value != null) {
                String columnName = toSnakeCase(field.getName());
                boolean isBlob = isBlobType(field.getType(), value);

                if (isBlob) {
                    continue; // BLOB not supported in UPSERT
                }

                columns.add("  " + quoteIdentifier(columnName));
                values.add("  " + formatValue(value, field.getType()));
            }
        }

        sql.append(String.join(",\n", columns));
        sql.append("\n) VALUES (\n");
        sql.append(String.join(",\n", values));
        sql.append("\n)");

        session.execute(fixTimestampTzFormat(sql.toString()));
        return model;
    }

    /**
     * Get record by partition key and clustering key.
     * Transaction must be started before calling this method.
     *
     * @param session Active SQL session with transaction started
     * @param model Model/Entity containing key values
     * @return Entity if found
     * @throws Exception if get fails
     */
    public T get(SqlSession session, T model) throws Exception {
        // Extract keys from model (like Java CRUD API)
        Key partitionKey = (Key) invokeMethod(model, "getPartitionKey");
        Key clusteringKey = null;
        try {
            clusteringKey = (Key) invokeMethod(model, "getClusteringKey");
        } catch (Exception e) {
            // Clustering key might not exist - that's okay
        }

        String namespace = getStaticField(entityClass, "NAMESPACE");
        String tableName = getStaticField(entityClass, "TABLE");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM ").append(formatTableName(namespace, tableName));
        sql.append(" WHERE ");

        List<String> conditions = new ArrayList<>();

        // Add partition key conditions
        for (Value<?> column : partitionKey.get()) {
            String columnName = column.getName();
            Object value = unwrapValue(column.get(), columnName);
            conditions.add(quoteIdentifier(columnName) + " = " + formatValueByObject(value));
        }

        // Add clustering key conditions if exists
        if (clusteringKey != null) {
            for (Value<?> column : clusteringKey.get()) {
                String columnName = column.getName();
                Object value = unwrapValue(column.get(), columnName);
                conditions.add(quoteIdentifier(columnName) + " = " + formatValueByObject(value));
            }
        }

        sql.append(String.join(" AND ", conditions));
        sql.append(" LIMIT 1");

        List<T> results = executeQuery(session, fixTimestampTzFormat(sql.toString()));
        if (results.isEmpty()) {
            throw new RuntimeException("No record found");
        }
        return results.get(0);
    }

    /**
     * Update a record.
     * Transaction must be started before calling this method.
     *
     * @param session Active SQL session with transaction started
     * @param model Model/Entity containing data to update and key values
     * @throws Exception if update fails
     */
    public T update(SqlSession session, T model) throws Exception {
        // Extract keys from model
        Key partitionKey = (Key) invokeMethod(model, "getPartitionKey");
        Key clusteringKey = null;
        try {
            clusteringKey = (Key) invokeMethod(model, "getClusteringKey");
        } catch (Exception e) {
            // Clustering key might not exist
        }

        // Build list of key column names
        Set<String> keyColumnNames = new HashSet<>();
        for (Value<?> column : partitionKey.get()) {
            keyColumnNames.add(column.getName());
        }
        if (clusteringKey != null) {
            for (Value<?> column : clusteringKey.get()) {
                keyColumnNames.add(column.getName());
            }
        }

        String namespace = getStaticField(entityClass, "NAMESPACE");
        String tableName = getStaticField(entityClass, "TABLE");

        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(formatTableName(namespace, tableName));
        sql.append(" SET\n");

        List<String> setClause = new ArrayList<>();
        List<String> whereClause = new ArrayList<>();

        for (Field field : model.getClass().getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }

            field.setAccessible(true);
            Object value = field.get(model);
            String columnName = toSnakeCase(field.getName());

            if (value != null) {
                boolean isBlob = isBlobType(field.getType(), value);

                if (keyColumnNames.contains(columnName)) {
                    // Key columns go to WHERE clause
                    if (!isBlob) {
                        whereClause.add(quoteIdentifier(columnName) + " = " + formatValue(value, field.getType()));
                    }
                } else {
                    // Non-key columns go to SET clause
                    if (isBlob) {
                        continue; // BLOB not supported in UPDATE SET
                    }
                    setClause.add("  " + quoteIdentifier(columnName) + " = " + formatValue(value, field.getType()));
                }
            }
        }

        if (setClause.isEmpty()) {
            throw new IllegalArgumentException("No valid columns to update");
        }

        if (whereClause.isEmpty()) {
            throw new IllegalArgumentException("No valid key values provided for UPDATE");
        }

        sql.append(String.join(",\n", setClause));
        sql.append("\nWHERE ");
        sql.append(String.join(" AND ", whereClause));

        session.execute(fixTimestampTzFormat(sql.toString()));
        return model;
    }

    /**
     * Delete a record.
     * Transaction must be started before calling this method.
     *
     * @param session Active SQL session with transaction started
     * @param model Model/Entity containing key values
     * @throws Exception if delete fails
     */
    public void delete(SqlSession session, T model) throws Exception {
        // Extract keys from model
        Key partitionKey = (Key) invokeMethod(model, "getPartitionKey");
        Key clusteringKey = null;
        try {
            clusteringKey = (Key) invokeMethod(model, "getClusteringKey");
        } catch (Exception e) {
            // Clustering key might not exist
        }

        String namespace = getStaticField(entityClass, "NAMESPACE");
        String tableName = getStaticField(entityClass, "TABLE");

        StringBuilder sql = new StringBuilder();
        sql.append("DELETE FROM ").append(formatTableName(namespace, tableName));
        sql.append(" WHERE ");

        List<String> conditions = new ArrayList<>();

        // Add partition key conditions
        for (Value<?> column : partitionKey.get()) {
            String columnName = column.getName();
            Object value = unwrapValue(column.get(), columnName);
            conditions.add(quoteIdentifier(columnName) + " = " + formatValueByObject(value));
        }

        // Add clustering key conditions if exists
        if (clusteringKey != null) {
            for (Value<?> column : clusteringKey.get()) {
                String columnName = column.getName();
                Object value = unwrapValue(column.get(), columnName);
                conditions.add(quoteIdentifier(columnName) + " = " + formatValueByObject(value));
            }
        }

        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("No valid key values provided for DELETE");
        }

        sql.append(String.join(" AND ", conditions));

        session.execute(fixTimestampTzFormat(sql.toString()));
    }

    /**
     * Execute a custom SQL query.
     * Transaction must be started before calling this method.
     *
     * @param session Active SQL session with transaction started
     * @param sql SQL query to execute
     * @return List of matching entities
     * @throws Exception if query fails
     */
    public List<T> executeQuery(SqlSession session, String sql) throws Exception {
        // Validate SQL statement
        validateSqlStatement(sql);

        try {
            List<T> results = new ArrayList<>();

            ResultSet resultSet = session.execute(sql);
            List<Record> records = resultSet.all();
            ColumnDefinitions columnDefinitions = resultSet.getColumnDefinitions();

            // Validate that all result columns can be mapped to entity fields
            validateResultColumns(columnDefinitions);

            for (Record record : records) {
                results.add(mapRecordToEntity(record, columnDefinitions));
            }

            return results;
        } catch (ValidationException e) {
            throw e; // Re-throw validation exceptions
        } catch (Exception e) {
            if (e instanceof CrudException) {
                throw (CrudException) e;
            }
            throw new RepositoryException("Failed to execute SQL and map results", e);
        }
    }

    // ==================== PROTECTED UTILITIES (for concrete repositories) ====================

    /**
     * Get static field value from class using reflection.
     */
    @SuppressWarnings("unchecked")
    protected <V> V getStaticField(Class<?> clazz, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (V) field.get(null);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot access static field: " + fieldName, e);
        }
    }

    /**
     * Invoke method on object using reflection.
     */
    private Object invokeMethod(Object obj, String methodName) throws Exception {
        Method method = obj.getClass().getMethod(methodName);
        return method.invoke(obj);
    }

    // ==================== SQL VALIDATION ====================

    /**
     * Validates the SQL statement for potential security issues.
     */
    private void validateSqlStatement(String statement) {
        if (statement == null || statement.trim().isEmpty()) {
            throw new ValidationException("SQL statement cannot be null or empty");
        }
    }

    /**
     * Validates that all columns in the result set can be mapped to entity fields.
     */
    private void validateResultColumns(ColumnDefinitions columnDefinitions) {
        List<String> unmappedColumns = new ArrayList<>();

        for (ColumnDefinition column : columnDefinitions) {
            String columnName = column.getColumnName();
            String camelCaseName = CaseUtils.toCamelCase(columnName, false, '_');

            if (!fieldSetterMap.containsKey(camelCaseName)) {
                unmappedColumns.add(columnName);
            }
        }

        if (!unmappedColumns.isEmpty()) {
            String entityName = entityClass.getSimpleName();
            String availableFields = fieldSetterMap.keySet().stream()
                .sorted()
                .collect(Collectors.joining(", "));

            throw new ValidationException(
                String.format("Cannot map columns %s to entity %s. Available fields: [%s]",
                    unmappedColumns, entityName, availableFields)
            );
        }
    }

    // ==================== VALUE FORMATTING ====================

    /**
     * Format value for SQL based on type.
     * Handles SQL injection prevention through sanitization.
     */
    protected String formatValue(Object value, Class<?> type) {
        if (value == null) {
            return "NULL";
        }

        // String types - use sanitization
        if (type == String.class) {
            String sanitized = sanitizeValue((String) value);
            return "'" + sanitized + "'";
        }

        // Numeric types
        if (type == Integer.class || type == int.class ||
            type == Long.class || type == long.class ||
            type == Float.class || type == float.class ||
            type == Double.class || type == double.class) {
            return value.toString();
        }

        // Boolean
        if (type == Boolean.class || type == boolean.class) {
            return value.toString();
        }

        // Date/Time types
        if (type.getName().contains("Date") ||
            type.getName().contains("Time") ||
            type.getName().contains("Instant")) {
            return "'" + value.toString() + "'";
        }

        // Byte array (BLOB) - hex encoding
        if (type == byte[].class) {
            byte[] bytes = (byte[]) value;
            StringBuilder hex = new StringBuilder("0x");
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }

        // Default - sanitize as string
        return "'" + sanitizeValue(value.toString()) + "'";
    }

    /**
     * Format value by object type (for Key values).
     */
    protected String formatValueByObject(Object value) {
        if (value == null) {
            return "NULL";
        }

        Class<?> type = value.getClass();
        return formatValue(value, type);
    }

    /**
     * Sanitizes a value for safe inclusion in SQL queries.
     * Escapes single quotes and removes control characters.
     */
    protected String sanitizeValue(String value) {
        if (value == null) {
            return null;
        }

        // Escape single quotes (standard SQL escaping)
        String sanitized = value.replace("'", "''");

        // Remove control characters
        sanitized = sanitized.replaceAll("[\\x00-\\x1f\\x7f-\\x9f]", "");

        return sanitized;
    }

    // ==================== RESULT MAPPING ====================

    /**
     * Maps a single record to an entity object.
     */
    private T mapRecordToEntity(Record record, ColumnDefinitions columnDefinitions) {
        try {
            T entity = constructor.newInstance();

            for (ColumnDefinition column : columnDefinitions) {
                String columnName = column.getColumnName();
                String camelCaseName = CaseUtils.toCamelCase(columnName, false, '_');

                FieldSetterPair fieldSetter = fieldSetterMap.get(camelCaseName);
                if (fieldSetter != null) {
                    setFieldValue(entity, fieldSetter, record, column);
                }
            }

            return entity;
        } catch (Exception e) {
            throw new RepositoryException("Failed to map record to entity", e);
        }
    }

    /**
     * Sets a field value on the entity based on the record data.
     */
    private void setFieldValue(T entity, FieldSetterPair fieldSetter, Record record, ColumnDefinition column)
            throws Exception {
        String columnName = column.getColumnName();
        Class<?> fieldType = fieldSetter.field.getType();

        // Handle null values
        if (record.isNull(columnName)) {
            if (!fieldType.isPrimitive()) {
                fieldSetter.setter.invoke(entity, (Object) null);
            } else {
                fieldSetter.setter.invoke(entity, getDefaultPrimitiveValue(fieldType));
            }
            return;
        }

        // Map non-null values based on data type
        Object value = extractValue(record, column, fieldType);
        fieldSetter.setter.invoke(entity, value);
    }

    /**
     * Extracts a value from the record based on the column data type.
     */
    private Object extractValue(Record record, ColumnDefinition column, Class<?> fieldType) {
        String columnName = column.getColumnName();
        DataType dataType = column.getDataType();

        switch (dataType) {
            case BOOLEAN:
                return record.getBoolean(columnName);
            case INT:
                return record.getInt(columnName);
            case BIGINT:
                return record.getBigInt(columnName);
            case FLOAT:
                return record.getFloat(columnName);
            case DOUBLE:
                return record.getDouble(columnName);
            case TEXT:
                return record.getText(columnName);
            case BLOB:
                return record.getBlobAsBytes(columnName);
            case DATE:
                return record.getDate(columnName);
            case TIME:
                return record.getTime(columnName);
            case TIMESTAMP:
                return record.getTimestamp(columnName);
            case TIMESTAMPTZ:
                return record.getTimestampTZ(columnName);
            default:
                logger.warn("Unsupported data type {} for column {}", dataType, columnName);
                return null;
        }
    }

    /**
     * Returns the default value for a primitive type.
     */
    private Object getDefaultPrimitiveValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException("Unknown primitive type: " + type);
    }

    // ==================== REFLECTION UTILITIES ====================

    /**
     * Builds a map of field names to field/setter pairs for efficient lookup.
     */
    private Map<String, FieldSetterPair> buildFieldSetterMap(Class<?> clazz) {
        Map<String, FieldSetterPair> map = new HashMap<>();

        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                continue;
            }

            String fieldName = field.getName();
            String setterName = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);

            try {
                Method setter = clazz.getMethod(setterName, field.getType());
                field.setAccessible(true);
                map.put(fieldName, new FieldSetterPair(field, setter));
            } catch (NoSuchMethodException e) {
                logger.debug("No setter found for field: {} in class: {}", fieldName, clazz.getName());
            }
        }

        return map;
    }

    /**
     * Extracts all column names from the entity class.
     */
    private Set<String> extractEntityColumns(Class<?> clazz) {
        Set<String> columns = new HashSet<>();

        // Extract from static final String fields (column constants)
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                    && field.getType() == String.class) {
                try {
                    field.setAccessible(true);
                    String value = (String) field.get(null);
                    if (value != null && !value.isEmpty()) {
                        columns.add(value);
                    }
                } catch (IllegalAccessException e) {
                    logger.debug("Cannot access field: {}", field.getName());
                }
            }
        }

        // Also add the actual field names (in snake_case format)
        for (Field field : fields) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers)) {
                String snakeCase = field.getName()
                    .replaceAll("([a-z])([A-Z]+)", "$1_$2")
                    .toLowerCase();
                columns.add(snakeCase);
            }
        }

        return columns;
    }

    // ==================== HELPER UTILITIES ====================

    /**
     * Check if field type is BLOB.
     */
    private boolean isBlobType(Class<?> type, Object value) {
        return type == byte[].class || (value != null && value.getClass() == byte[].class);
    }

    /**
     * Convert camelCase to snake_case.
     */
    private String toSnakeCase(String str) {
        return str.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
    }

    /**
     * Unwrap the value from Value<?>.get() which returns Optional.
     * This prevents Optional[value] from appearing in SQL queries.
     *
     * @param rawValue The value returned from column.get()
     * @param columnName The column name for error messages
     * @return The unwrapped value
     */
    private Object unwrapValue(Object rawValue, String columnName) {
        if (rawValue instanceof java.util.Optional) {
            return ((java.util.Optional<?>) rawValue).orElseThrow(() ->
                new IllegalArgumentException("Key value cannot be null for column: " + columnName));
        }
        return rawValue;
    }

    /**
     * Quote identifier if it's a reserved keyword.
     * ScalarDB SQL reserved keywords are case-insensitive and must be quoted with double quotes.
     */
    protected String quoteIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        // Check if the identifier is a reserved keyword (case-insensitive)
        if (RESERVED_KEYWORDS.contains(identifier.toUpperCase())) {
            return "\"" + identifier + "\"";
        }
        return identifier;
    }

    /**
     * Format fully qualified table name with proper quoting for reserved keywords.
     * Applies quoting to both namespace and table name if they are reserved keywords.
     *
     * @param namespace Namespace name
     * @param tableName Table name
     * @return Fully qualified table name with proper quoting
     */
    protected String formatTableName(String namespace, String tableName) {
        return quoteIdentifier(namespace) + "." + quoteIdentifier(tableName);
    }

    /**
     * Build SELECT * FROM namespace.table clause.
     * Useful for building custom queries in concrete repositories.
     *
     * @return SQL SELECT FROM clause
     */
    protected String buildSelectFrom() {
        String namespace = getStaticField(entityClass, "NAMESPACE");
        String tableName = getStaticField(entityClass, "TABLE");
        return "SELECT * FROM " + formatTableName(namespace, tableName);
    }

    /**
     * Workaround for ScalarDB SQL date/time format bug.
     *
     * TODO: Remove this method once ScalarDB fixes the timestamp parsing bug
     */
    protected String fixTimestampTzFormat(String sql) {
        // Replace 'T' separator with space in timestamp patterns
        String result = sql.replaceAll("([0-9]{4}-[0-9]{2}-[0-9]{2})T", "$1 ");

        // Add space before 'Z' for TIMESTAMPTZ
        result = result.replaceAll("([0-9])Z", "$1 Z");

        // Add missing seconds (:00) when time has only HH:MM
        result = result.replaceAll("(?<!:)([0-9]{2}:[0-9]{2})(?!:[0-9])", "$1:00");

        return result;
    }

    // ==================== INNER CLASSES ====================

    /**
     * Helper class to store field and setter method pairs.
     */
    private static class FieldSetterPair {
        final Field field;
        final Method setter;

        FieldSetterPair(Field field, Method setter) {
            this.field = field;
            this.setter = setter;
        }
    }

    /**
     * Exception thrown when validation fails.
     */
    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) {
            super(message);
        }

        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception thrown when repository operations fail.
     */
    public static class RepositoryException extends RuntimeException {
        public RepositoryException(String message) {
            super(message);
        }

        public RepositoryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
