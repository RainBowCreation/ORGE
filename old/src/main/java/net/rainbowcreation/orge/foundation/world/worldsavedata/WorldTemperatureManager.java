package net.rainbowcreation.orge.foundation.world.worldsavedata;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

public class WorldTemperatureManager {
    public class BlockData {
        private int last_tick_update;
        private float temperature_kelvin;
        private float mass_g;

        public BlockData(float temperature_kelvin, float mass_g) {
            this.last_tick_update = last_tick_update;
            this.temperature_kelvin = temperature_kelvin;
            this.mass_g = mass_g;
        }
    }

    private Map<BlockPos, Float> blockTemperatures = new HashMap<>();

}