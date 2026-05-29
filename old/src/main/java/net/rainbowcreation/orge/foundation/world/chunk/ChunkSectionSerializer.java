package net.rainbowcreation.orge.foundation.world.chunk;

import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.rainbowcreation.orge.Orge;
import net.rainbowcreation.orge.util.JsonHelper;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.*;

public class ChunkSectionSerializer {
    private static final Logger logger = Orge.LOGGER;

    public static byte[] serializeChunkSections(LevelChunk chunk, double x, double y, double z) throws IOException {
        return serializeChunkSections(chunk);
    }

    public static byte[] serializeChunkSections(LevelChunk chunk) throws IOException {
        // Use a FriendlyByteBuf which is the standard for Minecraft networking
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        JsonObject js = new JsonObject();

        LevelChunkSection[] sections = chunk.getSections();
        buffer.writeInt(sections.length);
        js.addProperty("length", sections.length);

        int minBuildHeight = chunk.getMinBuildHeight();

        for (int i = 0; i < sections.length; i++) {
            JsonObject jsec = new JsonObject();

            LevelChunkSection section = sections[i];

            int sectionY = minBuildHeight + (i * 16);
            buffer.writeInt(sectionY);
            jsec.addProperty("y", sectionY);

            // Write a flag indicating if the section is empty.
            // This is a simple, optional optimization.
            boolean isEmpty = section.hasOnlyAir();
            jsec.addProperty("isEmpty", isEmpty);
            buffer.writeBoolean(isEmpty);

            if (!isEmpty) {
                // Get the PalettedContainer for block states
                PalettedContainer<BlockState> blockStates = section.getStates();
                jsec.addProperty("size", blockStates.getSerializedSize());

                List<String> blocknames = new ArrayList<>();
                Set<String> blocks = new HashSet<>();
                JsonObject jsc = new JsonObject();
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            String name = blockStates.get(x, y, z).getBlock().getName().getString();
                            blocknames.add(name);
                            blocks.add(name);
                        }
                    }
                }
                JsonObject jsd = new JsonObject();
                for (String b: blocks) {
                    int c = 0;
                    for (String s: blocknames) {
                        if (Objects.equals(s, b)){
                            c++;
                        }
                    }
                    jsd.addProperty(b, c);
                }
                jsc.addProperty("count", Orge.GSON.toJsonTree(jsd).toString());
                jsc.addProperty("data", Orge.GSON.toJsonTree(blocknames).toString());
                jsc.addProperty("unique", Orge.GSON.toJsonTree(blocks).toString());

                jsec.add("name", jsc);

                // Use the official write method to serialize the data for us.
                blockStates.write(buffer);
                FriendlyByteBuf jbuff = new FriendlyByteBuf(Unpooled.buffer());
                blockStates.write(jbuff);
                byte[] jr = new byte[jbuff.readableBytes()];
                jbuff.readBytes(jr);

                jsec.addProperty("val", Orge.GSON.toJsonTree(jr).toString());
            }
            js.add(String.valueOf(i), jsec);
        }

        // Extract the byte array from the buffer
        byte[] result = new byte[buffer.readableBytes()];
        buffer.readBytes(result);

        JsonHelper.saveJsonObjectToFile(js, "chunk/" + chunk.getPos().x + "_" +  chunk.getPos().z + ".json");
        return result;
    }
}