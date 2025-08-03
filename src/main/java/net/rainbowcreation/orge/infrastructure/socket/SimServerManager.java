package net.rainbowcreation.orge.infrastructure.socket;

import net.minecraft.server.MinecraftServer;
import net.rainbowcreation.orge.Orge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class SimServerManager {
    private static Process simulationProcess;
    private static Path extractedExePath;

    public static void startServer(MinecraftServer server) {
        String resourcePath = "data/orge/server/server.exe";

        // Step 2: Extract the executable to a temporary location
        try (InputStream inputStream = Orge.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                sendMessage("§c[Sim] Error: C++ server executable not found in resources at: " + resourcePath);
                return;
            }

            // Create a temporary file to store the executable
            extractedExePath = Files.createTempFile("simserver", ".exe");

            // Copy the contents of the resource stream to the temporary file
            Files.copy(inputStream, extractedExePath, StandardCopyOption.REPLACE_EXISTING);

            // Step 3: Ensure the file has execute permissions
            File extractedFile = extractedExePath.toFile();
            if (!extractedFile.setExecutable(true)) {
                sendMessage("§c[Sim] Error: Failed to set executable permissions on C++ server.");
                return;
            }

            // Step 4: Run the extracted file using ProcessBuilder
            ProcessBuilder pb = new ProcessBuilder(extractedExePath.toString());
            pb.directory(extractedFile.getParentFile());
            pb.inheritIO();

            simulationProcess = pb.start();
            sendMessage("§a[Sim] Started C++ simulation server. PID: " + simulationProcess.pid());
        } catch (IOException e) {
            sendMessage("§c[Sim] Failed to start C++ server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void stopServer(MinecraftServer server) {
        if (simulationProcess != null && simulationProcess.isAlive()) {
           sendMessage("§a[Sim] Shutting down C++ simulation server.");

            // Attempt to gracefully terminate the process
            simulationProcess.destroy();

            // Wait for the process to exit, with a timeout
            try {
                boolean exited = simulationProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (exited) {
                    sendMessage("§a[Sim] C++ server shut down successfully.");
                } else {
                    // Forcefully terminate if it didn't exit gracefully
                    simulationProcess.destroyForcibly();
                    sendMessage("§c[Sim] C++ server did not shut down gracefully. Forcibly terminated.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore the interrupted status
                simulationProcess.destroyForcibly();
            }
        }
    }

    private static void sendMessage(String text) {
        Orge.LOGGER.info(text);
    }
}