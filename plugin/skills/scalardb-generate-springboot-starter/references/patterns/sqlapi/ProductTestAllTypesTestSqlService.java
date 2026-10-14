package com.example.demo_sql_api.service;

import com.example.demo_sql_api.model.ProductTestAllTypesTest;
import com.example.demo_sql_api.dto.ProductTestAllTypesTestDto;
import com.example.demo_sql_api.dto.ResponseStatusDto;
import com.example.demo_sql_api.exception.CustomException;
import com.example.demo_sql_api.mapper.ProductTestAllTypesTestMapper;
import com.example.demo_sql_api.repository.ProductTestAllTypesTestSqlRepository;
import com.scalar.db.sql.SqlSession;
import com.scalar.db.sql.SqlSessionFactory;
import com.scalar.db.sql.exception.AuthenticationException;
import com.scalar.db.sql.exception.AuthorizationException;
import com.scalar.db.sql.exception.SqlException;
import com.scalar.db.sql.exception.UnknownTransactionStatusException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    private void handleSqlSessionException(Exception e, SqlSession sqlSession) {
        if (e instanceof UnknownTransactionStatusException) {
            // The commit may or may not have been applied, so do NOT roll back:
            // a rollback cannot undo a commit that already succeeded. Report the
            // transaction ID instead and leave the outcome to manual verification
            // (this exception is never auto-retried; the API returns 9200).
            String transactionId = ((UnknownTransactionStatusException) e).getTransactionId()
                    .or(() -> sqlSession != null ? sqlSession.getTransactionId() : Optional.empty())
                    .orElse("unknown");
            String message = "Transaction status is unknown (transaction ID: " + transactionId
                    + "). The commit may or may not have been applied; verify the data manually before retrying.";
            log.error(message, e);
            throw new CustomException(message, e, 9200);
        }
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
        // A CustomException already carries a code determined closer to the
        // source (e.g. repository not-found) - preserve it.
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof CustomException && ((CustomException) c).getErrorCode() != null) {
                return ((CustomException) c).getErrorCode();
            }
        }
        // UnknownTransactionStatusException / AuthenticationException /
        // AuthorizationException are subclasses of SqlException - check them
        // before the parent type; scan the whole cause chain so wrapped
        // exceptions are still classified.
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof UnknownTransactionStatusException) return 9200;
            if (c instanceof AuthenticationException
                    || c instanceof AuthorizationException) return 9600;
        }
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SqlException) return 9300;
        }
        if (e instanceof RuntimeException) return 9400;
        return 9500;
    }
}
