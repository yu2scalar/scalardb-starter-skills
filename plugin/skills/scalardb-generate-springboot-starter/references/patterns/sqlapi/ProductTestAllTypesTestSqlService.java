package com.example.demo_sql_api.service;

import com.example.demo_sql_api.model.ProductTestAllTypesTest;
import com.example.demo_sql_api.dto.ProductTestAllTypesTestDto;
import com.example.demo_sql_api.dto.ResponseStatusDto;
import com.example.demo_sql_api.dto.SqlCommandDto;
import com.example.demo_sql_api.exception.CustomException;
import com.example.demo_sql_api.mapper.ProductTestAllTypesTestMapper;
import com.example.demo_sql_api.repository.ProductTestAllTypesTestSqlRepository;
import com.scalar.db.sql.SqlSession;
import com.scalar.db.sql.SqlSessionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL Service class with automatic retry logic for transient failures.
 *
 * This service uses Spring Retry to automatically retry SQL operations that fail
 * due to transient exceptions like TransactionRetryableException.
 *
 * Note: UnknownTransactionStatusException requires manual verification
 * and is NOT automatically retried to avoid duplicate operations.
 */
@Slf4j
@Service
public class ProductTestAllTypesTestSqlService {
    SqlSessionFactory sqlSessionFactory;

    @Autowired
    @Qualifier("sqlApiRetryTemplate")
    private RetryTemplate sqlApiRetryTemplate;

    @Autowired
    ProductTestAllTypesTestSqlRepository allTypesTestRepository;

    public ProductTestAllTypesTestSqlService(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    // INSERT using SQL
    public ResponseStatusDto insertProductTestAllTypesTest(ProductTestAllTypesTestDto allTypesTestDto) throws CustomException {
        try {
            return sqlApiRetryTemplate.execute(context -> {
                SqlSession sqlSession = null;
                try {
                    ProductTestAllTypesTest allTypesTest = ProductTestAllTypesTestMapper.mapToProductTestAllTypesTest(allTypesTestDto);
                    sqlSession = sqlSessionFactory.createSqlSession();
                    sqlSession.begin();

                    allTypesTest = allTypesTestRepository.insert(sqlSession, allTypesTest);

                    sqlSession.commit();
                    return ResponseStatusDto.builder().code(0).message("").build();
                } catch (Exception e) {
                    handleSqlSessionException(e, sqlSession);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // UPSERT using SQL (insert or update if exists)
    public ResponseStatusDto upsertProductTestAllTypesTest(ProductTestAllTypesTestDto allTypesTestDto) throws CustomException {
        try {
            return sqlApiRetryTemplate.execute(context -> {
                SqlSession sqlSession = null;
                try {
                    ProductTestAllTypesTest allTypesTest = ProductTestAllTypesTestMapper.mapToProductTestAllTypesTest(allTypesTestDto);
                    sqlSession = sqlSessionFactory.createSqlSession();
                    sqlSession.begin();

                    allTypesTest = allTypesTestRepository.upsert(sqlSession, allTypesTest);

                    sqlSession.commit();
                    return ResponseStatusDto.builder().code(0).message("").build();
                } catch (Exception e) {
                    handleSqlSessionException(e, sqlSession);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // SELECT using SQL (get by partition and clustering key)
    public ProductTestAllTypesTestDto getProductTestAllTypesTest(ProductTestAllTypesTestDto allTypesTestDto) throws CustomException {
        try {
            return sqlApiRetryTemplate.execute(context -> {
                SqlSession sqlSession = null;
                try {
                    ProductTestAllTypesTest allTypesTest = ProductTestAllTypesTestMapper.mapToProductTestAllTypesTest(allTypesTestDto);
                    sqlSession = sqlSessionFactory.createSqlSession();
                    sqlSession.begin();

                    allTypesTest = allTypesTestRepository.get(sqlSession, allTypesTest);

                    sqlSession.commit();
                    return ProductTestAllTypesTestMapper.mapToProductTestAllTypesTestDto(allTypesTest);
                } catch (Exception e) {
                    handleSqlSessionException(e, sqlSession);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // UPDATE using SQL
    public ResponseStatusDto updateProductTestAllTypesTest(ProductTestAllTypesTestDto allTypesTestDto) throws CustomException {
        try {
            return sqlApiRetryTemplate.execute(context -> {
                SqlSession sqlSession = null;
                try {
                    ProductTestAllTypesTest allTypesTest = ProductTestAllTypesTestMapper.mapToProductTestAllTypesTest(allTypesTestDto);
                    sqlSession = sqlSessionFactory.createSqlSession();
                    sqlSession.begin();

                    allTypesTest = allTypesTestRepository.update(sqlSession, allTypesTest);

                    sqlSession.commit();
                    return ResponseStatusDto.builder().code(0).message("").build();
                } catch (Exception e) {
                    handleSqlSessionException(e, sqlSession);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // DELETE using SQL
    public ResponseStatusDto deleteProductTestAllTypesTest(ProductTestAllTypesTestDto allTypesTestDto) throws CustomException {
        try {
            return sqlApiRetryTemplate.execute(context -> {
                SqlSession sqlSession = null;
                try {
                    ProductTestAllTypesTest allTypesTest = ProductTestAllTypesTestMapper.mapToProductTestAllTypesTest(allTypesTestDto);
                    sqlSession = sqlSessionFactory.createSqlSession();
                    sqlSession.begin();

                    allTypesTestRepository.delete(sqlSession, allTypesTest);

                    sqlSession.commit();
                    return ResponseStatusDto.builder().code(0).message("").build();
                } catch (Exception e) {
                    handleSqlSessionException(e, sqlSession);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // SELECT * (scan all) using SQL
    public List<ProductTestAllTypesTestDto> getProductTestAllTypesTestListAll() throws CustomException {
        try {
            return sqlApiRetryTemplate.execute(context -> {
                SqlSession sqlSession = null;
                List<ProductTestAllTypesTest> allTypesTestList = new ArrayList<>();
                try {
                    sqlSession = sqlSessionFactory.createSqlSession();
                    sqlSession.begin();

                    allTypesTestList = allTypesTestRepository.scan(sqlSession);

                    sqlSession.commit();
                    return ProductTestAllTypesTestMapper.mapToProductTestAllTypesTestDtoList(allTypesTestList);
                } catch (Exception e) {
                    handleSqlSessionException(e, sqlSession);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // SELECT with partition key using SQL
    public List<ProductTestAllTypesTestDto> getProductTestAllTypesTestListByPk(ProductTestAllTypesTestDto allTypesTestDto) throws CustomException {
        try {
            return sqlApiRetryTemplate.execute(context -> {
                SqlSession sqlSession = null;
                List<ProductTestAllTypesTest> allTypesTestList = new ArrayList<>();
                try {
                    ProductTestAllTypesTest allTypesTest = ProductTestAllTypesTestMapper.mapToProductTestAllTypesTest(allTypesTestDto);
                    sqlSession = sqlSessionFactory.createSqlSession();
                    sqlSession.begin();

                    allTypesTestList = allTypesTestRepository.scan(sqlSession, allTypesTest);

                    sqlSession.commit();
                    return ProductTestAllTypesTestMapper.mapToProductTestAllTypesTestDtoList(allTypesTestList);
                } catch (Exception e) {
                    handleSqlSessionException(e, sqlSession);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // Generic SQL execution (for flexibility - allows custom queries)
    public List<ProductTestAllTypesTestDto> executeSQL(SqlCommandDto sqlCommandDto) throws CustomException {
        try {
            return sqlApiRetryTemplate.execute(context -> {
                SqlSession sqlSession = null;
                List<ProductTestAllTypesTest> allTypesTestList = new ArrayList<>();
                try {
                    sqlSession = sqlSessionFactory.createSqlSession();
                    sqlSession.begin();

                    allTypesTestList = allTypesTestRepository.executeQuery(sqlSession, sqlCommandDto.getSqlCommand());

                    sqlSession.commit();
                    return ProductTestAllTypesTestMapper.mapToProductTestAllTypesTestDtoList(allTypesTestList);
                } catch (Exception e) {
                    handleSqlSessionException(e, sqlSession);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    private void handleSqlSessionException(Exception e, SqlSession sqlSession) {
        log.error(e.getMessage(), e);
        if (sqlSession != null) {
            try {
                sqlSession.rollback();
            } catch (Exception ex) {
                log.error(ex.getMessage(), ex);
            }
        }
    }

    /**
     * Determine error code based on exception type.
     *
     * Note: UnknownTransactionStatusException requires manual verification
     * before retry to avoid duplicate operations. This exception is NOT
     * automatically retried.
     */
    private int determineErrorCode(Exception e) {
        // SQL-specific error codes (can be enhanced with specific SQL exceptions)
        if (e instanceof RuntimeException) return 9400;
        return 9500;
    }
}
