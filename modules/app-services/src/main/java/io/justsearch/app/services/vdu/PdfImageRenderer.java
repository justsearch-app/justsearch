/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.app.util.TempFileManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders PDF pages to temporary images for VDU processing.
 *
 * <p>Uses PDFBox 3.x API (Loader.loadPDF).
 *
 * <p><b>Usage:</b> Create via try-with-resources to ensure cleanup:
 * <pre>{@code
 * try (PdfImageRenderer renderer = new PdfImageRenderer(tempManager)) {
 *     List<Path> images = renderer.render(pdfPath);
 *     // process images...
 * } // images cleaned up here
 * }</pre>
 */
public class PdfImageRenderer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(PdfImageRenderer.class);

    /** DPI for rendering. 100 DPI is sufficient for VLM OCR (~50% faster than 150 DPI). */
    private static final int DEFAULT_DPI = 100;

    /** Maximum pages to process per PDF (prevent runaway processing). */
    private static final int MAX_PAGES = 50;

    private final TempFileManager tempFileManager;
    private final List<Path> renderedImages = new ArrayList<>();

    /**
     * Creates a new PdfImageRenderer.
     *
     * @param tempFileManager manager for temporary file creation and cleanup
     */
    public PdfImageRenderer(TempFileManager tempFileManager) {
        this.tempFileManager = tempFileManager;
    }

    /**
     * Renders PDF pages to temporary PNG files.
     *
     * @param pdfPath path to PDF file
     * @return list of temp image paths (ordered by page number)
     * @throws IOException if PDF cannot be read or images cannot be written
     */
    public List<Path> render(Path pdfPath) throws IOException {
        renderedImages.clear();

        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            BoundedPdfRenderer renderer = new BoundedPdfRenderer(document);
            int totalPages = document.getNumberOfPages();
            int pageCount = Math.min(totalPages, MAX_PAGES);

            // Validate every selected page before the first raster allocation. PDFRenderer uses
            // the crop box and DPI scale; round upward to cover its integer raster dimensions.
            long documentPixels = 0;
            for (int page = 0; page < pageCount; page++) {
                var box = document.getPage(page).getCropBox();
                float scale = DEFAULT_DPI / 72f;
                documentPixels += VduImageLimits.checkDimensions(
                    box.getWidth() * scale, box.getHeight() * scale);
                if (documentPixels > VduImageLimits.MAX_DOCUMENT_PIXELS) {
                    throw new IOException("VDU document pixels exceed allocation limit");
                }
            }

            if (pageCount < totalPages) {
                LOG.warn("PDF has {} pages, processing only first {} (limit)",
                    totalPages, MAX_PAGES);
            }

            LOG.debug("Rendering {} pages from: {}", pageCount, pdfPath.getFileName());

            for (int page = 0; page < pageCount; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, DEFAULT_DPI, ImageType.RGB);
                // PDFBox may catch an operator's IOException. A refused image must reject the
                // document even if the renderer returned a page with that image omitted.
                renderer.requireWithinLimits();
                Path tempPath = tempFileManager.createTempFile("vdu_page_" + page + "_", ".png");
                ImageIO.write(image, "PNG", tempPath.toFile());
                renderedImages.add(tempPath);
                LOG.trace("Rendered page {}/{} to: {}", page + 1, pageCount, tempPath);
            }
        }

        LOG.debug("Rendered {} pages from: {}", renderedImages.size(), pdfPath.getFileName());
        return List.copyOf(renderedImages);
    }

    /**
     * Returns the number of rendered images (for progress tracking).
     */
    public int getRenderedCount() {
        return renderedImages.size();
    }

    @Override
    public void close() {
        int count = renderedImages.size();
        for (Path path : renderedImages) {
            try {
                tempFileManager.cleanup(path);
            } catch (Exception e) {
                LOG.warn("Failed to cleanup temp image: {}", path, e);
            }
        }
        renderedImages.clear();
        if (count > 0) {
            LOG.trace("Cleaned up {} temp images", count);
        }
    }
}
