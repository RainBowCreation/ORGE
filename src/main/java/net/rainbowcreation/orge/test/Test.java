package net.rainbowcreation.orge.test;

import net.rainbowcreation.orge.util.FunctionTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Test {
    private static final Logger log = LoggerFactory.getLogger(Test.class);

    public static void main(String[] args) throws Exception {
        ElementDataLoader loader = new ElementDataLoader("XEEYBQQBJWHFJM-UHFFFAOYSA-N");
        long total_solid = 0;
        int count_solid = 0;
        long total_liquid = 0;
        int count_liquid = 0;
        long total_gas = 0;
        int count_gas = 0;

        long total_solid_hook = 0;
        long total_liquid_hook = 0;
        long total_gas_hook = 0;

        for (double temp = 298; temp<6000; temp+=1) {
            //System.out.println("At: " + temp + "k");
            long start = System.nanoTime();
            String state = loader.getStateForTemperature(temp);
            long elapsedState = System.nanoTime() - start;
            FunctionTimer.Result<String> state_result = FunctionTimer.measure(loader::getStateForTemperature, temp);

            switch (state) {
                case "solid":
                    count_solid++;
                    total_solid += elapsedState;
                    total_solid_hook += state_result.elapsedNano;
                    break;
                case "liquid":
                    count_liquid++;
                    total_liquid += elapsedState;
                    total_liquid_hook += state_result.elapsedNano;
                    break;
                case "gas":
                    count_gas++;
                    total_gas += elapsedState;
                    total_gas_hook += state_result.elapsedNano;
                    break;
            }

            start = System.nanoTime();
            double heatCap = loader.getHeatCapacity(temp);
            long elapsedHeat = System.nanoTime() - start;
            FunctionTimer.Result<Double> heatCap_result = FunctionTimer.measure(loader::getHeatCapacity, temp);

            switch (state) {
                case "solid":
                    count_solid++;
                    total_solid += elapsedHeat;
                    total_solid_hook += heatCap_result.elapsedNano;
                    break;
                case "liquid":
                    count_liquid++;
                    total_liquid += elapsedHeat;
                    total_liquid_hook += heatCap_result.elapsedNano;
                    break;
                case "gas":
                    count_gas++;
                    total_gas += elapsedHeat;
                    total_gas_hook += heatCap_result.elapsedNano;
                    break;
            }
            //System.out.println(" |_ " + state + " (" + elapsedState + " ns)");
            //System.out.println(" |_ " + heatCap + " (" + elapsedHeat + " ns)");
        }

        System.out.println("Solid: " + count_solid + " (" + total_solid + "ns) average: " + (total_solid / count_solid) + "ns");
        System.out.println("Liquid: " + count_liquid + " (" + total_liquid + "ns) average: " + (total_liquid / count_liquid) + "ns");
        System.out.println("Gas: " + count_gas + " (" + total_gas + "ns) average: " + (total_gas / count_gas) + "ns");

        System.out.println("Solid (hook): " + count_solid + " (" + total_solid_hook + "ns) average: " + (total_solid_hook / count_solid) + "ns");
        System.out.println("Liquid (hook): " + count_liquid + " (" + total_liquid_hook + "ns) average: " + (total_liquid_hook / count_liquid) + "ns");
        System.out.println("Gas (hook): " + count_gas + " (" + total_gas_hook + "ns) average: " + (total_gas_hook / count_gas) + "ns");
    }
}