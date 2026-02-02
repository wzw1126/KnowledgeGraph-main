package com.wzw.knowledge.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI响应验证工具
 * 用于检测AI生成的回答是否篡改了原始文档中的数值和单位
 * 
 * @author wzw
 * @version 1.0
 */
@Slf4j
public class ResponseValidator {

    /**
     * 匹配数值和单位的正则表达式
     * 匹配格式：数字 + 可选空格 + 单位
     * 例如：25 g、30mg、1.5千克、100 毫升
     */
    private static final Pattern NUMBER_UNIT_PATTERN = Pattern.compile(
        "(\\d+(?:\\.\\d+)?(?:\\s*[～~-]\\s*\\d+(?:\\.\\d+)?)?)\\s*([a-zA-Z/]+|克|毫克|千克|公斤|升|毫升|天|日|小时|分钟)"
    );

    /**
     * 验证AI响应中的数值是否与参考内容一致
     * 
     * @param referenceContent 参考文档内容
     * @param aiResponse AI生成的响应
     * @return 验证结果
     */
    public static ValidationResult validate(String referenceContent, String aiResponse) {
        if (referenceContent == null || aiResponse == null) {
            return ValidationResult.valid();
        }

        // 提取参考内容中的所有数值-单位对
        List<NumberUnit> referenceNumbers = extractNumberUnits(referenceContent);
        
        // 提取AI响应中的所有数值-单位对
        List<NumberUnit> responseNumbers = extractNumberUnits(aiResponse);

        // 如果参考内容有数值，但AI响应中没有对应的数值，需要警告
        if (!referenceNumbers.isEmpty() && responseNumbers.isEmpty()) {
            log.warn("AI响应缺失数值信息 - 参考内容包含{}个数值，但响应中未找到", referenceNumbers.size());
            return ValidationResult.warning("AI响应可能遗漏了数值信息");
        }

        // 检查是否存在可疑的单位转换
        List<String> warnings = new ArrayList<>();
        for (NumberUnit refNum : referenceNumbers) {
            for (NumberUnit respNum : responseNumbers) {
                // 检测可疑的单位转换（如克->千克）
                if (isSuspiciousConversion(refNum, respNum)) {
                    String warning = String.format(
                        "检测到可疑的单位转换: 参考内容='%s'，AI响应='%s'",
                        refNum.original, respNum.original
                    );
                    warnings.add(warning);
                    log.warn(warning);
                }
            }
        }

        if (!warnings.isEmpty()) {
            return ValidationResult.invalid(String.join("; ", warnings));
        }

        return ValidationResult.valid();
    }

    /**
     * 从文本中提取所有数值-单位对
     */
    private static List<NumberUnit> extractNumberUnits(String text) {
        List<NumberUnit> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_UNIT_PATTERN.matcher(text);
        
        while (matcher.find()) {
            String number = matcher.group(1);
            String unit = matcher.group(2);
            numbers.add(new NumberUnit(number, unit, matcher.group(0)));
        }
        
        return numbers;
    }

    /**
     * 检测是否为可疑的单位转换
     * 例如：25克 -> 0.025千克，这种转换虽然数学上正确，但在RAG场景中应该保持原始单位
     */
    private static boolean isSuspiciousConversion(NumberUnit ref, NumberUnit resp) {
        // 检查是否为质量单位之间的转换
        if (isMassUnit(ref.unit) && isMassUnit(resp.unit) && !ref.unit.equals(resp.unit)) {
            // 如果原文是克(g)，但响应中变成了千克/公斤(kg)，这是可疑的
            if (isGramUnit(ref.unit) && isKilogramUnit(resp.unit)) {
                log.warn("检测到克->千克/公斤的转换: {} -> {}", ref.original, resp.original);
                return true;
            }
            // 如果原文是毫克(mg)，但响应中变成了克(g)或千克(kg)，也是可疑的
            if (isMilligramUnit(ref.unit) && (isGramUnit(resp.unit) || isKilogramUnit(resp.unit))) {
                log.warn("检测到毫克->克/千克的转换: {} -> {}", ref.original, resp.original);
                return true;
            }
        }
        
        return false;
    }

    /**
     * 判断是否为质量单位
     */
    private static boolean isMassUnit(String unit) {
        return unit.matches(".*?(g|mg|kg|克|毫克|千克|公斤).*");
    }

    /**
     * 判断是否为克单位
     */
    private static boolean isGramUnit(String unit) {
        return unit.matches(".*?(g[^k]|克).*") || unit.equals("g");
    }

    /**
     * 判断是否为千克/公斤单位
     */
    private static boolean isKilogramUnit(String unit) {
        return unit.matches(".*?(kg|千克|公斤).*");
    }

    /**
     * 判断是否为毫克单位
     */
    private static boolean isMilligramUnit(String unit) {
        return unit.matches(".*?(mg|毫克).*");
    }

    /**
     * 数值-单位对的内部表示
     */
    private static class NumberUnit {
        String number;   // 数值部分（可能包含范围，如"25-30"）
        String unit;     // 单位部分
        String original; // 原始文本

        NumberUnit(String number, String unit, String original) {
            this.number = number;
            this.unit = unit;
            this.original = original;
        }
    }

    /**
     * 验证结果
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String message;
        private final boolean isWarning;

        private ValidationResult(boolean valid, String message, boolean isWarning) {
            this.valid = valid;
            this.message = message;
            this.isWarning = isWarning;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null, false);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message, false);
        }

        public static ValidationResult warning(String message) {
            return new ValidationResult(true, message, true);
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }

        public boolean isWarning() {
            return isWarning;
        }
    }
}
