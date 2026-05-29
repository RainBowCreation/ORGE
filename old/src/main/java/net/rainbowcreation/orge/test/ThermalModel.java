package net.rainbowcreation.orge.test;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.SimpleBindings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ThermalModel {
    private final JSONMapper.MaterialData data;
    private final ScriptEngine engine;

    public ThermalModel(JSONMapper.MaterialData data) {
        this.data = data;
        this.engine = new ScriptEngineManager().getEngineByName("JavaScript");
    }

    public String getState(double t) {
        for (Map.Entry<String, JSONMapper.StateData> entry : data.state.entrySet()) {
            List<JSONMapper.TempRange> ranges = entry.getValue().temperature_k;
            for (JSONMapper.TempRange range : ranges) {
                if (t >= range.min && t < range.max) {
                    return entry.getKey();
                }
            }
        }
        return "unknown";
    }

    public double getHeatCapacity(double t) throws Exception {
        String state = getState(t);
        if (state.equals("unknown")) throw new IllegalArgumentException("Temperature out of range");

        JSONMapper.StateData sData = data.state.get(state);
        int index = getMatchingRangeIndex(sData.temperature_k, t);
        if (index == -1) throw new IllegalArgumentException("No temperature range matched");

        // Use the corresponding coefficient set (e.g., A[0], B[0], ...)
        Map<String, Object> vars = new HashMap<>();
        vars.put("t", t);
        vars.put("A", sData.A.get(index));
        vars.put("B", sData.B.get(index));
        vars.put("C", sData.C.get(index));
        vars.put("D", sData.D.get(index));
        vars.put("E", sData.E.get(index));
        vars.put("F", sData.F.get(index));
        vars.put("G", sData.G.get(index));
        vars.put("H", sData.H.get(index));

        String formula = sData.heat_capacity
                .replace("−", "-");  // Ensure minus is the correct character

        Object result = engine.eval(formula, new SimpleBindings(vars));
        return ((Number) result).doubleValue();
    }

    private int getMatchingRangeIndex(List<JSONMapper.TempRange> ranges, double t) {
        for (int i = 0; i < ranges.size(); i++) {
            JSONMapper.TempRange r = ranges.get(i);
            if (t >= r.min && t < r.max)
                return i;
        }
        return -1;
    }
}