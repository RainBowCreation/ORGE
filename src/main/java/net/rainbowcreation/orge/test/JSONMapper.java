package net.rainbowcreation.orge.test;

import java.util.List;
import java.util.Map;

public class JSONMapper {
    public class MaterialData {
        public String formular;
        public double molecular_weight;
        public Map<String, StateData> state; // "solid", "liquid", "gas"
    }

    public class StateData {
        public List<TempRange> temperature_k;
        public List<Double> A, B, C, D, E, F, G, H;
        public String heat_capacity;
        public String standard_enthalpy_minus_H_298_15;
        public String standard_entropy;
    }

    public class TempRange {
        public double min;
        public double max;
    }
}