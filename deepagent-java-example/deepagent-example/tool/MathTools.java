/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.tool;

import com.openjiuwen.core.foundation.tool.annotation.ToolDefinition;

/**
 * 加减乘除四个工具，使用 @ToolDefinition 注解定义。
 * 通过 AnnotatedToolFactory.scan() 自动扫描并转换为 LocalFunction。
 */
public class MathTools {

    @ToolDefinition(name = "add", description = "加法工具，计算两个数的和 a + b")
    public String add(double a, double b) {
        double result = a + b;
        if (result == (long) result) {
            return String.valueOf((long) result);
        }
        return String.valueOf(result);
    }

    @ToolDefinition(name = "subtract", description = "减法工具，计算两个数的差 a - b")
    public String subtract(double a, double b) {
        double result = a - b;
        if (result == (long) result) {
            return String.valueOf((long) result);
        }
        return String.valueOf(result);
    }

    @ToolDefinition(name = "multiply", description = "乘法工具，计算两个数的积 a * b")
    public String multiply(double a, double b) {
        double result = a * b;
        if (result == (long) result) {
            return String.valueOf((long) result);
        }
        return String.valueOf(result);
    }

    @ToolDefinition(name = "divide", description = "除法工具，计算两个数的商 a / b，除数不能为0")
    public String divide(double a, double b) {
        if (b == 0.0) {
            return "错误: 除数不能为 0";
        }
        double result = a / b;
        if (result == (long) result) {
            return String.valueOf((long) result);
        }
        return String.valueOf(result);
    }
}
