package com.example.demo_java_api.mapper;

import com.example.demo_java_api.model.ProductTestAllTypesTest;
import com.example.demo_java_api.dto.ProductTestAllTypesTestDto;
import java.util.ArrayList;
import java.util.List;
import org.modelmapper.ModelMapper;

public class ProductTestAllTypesTestMapper {
    private static final ModelMapper modelMapper = new ModelMapper();

    // Convert Model to DTO
    public static ProductTestAllTypesTestDto mapToProductTestAllTypesTestDto(ProductTestAllTypesTest allTypesTest) {
        return modelMapper.map(allTypesTest, ProductTestAllTypesTestDto.class);
    }

    // Convert DTO to Model
    public static ProductTestAllTypesTest mapToProductTestAllTypesTest(ProductTestAllTypesTestDto allTypesTestDto) {
        return modelMapper.map(allTypesTestDto, ProductTestAllTypesTest.class);
    }

    // Convert Model List to DTO List
    public static List<ProductTestAllTypesTestDto> mapToProductTestAllTypesTestDtoList(List<ProductTestAllTypesTest> allTypesTestList) {
        List<ProductTestAllTypesTestDto> allTypesTestDtoList = new ArrayList<>();
        for (ProductTestAllTypesTest allTypesTest : allTypesTestList) {
            allTypesTestDtoList.add(mapToProductTestAllTypesTestDto(allTypesTest));
        }
        return allTypesTestDtoList;
    }
}