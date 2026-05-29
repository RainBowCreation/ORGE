package net.rainbowcreation.orge.infrastructure.data;

import io.github.fabricators_of_create.porting_lib.data.ExistingFileHelper;
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.rainbowcreation.orge.Orge;

public class OrgeDatagen implements DataGeneratorEntrypoint {
    @Override
    public void onInitializeDataGenerator(FabricDataGenerator generator) {
        ExistingFileHelper helper = ExistingFileHelper.withResourcesFromArg();
        FabricDataGenerator.Pack pack = generator.createPack();
        Orge.registrate().setupDatagen(pack, helper);
    }
}
