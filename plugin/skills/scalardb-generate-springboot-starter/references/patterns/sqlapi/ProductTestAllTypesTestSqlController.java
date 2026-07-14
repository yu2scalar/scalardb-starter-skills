package com.example.demo_sql_api.controller;

import com.example.demo_sql_api.service.ProductTestAllTypesTestSqlService;
import com.example.demo_sql_api.dto.ProductTestAllTypesTestDto;
import com.example.demo_sql_api.dto.ApiResponse;
import com.example.demo_sql_api.dto.ResponseStatusDto;
import com.example.demo_sql_api.exception.CustomException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@RequestMapping(value = "/product-test-all-types-test-sql")
@RestController
public class ProductTestAllTypesTestSqlController {
    @Autowired
    private ProductTestAllTypesTestSqlService allTypesTestSqlService;

    // INSERT using SQL
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> insertProductTestAllTypesTest(@RequestBody ProductTestAllTypesTestDto allTypesTestDto) {
        ResponseStatusDto status = allTypesTestSqlService.insertProductTestAllTypesTest(allTypesTestDto);
        return ResponseEntity.ok(ApiResponse.fromResponseStatus(status));
    }

    // UPSERT using SQL (insert or update if exists)
    @PostMapping("/upsert")
    public ResponseEntity<ApiResponse<Void>> upsertProductTestAllTypesTest(@RequestBody ProductTestAllTypesTestDto allTypesTestDto) {
        ResponseStatusDto status = allTypesTestSqlService.upsertProductTestAllTypesTest(allTypesTestDto);
        return ResponseEntity.ok(ApiResponse.fromResponseStatus(status));
    }

    // SELECT using SQL (get by key)
    @GetMapping("/{int_value}/{bigint_value}/{float_value}")
    public ResponseEntity<ApiResponse<ProductTestAllTypesTestDto>> getProductTestAllTypesTest(@PathVariable("int_value") Integer int_value, @PathVariable("bigint_value") Long bigint_value, @PathVariable("float_value") Float float_value) {
        ProductTestAllTypesTestDto allTypesTestDto = ProductTestAllTypesTestDto.builder()
            .intValue(int_value)
                .bigintValue(bigint_value)
                .floatValue(float_value)
            .build();
        ProductTestAllTypesTestDto result = allTypesTestSqlService.getProductTestAllTypesTest(allTypesTestDto);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // UPDATE using SQL
    @PutMapping
    public ResponseEntity<ApiResponse<Void>> updateProductTestAllTypesTest(@RequestBody ProductTestAllTypesTestDto allTypesTestDto) {
        ResponseStatusDto status = allTypesTestSqlService.updateProductTestAllTypesTest(allTypesTestDto);
        return ResponseEntity.ok(ApiResponse.fromResponseStatus(status));
    }

    // DELETE using SQL
    @DeleteMapping("/{int_value}/{bigint_value}/{float_value}")
    public ResponseEntity<ApiResponse<Void>> deleteProductTestAllTypesTest(@PathVariable("int_value") Integer int_value, @PathVariable("bigint_value") Long bigint_value, @PathVariable("float_value") Float float_value) {
        ProductTestAllTypesTestDto allTypesTestDto = ProductTestAllTypesTestDto.builder()
            .intValue(int_value)
                .bigintValue(bigint_value)
                .floatValue(float_value)
            .build();
        ResponseStatusDto status = allTypesTestSqlService.deleteProductTestAllTypesTest(allTypesTestDto);
        return ResponseEntity.ok(ApiResponse.fromResponseStatus(status));
    }

    // SELECT with partition key using SQL (returns List)
    @GetMapping("/scan-by-pk/{int_value}")
    public ResponseEntity<ApiResponse<List<ProductTestAllTypesTestDto>>> getProductTestAllTypesTestByPk(@PathVariable("int_value") Integer int_value) {
        ProductTestAllTypesTestDto allTypesTestDto = ProductTestAllTypesTestDto.builder()
            .intValue(int_value)
            .build();
        List<ProductTestAllTypesTestDto> result = allTypesTestSqlService.getProductTestAllTypesTestListByPk(allTypesTestDto);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // SELECT * (scan all) using SQL
    @GetMapping("/scan-all")
    public ResponseEntity<ApiResponse<List<ProductTestAllTypesTestDto>>> getProductTestAllTypesTestListAll() {
        List<ProductTestAllTypesTestDto> result = allTypesTestSqlService.getProductTestAllTypesTestListAll();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @ExceptionHandler(value = CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleScalarDbException(CustomException ex) {
        ApiResponse<Void> errorResponse = ApiResponse.error(ex.getErrorCode(), ex.getMessage());
        return switch (ex.getErrorCode()) {
            case 9100, 9400 -> new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
            case 9200, 9300 -> new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
            default -> new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        };
    }
}
