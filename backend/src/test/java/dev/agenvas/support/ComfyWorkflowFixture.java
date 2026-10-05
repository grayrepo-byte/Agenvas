package dev.agenvas.support;

import dev.agenvas.provider.domain.ComfyUiWorkflowDefinition;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic API graphs for contract tests; no model, credentials or real provider data. */
public final class ComfyWorkflowFixture {
    private ComfyWorkflowFixture() {}

    public static ObjectNode settings(ObjectMapper mapper, boolean video, boolean reference) {
        ObjectNode settings = mapper.createObjectNode();
        ObjectNode definition = settings.putObject(ComfyUiWorkflowDefinition.SETTINGS_KEY);
        definition.put("schemaVersion", 1).put("width", 640).put("height", 640)
                .put("minimumSeconds", video ? 1 : 0).put("maximumSeconds", video ? 30 : 0)
                .put("fps", 24).put("frameMultiple", 4).put("frameOffset", 1);
        ObjectNode graph = (ObjectNode) mapper.readTree("""
                {"11":{"class_type":"TextEncode","inputs":{"text":"synthetic prompt"}},
                "12":{"class_type":"EmptyLatent","inputs":{"width":640,"height":640,"seed":4,"batch_size":2}},
                "14":{"class_type":"SyntheticGenerator","inputs":{"conditioning":["11",0],"latent":["12",0],"frames":1,"fps":24,"steps":20}},
                "99":{"class_type":"SaveImage","inputs":{"samples":["14",0],"filename_prefix":"render/demo"}}}
                """);
        if (video) graph.withObject("99").put("class_type", "SaveVideo");
        definition.set("graph", graph);
        var bindings = definition.putArray("bindings");
        bindings.addObject().put("nodeId", "11").put("inputName", "text").put("source", "PROMPT");
        for (String key : new String[] {"width", "height", "seed", "batch_size"}) {
            bindings.addObject().put("nodeId", "12").put("inputName", key)
                    .put("source", "batch_size".equals(key) ? "BATCH_SIZE" : key.toUpperCase(java.util.Locale.ROOT));
        }
        if (video) {
            bindings.addObject().put("nodeId", "14").put("inputName", "frames").put("source", "FRAME_COUNT");
            bindings.addObject().put("nodeId", "14").put("inputName", "fps").put("source", "FPS");
        }
        if (reference) {
            graph.putObject("13").put("class_type", "LoadImage").putObject("inputs").put("image", "synthetic.png");
            graph.withObject("14").withObject("inputs").putArray("reference").add("13").add(0);
            bindings.addObject().put("nodeId", "13").put("inputName", "image").put("source", "REFERENCE_IMAGE").put("referenceIndex", 0);
        }
        definition.putObject("output").put("nodeId", "99").put("field", video ? "videos" : "images");
        return settings;
    }
}
