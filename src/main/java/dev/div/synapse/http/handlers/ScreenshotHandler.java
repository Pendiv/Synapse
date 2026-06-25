package dev.div.synapse.http.handlers;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.sun.net.httpserver.HttpExchange;
import dev.div.synapse.config.SynapseConfig;
import dev.div.synapse.core.MainThread;
import dev.div.synapse.http.EndpointResult;
import dev.div.synapse.http.HttpUtil;
import dev.div.synapse.http.SynapseEndpoint;
import dev.div.synapse.http.SynapseError;
import dev.div.synapse.http.SynapseException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.Base64;
import java.util.Map;

/** {@code GET /screenshot?format=base64|raw} — visual confirmation (spec §5.4). */
public final class ScreenshotHandler implements SynapseEndpoint {

    @Override
    public String path() {
        return "/screenshot";
    }

    @Override
    public String[] methods() {
        return new String[]{"GET"};
    }

    @Override
    public EndpointResult handle(HttpExchange exchange) throws Exception {
        if (!SynapseConfig.SCREENSHOT_ENABLED.get()) {
            throw new SynapseException(SynapseError.BAD_REQUEST, "Screenshot endpoint is disabled in config.");
        }
        Map<String, String> q = HttpUtil.queryParams(exchange);
        String format = q.getOrDefault("format", "base64");
        long timeout = SynapseConfig.TIMEOUT_MS.get();

        byte[] png = MainThread.run(ScreenshotHandler::capture, timeout);

        if (format.equalsIgnoreCase("raw")) {
            return EndpointResult.raw("image/png", png);
        }
        if (format.equalsIgnoreCase("base64")) {
            JsonObject data = new JsonObject();
            data.addProperty("format", "base64");
            data.addProperty("encoding", "png");
            data.addProperty("bytes", png.length);
            data.addProperty("image", Base64.getEncoder().encodeToString(png));
            return EndpointResult.json(data);
        }
        throw new SynapseException(SynapseError.BAD_REQUEST,
                "format must be 'base64' or 'raw', got '" + format + "'.");
    }

    /** Captures the framebuffer to PNG bytes. Runs on the render (main) thread. */
    private static byte[] capture() throws SynapseException {
        Minecraft mc = Minecraft.getInstance();
        NativeImage image = null;
        try {
            image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            return image.asByteArray();
        } catch (Throwable t) {
            throw new SynapseException(SynapseError.SCREENSHOT_FAILED,
                    "Failed to capture or encode the framebuffer: " + t.getMessage(), t);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    @Override
    public JsonObject manifestFragment() {
        JsonObject f = new JsonObject();
        f.addProperty("path", "/screenshot");
        f.addProperty("method", "GET");
        f.addProperty("desc", "Capture the current frame as PNG. For visual/render checks only — use /state for logic.");
        JsonObject params = new JsonObject();
        params.addProperty("format", "base64 (default) | raw (image/png binary)");
        f.add("params", params);
        f.addProperty("returns", "base64: { format, encoding, bytes, image }. raw: image/png body (no envelope).");
        return f;
    }
}
