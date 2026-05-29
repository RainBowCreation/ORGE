package net.rainbowcreation.orge.infrastructure.socket;

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

    public static void startServer() {
        String resourcePath = "data/orge/exe/EchoServer.exe";

        // Step 2: Extract the executable to a temporary location
        try (InputStream inputStream = Orge.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                logMessage("§c[Sim] Error: C++ server executable not found in resources at: " + resourcePath);
                return;
            }

            // Create a temporary file to store the executable
            extractedExePath = Files.createTempFile("Orge Echo Server ", ".exe");

            // Copy the contents of the resource stream to the temporary file
            Files.copy(inputStream, extractedExePath, StandardCopyOption.REPLACE_EXISTING);

            // Step 3: Ensure the file has execute permissions
            File extractedFile = extractedExePath.toFile();
            if (!extractedFile.setExecutable(true)) {
                logMessage("§c[Sim] Error: Failed to set executable permissions on C++ server.");
                return;
            }

            // Step 4: Run the extracted file using ProcessBuilder
            ProcessBuilder pb = new ProcessBuilder(extractedExePath.toString());
            pb.directory(extractedFile.getParentFile());
            pb.inheritIO();

            simulationProcess = pb.start();
            logMessage("§a[Sim] Started C++ simulation server. PID: " + simulationProcess.pid());
        } catch (IOException e) {
            logMessage("§c[Sim] Failed to start C++ server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void stopServer() {
        if (simulationProcess != null && simulationProcess.isAlive()) {
           logMessage("§a[Sim] Shutting down C++ simulation server.");

            // Attempt to gracefully terminate the process
            simulationProcess.destroy();

            // Wait for the process to exit, with a timeout
            try {
                boolean exited = simulationProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (exited) {
                    logMessage("§a[Sim] C++ server shut down successfully.");
                } else {
                    // Forcefully terminate if it didn't exit gracefully
                    simulationProcess.destroyForcibly();
                    logMessage("§c[Sim] C++ server did not shut down gracefully. Forcibly terminated.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore the interrupted status
                simulationProcess.destroyForcibly();
            }
        }
    }

    private static void logMessage(String text) {
        Orge.LOGGER.info(text);
    }
}