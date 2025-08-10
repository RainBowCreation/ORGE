package net.rainbowcreation.orge.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.rainbowcreation.orge.Orge;

import java.io.FileWriter;
import java.io.IOException;

public class JsonHelper {
    private static final Gson gson = Orge.GSON;
    public static void saveJsonObjectToFile(JsonObject jsonObject, String filePath) {
        // Use GsonBuilder to create a Gson instance that pretty prints the output.
        // This makes the JSON file human-readable.
        try (FileWriter writer = new FileWriter(filePath)) {
            // Write the JsonObject to the file.
            gson.toJson(jsonObject, writer);
            System.out.println("Block state registry successfully saved to " + filePath);
        } catch (IOException e) {
            System.err.println("An error occurred while writing the JSON file: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
