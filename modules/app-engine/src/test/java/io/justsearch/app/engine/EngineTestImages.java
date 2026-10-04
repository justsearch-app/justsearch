/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

/**
 * Lane F stage A item A12 — the one image fixture the converted system tests share.
 *
 * <p>The retired {@code CompleteIndexingWorkflowE2ETest} inlined this exact byte array; the retired
 * {@code VduRecoverySystemTest} instead drew a 100x100 PNG with {@code java.awt.Graphics2D} and
 * {@code ImageIO}. Both were reaching for the same thing — a file the ingest pipeline classifies as
 * an image — and the byte array is the better of the two here, for two reasons:
 *
 * <ol>
 *   <li>It has no {@code java.desktop} dependency, so it cannot fail on a font-less or headless
 *       test JVM the way {@code Graphics2D.drawString} can.
 *   <li>It makes the VDU expectation <em>deterministic</em> rather than incidental. VDU demand is
 *       decided by {@code VisualRoutingDecision.decide}: a {@code .png} is eligible
 *       (VisualRoutingDecision.java:20-21), and an extraction that yields fewer than two
 *       letters-or-digits is a dropout (ExtractionDropoutPolicy.java:MIN_USABLE_ALPHANUMERIC_CHARS
 *       = 2), which routes straight to {@code VDU_STATUS_PENDING}
 *       (VisualRoutingDecision.java:62-66). A one-pixel image has no text by construction, so it
 *       lands on that branch every time. The drawn "TEST" glyphs did not: whether OCR read them
 *       decided which branch fired, and the retired test's PENDING assertion held only because OCR
 *       happened not to.
 * </ol>
 */
final class EngineTestImages {

  /** A minimal, valid 1x1 RGB PNG — signature, IHDR, IDAT, IEND, each with a correct CRC. */
  private static final byte[] MINIMAL_PNG = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
    0x00, 0x00, 0x00, 0x0D, // IHDR length
    0x49, 0x48, 0x44, 0x52, // "IHDR"
    0x00, 0x00, 0x00, 0x01, // width = 1
    0x00, 0x00, 0x00, 0x01, // height = 1
    0x08, 0x02, // bit depth 8, colour type 2 (RGB)
    0x00, 0x00, 0x00, // compression, filter, interlace
    (byte) 0x90, 0x77, 0x53, (byte) 0xDE, // IHDR CRC
    0x00, 0x00, 0x00, 0x0C, // IDAT length
    0x49, 0x44, 0x41, 0x54, // "IDAT"
    0x08, (byte) 0xD7, 0x63, (byte) 0xF8, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00,
    0x05, (byte) 0xFE, 0x02, (byte) 0xFE,
    (byte) 0xA3, 0x21, 0x69, (byte) 0xE5, // IDAT CRC
    0x00, 0x00, 0x00, 0x00, // IEND length
    0x49, 0x45, 0x4E, 0x44, // "IEND"
    (byte) 0xAE, 0x42, 0x60, (byte) 0x82 // IEND CRC
  };

  private EngineTestImages() {}

  /** A fresh copy of the fixture, so a caller writing it cannot mutate the shared array. */
  static byte[] minimalPng() {
    return MINIMAL_PNG.clone();
  }
}
