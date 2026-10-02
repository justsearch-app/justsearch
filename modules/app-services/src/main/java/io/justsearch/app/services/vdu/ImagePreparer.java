/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Prepares images for VLM consumption (resize, format conversion).
 *
 * <p>Stateless utility - safe to reuse across multiple VDU operations.
 */
public class ImagePreparer {
    private static final Logger LOG = LoggerFactory.getLogger(ImagePreparer.class);

    /** Vision-model optimal: max 1280px on longest side (constraint inherited from Qwen3-VL era). */
    private static final int MAX_DIMENSION = 1280;

    /**
     * Loads and resizes image, returns JPEG bytes for base64 encoding.
     *
     * @param imagePath path to image file (PNG, JPEG, etc.)
     * @return JPEG bytes ready for base64 encoding
     * @throws IOException if image cannot be read or processed
     */
    public byte[] prepare(Path imagePath) throws IOException {
        BufferedImage original = VduImageLimits.read(imagePath);
        if (original == null) {
            throw new IOException("Failed to read image (unsupported format?): " + imagePath);
        }

        int origWidth = original.getWidth();
        int origHeight = original.getHeight();

        BufferedImage processed = resizeIfNeeded(original);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(processed, "JPEG", baos);

        LOG.trace("Prepared image: {}x{} -> {}x{}, {} bytes",
            origWidth, origHeight,
            processed.getWidth(), processed.getHeight(),
            baos.size());

        return baos.toByteArray();
    }

    private BufferedImage resizeIfNeeded(BufferedImage original) {
        int width = original.getWidth();
        int height = original.getHeight();

        // Convert to RGB if needed (handles PNG with alpha) even if no resize
        if (width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
            if (original.getType() != BufferedImage.TYPE_INT_RGB) {
                BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                // Fill with white background for transparent PNGs
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, width, height);
                g.drawImage(original, 0, 0, null);
                g.dispose();
                return rgb;
            }
            return original;
        }

        return scaleToFit(original, MAX_DIMENSION);
    }

    /**
     * Scales {@code original} to fit within a {@code maxDimension} x {@code maxDimension} box
     * (preserving aspect ratio), compositing onto a white background and normalizing to {@code
     * TYPE_INT_RGB}. Package-private so {@link ImageLegibility} can reuse the same downscale
     * behavior (bilinear interpolation, white-fill for transparent sources) instead of
     * duplicating it for its own bounded-size analysis pass.
     *
     * <p>Caller must ensure at least one of {@code original}'s dimensions exceeds {@code
     * maxDimension} if upscaling is undesired — this method always scales to fit the box.
     */
    static BufferedImage scaleToFit(BufferedImage original, int maxDimension) {
        int width = original.getWidth();
        int height = original.getHeight();

        double scale = Math.min((double) maxDimension / width, (double) maxDimension / height);
        int newWidth = Math.max(1, (int) (width * scale));
        int newHeight = Math.max(1, (int) (height * scale));

        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        // Fill with white background for transparent PNGs
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, newWidth, newHeight);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(original, 0, 0, newWidth, newHeight, null);
        g.dispose();

        return resized;
    }
}
