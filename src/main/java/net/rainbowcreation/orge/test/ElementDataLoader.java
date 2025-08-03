package net.rainbowcreation.orge.test;

import com.google.gson.*;
import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ElementDataLoader {
    private static final String BASE_PATH = "src/main/resources/data/orge/elements/data/";
    private final JsonObject root;
    private final Map<String, Double> variables;

    public ElementDataLoader(String elementId) throws IOException {
        String jsonContent = Files.readString(Path.of(BASE_PATH + elementId + ".json"));
        this.root = JsonParser.parseString(jsonContent).getAsJsonObject();
        this.variables = new HashMap<>();
    }

    public String getStateForTemperature(double tempK) {
        JsonObject states = root.getAsJsonObject("state");
        for (Map.Entry<String, JsonElement> stateEntry : states.entrySet()) {
            JsonObject state = stateEntry.getValue().getAsJsonObject();
            JsonArray tempRanges = state.getAsJsonObject("heat_table").getAsJsonArray("temperature_k");
            for (int i = 0; i < tempRanges.size(); i++) {
                JsonObject range = tempRanges.get(i).getAsJsonObject();
                double min = range.get("min").getAsDouble();
                double max = range.get("max").getAsDouble();
                if (tempK >= min && tempK < max) {
                    return stateEntry.getKey();
                }
            }
        }
        return "unknown";
    }

    public double getHeatCapacity(double tempK) {
        String stateName = getStateForTemperature(tempK);

        JsonObject state = root.getAsJsonObject("state").getAsJsonObject(stateName);
        JsonObject table = state.getAsJsonObject("heat_table");

        int index = findRangeIndex(table.getAsJsonArray("temperature_k"), tempK);
        // Set coefficients
        variables.clear();
        for (String key : Arrays.asList("A", "B", "C", "D", "E")) {
            JsonArray coeffs = table.getAsJsonArray(key);
            double value = coeffs != null && coeffs.size() > index ? 
                    coeffs.get(index).getAsDouble() : 0.0;
            variables.put(key.toLowerCase(), value);
        }
        variables.put("t", tempK/1000);

        String expression = state.get("heat_capacity").getAsString();
        expression = normalizeFormula(expression);

        return evaluateExpression(expression);
    }

    private double evaluateExpression(String expression) {
        return evaluateExpression(expression, 2 );
    }

    private double evaluateExpression(String expression, int decimal) {
        String[] terms = expression.split("\\+");
        double result = 0.0;

        for (String term : terms) {
            term = term.trim();
            if (term.isEmpty()) continue;
            
            double termResult;
            if (term.contains("*")) {
                String[] factors = term.split("\\*");
                double product = 1.0;

                for (String factor : factors) {
                    factor = factor.trim();
                    if (factor.contains("^")) {
                        String[] parts = factor.split("\\^");
                        double base = getVariableValue(parts[0].trim());
                        //double exponent = Double.parseDouble(parts[1].trim());
                        double exponent = Double.parseDouble(parts[1].trim().replaceAll("[()]", ""));
                        double powerResult = Math.pow(base, exponent);
                        product *= powerResult;
                    } else {
                        double value = getVariableValue(factor);
                        product *= value;
                    }
                }
                termResult = product;
            } else {
                termResult = getVariableValue(term);
            }
            result += termResult;
        }

        double rounded = new BigDecimal(result)
                .setScale(decimal, RoundingMode.HALF_UP)
                .doubleValue();
        return rounded;
    }

    private double getVariableValue(String variable) {
        if (variable.matches("-?\\d+(\\.\\d+)?")) {
            return Double.parseDouble(variable);
        }
        return variables.getOrDefault(variable, 0.0);
    }

    private String normalizeFormula(String expression) {
        // Replace letter variables with lowercase
        Pattern varPattern = Pattern.compile("([A-Ha-h])");
        Matcher varMatcher = varPattern.matcher(expression);
        StringBuffer sb = new StringBuffer();

        while (varMatcher.find()) {
            varMatcher.appendReplacement(sb, varMatcher.group(1).toLowerCase());
        }
        varMatcher.appendTail(sb);

        // Replace divisions like "e/t^2" with multiplication by inverse power: "e*t^-2"
        String normalized = sb.toString();
        normalized = normalized.replaceAll("([a-h])\\s*/\\s*t\\^(\\d+(\\.\\d+)?)", "$1*t^(-$2)");

        return normalized;
    }

    private int findRangeIndex(JsonArray ranges, double t) {
        for (int i = 0; i < ranges.size(); i++) {
            JsonObject range = ranges.get(i).getAsJsonObject();
            double min = range.get("min").getAsDouble();
            double max = range.get("max").getAsDouble();
            if (t >= min && t < max) {
                return i;
            }
        }
        return -1;
    }
}