package net.rainbowcreation.orge.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.rainbowcreation.orge.Orge;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

public class GraphExporter {
    private static final Gson GSON = new Gson();

    public static final String GRAPH_JS = "(async function(){\n" +
            "  const resp = await fetch('graph.json');\n" +
            "  const data = await resp.json();\n" +
            "  const nodes = data.nodes.map(n => ({ data: { id: n.id, label: n.label, image: n.image }, classes: n.source ? 'source' : '' }));\n" +
            "  const edges = data.edges.map(e => ({ data: { id: e.from+'->'+e.to, source: e.from, target: e.to } }));\n" +
            "  const cy = cytoscape({ container: document.getElementById('cy'), elements: nodes.concat(edges), style: [\n" +
            "    { selector: 'node', style: { \n" +
            "      'border-color': '#ccc', \n" +
            "      'border-width': '2px', \n" +
            "      'width': '48px', \n" +
            "      'height': '48px', \n" +
            "      'label': '', \n" +
            "      'text-valign': 'bottom', \n" +
            "      'text-halign': 'center', \n" +
            "      'font-size': '10px', \n" +
            "      'text-margin-y': '5px', \n" +
            "      'padding': '0px', \n" +
            "      'background-color': '#ddd', \n" +
            "      'background-image': function(ele) { \n" +
            "          const imageUrl = ele.data('image'); \n" +
            "          return imageUrl ? imageUrl : 'none'; \n" +
            "      },\n" +
            "      'background-fit': function(ele) { \n" +
            "          const imageUrl = ele.data('image'); \n" +
            "          return imageUrl ? 'cover' : 'none'; \n" +
            "      }\n" +
            "    } },\n" +
            "    { selector: 'node.source', style: { 'border-color': '#ff6b6b' } },\n" +
            "    { selector: 'edge', style: { 'curve-style': 'bezier', 'target-arrow-shape': 'triangle', 'line-color': '#9e9e9e' } },\n" +
            "    // Updated styles for highlighting\n" +
            "    { selector: '.highlighted', style: { 'background-color': '#800080', 'border-color': '#800080', 'border-width': '4px' } },\n" +
            "    { selector: '.faded', style: { 'opacity': '0.3' } },\n" +
            "    // New styles for edges\n" +
            "    { selector: 'edge.in-edge', style: { 'line-color': '#ff0000', 'target-arrow-color': '#ff0000', 'width': 4 } },\n" +
            "    { selector: 'edge.out-edge', style: { 'line-color': '#00ff00', 'target-arrow-color': '#00ff00', 'width': 4 } }\n" +
            "  ], layout: { \n" +
            "    name: 'cose', \n" +
            "    nodeRepulsion: 8000, \n" +
            "    idealEdgeLength: 100, \n" +
            "    padding: 20\n" +
            "  } });\n" +
            "\n" +
            "  // Search functionality\n" +
            "  const searchInput = document.getElementById('search-input');\n" +
            "  searchInput.addEventListener('input', e => {\n" +
            "    const query = e.target.value.toLowerCase();\n" +
            "    if (query.length > 2) {\n" +
            "      const matchingNodes = cy.nodes().filter(n => n.data('label').toLowerCase().includes(query));\n" +
            "      if (matchingNodes.length > 0) {\n" +
            "        cy.elements().removeClass('faded').unlock();\n" +
            "        cy.nodes().removeClass('highlighted');\n" +
            "        matchingNodes.addClass('highlighted');\n" +
            "        \n" +
            "        const neighbors = matchingNodes.incomers().nodes().union(matchingNodes.outgoers().nodes());\n" +
            "        neighbors.addClass('highlighted');\n" +
            "        \n" +
            "        const nonMatching = cy.nodes().not(matchingNodes).not(neighbors);\n" +
            "        nonMatching.addClass('faded').lock(); // Lock non-highlighted nodes\n" +
            "        cy.edges().addClass('faded');\n" +
            "        matchingNodes.connectedEdges().removeClass('faded');\n" +
            "        \n" +
            "        // Add red border to source nodes that are highlighted\n" +
            "        matchingNodes.filter('.source').style('border-color', '#ff6b6b');\n" +
            "        neighbors.filter('.source').style('border-color', '#ff6b6b');\n" +
            "        \n" +
            "        // Center the view on the first matching node\n" +
            "        cy.animate({ \n" +
            "          center: { eles: matchingNodes[0] }, \n" +
            "          zoom: 1.5, \n" +
            "          duration: 500\n" +
            "        });\n" +
            "      } else {\n" +
            "        cy.elements().removeClass('faded').removeClass('highlighted').unlock();\n" +
            "        cy.nodes().style('border-color', '#ccc'); // Reset border color for all nodes\n" +
            "        cy.nodes('.source').style('border-color', '#ff6b6b'); // Reapply red border for source nodes\n" +
            "      }\n" +
            "    } else {\n" +
            "      cy.elements().removeClass('faded').removeClass('highlighted').unlock();\n" +
            "      cy.nodes().style('border-color', '#ccc'); // Reset border color for all nodes\n" +
            "      cy.nodes('.source').style('border-color', '#ff6b6b'); // Reapply red border for source nodes\n" +
            "    }\n" +
            "  });\n" +
            "\n" +
            "  cy.on('mouseover', 'node', e => { e.target.qtip({ content: e.target.data('label'), show: { event: 'mouseover' }, hide: { event: 'mouseout' }, position: { my: 'bottom center', at: 'top center' }, style: { classes: 'qtip-tipsy' } }); });\n" +
            "\n" +
            "  cy.on('tap', 'node', e => {\n" +
            "    const node = e.target;\n" +
            "    cy.edges().removeClass('in-edge').removeClass('out-edge'); // Clear previous coloring\n" +
            "    node.incomers('edge').addClass('in-edge');\n" +
            "    node.outgoers('edge').addClass('out-edge');\n" +
            "  });\n" +
            "\n" +
            "  // Clear edge coloring when tapping on the background\n" +
            "  cy.on('tap', e => {\n" +
            "    if (e.target === cy) {\n" +
            "      cy.edges().removeClass('in-edge').removeClass('out-edge');\n" +
            "    }\n" +
            "  });\n" +
            "})();";
    public static final String GRAPH_HTML = "<!doctype html>\n" +
            "<html><head><meta charset=\"utf-8\"><title>Block Recipe Graph</title>\n" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n" +
            "<style>html,body,#cy{height:100%;margin:0;padding:0}\n" +
            "#search-container { position: fixed; top: 10px; left: 10px; z-index: 100; }\n" +
            "#search-input { width: 250px; padding: 5px; border: 1px solid #ccc; border-radius: 4px; }\n" +
            "</style>\n" +
            "</head><body>\n" +
            "<div id=\"search-container\"><input type=\"text\" id=\"search-input\" placeholder=\"Search for a block...\"></div>\n" +
            "<div id=\"cy\"></div>\n" +
            "<script src=\"https://unpkg.com/jquery@3.6.0/dist/jquery.min.js\"></script>\n" +
            "<script src=\"https://unpkg.com/qtip2@3.0.3/dist/jquery.qtip.min.js\"></script>\n" +
            "<link rel=\"stylesheet\" href=\"https://unpkg.com/qtip2@3.0.3/dist/jquery.qtip.min.css\">\n" +
            "<script src=\"https://unpkg.com/cytoscape@3.24.0/dist/cytoscape.min.js\"></script>\n" +
            "<script src=\"https://unpkg.com/cytoscape-qtip@2.8.0/dist/cytoscape-qtip.js\"></script>\n" +
            "<script src=\"graph.js\"></script>\n" +
            "</body></html>";

    public static JsonObject generateRegistryJson() {
        JsonObject registry = new JsonObject();
        for (Block block : BuiltInRegistries.BLOCK) {
            int rawId = BuiltInRegistries.BLOCK.getId(block);
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            registry.addProperty(String.valueOf(rawId), id);
        }
        return registry;
    }

    public static JsonObject buildGraphFromRecipes(MinecraftServer server, JsonObject registryJson) {
        Set<String> allowedBlocks = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : registryJson.entrySet()) {
            allowedBlocks.add(entry.getValue().getAsString());
        }

        Map<String, Set<String>> graph = new HashMap<>();
        Map<String, String> blockToImagePath = new HashMap<>();

        Set<String> outputs = new HashSet<>(); // New set to track all outputs

        Collection<Recipe<?>> recipes = server.getRecipeManager().getRecipes();

        for (Recipe<?> recipe : recipes) {
            ItemStack output = recipe.getResultItem(server.registryAccess());
            Item outputItem = output.getItem();

            if (!(outputItem instanceof BlockItem)) continue;

            Block outputBlock = ((BlockItem) outputItem).getBlock();
            String outputId = BuiltInRegistries.BLOCK.getKey(outputBlock).toString();

            if (!allowedBlocks.contains(outputId)) continue;

            outputs.add(outputId); // Add the block to the outputs set

            Set<String> inputs = new HashSet<>();
            try {
                for (var ingredient : recipe.getIngredients()) {
                    for (ItemStack stack : ingredient.getItems()) {
                        Item item = stack.getItem();
                        if (item instanceof BlockItem) {
                            Block inputBlock = ((BlockItem) item).getBlock();
                            String inputId = BuiltInRegistries.BLOCK.getKey(inputBlock).toString();
                            if (allowedBlocks.contains(inputId)) {
                                inputs.add(inputId);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (!inputs.isEmpty()) {
                graph.computeIfAbsent(outputId, k -> new HashSet<>()).addAll(inputs);
            }
        }

        // This part remains the same to find the correct texture path
        for (String blockId : allowedBlocks) {
            ResourceLocation resLoc = new ResourceLocation(blockId);
            ResourceLocation itemModelLocation = new ResourceLocation(resLoc.getNamespace(), "item/" + resLoc.getPath());
            ResourceLocation blockModelLocation = new ResourceLocation(resLoc.getNamespace(), "block/" + resLoc.getPath());

            String texturePath = getTextureFromModel(itemModelLocation, new HashSet<>());
            if (texturePath == null) {
                texturePath = getTextureFromModel(blockModelLocation, new HashSet<>());
            }

            blockToImagePath.put(blockId, getImagePath(texturePath));
        }

        // The logic for determining source blocks is now correct
        Set<String> sourceBlocks = allowedBlocks.stream()
                .filter(b -> !outputs.contains(b))
                .collect(Collectors.toSet());

        JsonObject out = new JsonObject();
        JsonArray nodes = new JsonArray();
        for (String blockId : allowedBlocks) {
            JsonObject node = new JsonObject();
            node.addProperty("id", blockId);
            node.addProperty("label", blockId);
            node.addProperty("source", sourceBlocks.contains(blockId));
            node.addProperty("image", blockToImagePath.getOrDefault(blockId, ""));
            nodes.add(node);
        }
        out.add("nodes", nodes);

        JsonArray edges = new JsonArray();
        for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
            String outBlock = entry.getKey();
            for (String inBlock : entry.getValue()) {
                JsonObject edge = new JsonObject();
                edge.addProperty("from", inBlock);
                edge.addProperty("to", outBlock);
                edges.add(edge);
            }
        }
        out.add("edges", edges);

        return out;
    }

    private static String getImagePath(String texturePath) {
        if (texturePath != null) {
            ResourceLocation textureLocation = new ResourceLocation(texturePath);
            return String.format("assets/%s/textures/%s.png", textureLocation.getNamespace(), textureLocation.getPath());
        }
        return "";
    }

    private static String getTextureFromModel(ResourceLocation modelLocation, Set<String> visitedModels) {
        String modelPath = modelLocation.toString();
        if (visitedModels.contains(modelPath)) {
            // We've found a recursive loop, so we stop here.
            return null;
        }
        visitedModels.add(modelPath);

        String texturePath = null;
        try (InputStream stream = GraphExporter.class.getResourceAsStream("/assets/" + modelLocation.getNamespace() + "/models/" + modelLocation.getPath() + ".json")) {
            if (stream == null) {
                return null;
            }

            JsonObject modelJson = GSON.fromJson(new InputStreamReader(stream), JsonObject.class);

            // Check for textures directly
            if (modelJson.has("textures")) {
                JsonObject textures = modelJson.getAsJsonObject("textures");
                if (textures.has("layer0")) {
                    texturePath = textures.get("layer0").getAsString();
                } else if (textures.has("side")) {
                    texturePath = textures.get("side").getAsString();
                } else if (textures.keySet().iterator().hasNext()) {
                    texturePath = textures.get(textures.keySet().iterator().next()).getAsString();
                }
            }

            // If a texture path was found, resolve it
            if (texturePath != null) {
                if (texturePath.startsWith("minecraft:")) {
                    return texturePath;
                } else {
                    return modelLocation.getNamespace() + ":" + texturePath;
                }
            }

            // If no texture was found, check for a parent model
            if (modelJson.has("parent")) {
                String parentId = modelJson.get("parent").getAsString();
                return getTextureFromModel(new ResourceLocation(parentId), visitedModels);
            }

        } catch (IOException e) {
            Orge.LOGGER.warn("Failed to read model file: " + modelLocation.toString());
        }
        return null;
    }

    public static void copyItemTextures(Set<String> blockIds, Path outputDir) throws IOException {
        for (String blockId : blockIds) {
            ResourceLocation resLoc = new ResourceLocation(blockId);
            ResourceLocation itemModelLocation = new ResourceLocation(resLoc.getNamespace(), "item/" + resLoc.getPath());
            ResourceLocation blockModelLocation = new ResourceLocation(resLoc.getNamespace(), "block/" + resLoc.getPath());

            // First, try to find the texture from the item model chain
            String texturePath = getTextureFromModel(itemModelLocation, new HashSet<>());

            // If no texture was found from the item model, try the block model chain
            if (texturePath == null) {
                texturePath = getTextureFromModel(blockModelLocation, new HashSet<>());
            }

            // If a texture path was successfully found from either model
            if (texturePath != null) {
                ResourceLocation textureLocation = new ResourceLocation(texturePath);
                Path destinationPath = Paths.get(outputDir.toString(), "assets", textureLocation.getNamespace(), "textures", textureLocation.getPath() + ".png");

                try (InputStream inputStream = GraphExporter.class.getResourceAsStream("/assets/" + textureLocation.getNamespace() + "/textures/" + textureLocation.getPath() + ".png")) {
                    if (inputStream != null) {
                        Files.createDirectories(destinationPath.getParent());
                        Files.copy(inputStream, destinationPath, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Orge.LOGGER.warn("Could not find texture file: " + textureLocation.toString());
                    }
                }
            } else {
                Orge.LOGGER.warn("Could not find a valid model or texture for block: " + blockId);
            }
        }
    }
}