package com.nl2sql.core.executor;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ExcelExportService {
    
    /**
     * 将查询结果导出为Excel
     */
    public byte[] exportToExcel(List<Map<String, Object>> data, String fileName) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("数据为空，无法导出");
        }
        
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            
            Sheet sheet = workbook.createSheet("查询结果");
            
            // 创建表头样式
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 12);
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            
            // 创建数据样式
            CellStyle dataStyle = workbook.createCellStyle();
            dataStyle.setBorderBottom(BorderStyle.THIN);
            dataStyle.setBorderTop(BorderStyle.THIN);
            dataStyle.setBorderLeft(BorderStyle.THIN);
            dataStyle.setBorderRight(BorderStyle.THIN);
            
            // 获取列名
            List<String> columns = new java.util.ArrayList<>(data.get(0).keySet());
            
            // 创建表头行
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns.get(i));
                cell.setCellStyle(headerStyle);
                // 自动调整列宽
                sheet.setColumnWidth(i, 20 * 256);
            }
            
            // 填充数据
            int rowNum = 1;
            for (Map<String, Object> row : data) {
                Row excelRow = sheet.createRow(rowNum++);
                
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = excelRow.createCell(i);
                    Object value = row.get(columns.get(i));
                    
                    if (value == null) {
                        cell.setCellValue("");
                    } else if (value instanceof Number) {
                        cell.setCellValue(((Number) value).doubleValue());
                    } else if (value instanceof java.util.Date) {
                        cell.setCellValue(value.toString());
                    } else {
                        cell.setCellValue(value.toString());
                    }
                    
                    cell.setCellStyle(dataStyle);
                }
            }
            
            // 冻结表头
            sheet.createFreezePane(0, 1);
            
            // 自动调整所有列宽
            for (int i = 0; i < columns.size(); i++) {
                sheet.autoSizeColumn(i);
                // 设置最大宽度
                if (sheet.getColumnWidth(i) > 50 * 256) {
                    sheet.setColumnWidth(i, 50 * 256);
                }
            }
            
            workbook.write(outputStream);
            
            log.info("Excel导出成功: 文件名={}, 行数={}", fileName, data.size());
            
            return outputStream.toByteArray();
            
        } catch (IOException e) {
            log.error("Excel导出失败", e);
            throw new RuntimeException("Excel导出失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 生成文件名
     */
    public String generateFileName(String prefix) {
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        return prefix + "_" + timestamp + ".xlsx";
    }
}
