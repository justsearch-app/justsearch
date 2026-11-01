/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.app.api.chat.ChatRefusal;
import io.justsearch.app.api.chat.VduHold;
import io.justsearch.app.inference.chat.ChatHolds;
import io.justsearch.app.util.TempFileManager;
import io.justsearch.core.execution.EngineFutures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Two-pass VDU (Visual Document Understanding) pipeline via llama-server.
 *
 * <p>Pass 1: Extract text from document images (vision completion)
 * <p>Pass 2: Summarize and extract entities (chat completion)
 *
 * <p>Note: the canonical chat model (Qwen3.5-9B Q4_K_M) is text-only; the
 * VDU pipeline currently requires a separate vision-capable model on the
 * llama-server side. Vision-model selection is tracked separately from
 * tempdoc 374's model-registry work.
 *
 * <p>This processor is thread-safe and can be reused for multiple documents.
 */
public class VduProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(VduProcessor.class);

    private static final String PASS1_PROMPT =
        "Return the plain text representation of this document as if you were reading it naturally."
            + " Do not add any commentary.";

    private static final String PASS2_PROMPT_TEMPLATE = """
        /no_think
        Based on the following extracted document content, provide:
        1. A brief summary (2-3 sentences)
        2. Document type (invoice, contract, receipt, letter, etc.)
        3. Key entities (dates, amounts, names, addresses)

        Output as JSON: {"summary": "...", "doc_type": "...", "entities": {...}}

        Document content:
        %s
        """;

    /** Max tokens for text extraction pass (full-page text can exceed 2048 tokens). */
    private static final int PASS1_MAX_TOKENS = 4096;

    /** Max tokens for enrichment pass. */
    private static final int PASS2_MAX_TOKENS = 512;

    /**
     * Maximum characters to include in the pass 2 (enrichment) prompt.
     *
     * <p>This limit (8,000 chars ≈ 2,000 tokens) is larger than {@code SummaryController}'s 6K
     * limit because vision enrichment benefits from more context (extracted text from pass 1).
     *
     * <p>Rationale for 8K:
     * <ol>
     *   <li>Vision models can handle longer context than chat models
     *   <li>Pass 2 needs sufficient extracted text to enrich accurately
     *   <li>Balances quality against latency (keeps VDU under 30s total)
     * </ol>
     *
     * @see io.justsearch.ui.api.SummaryController#MAX_CONTENT_CHARS 6K summarization limit
     * @see io.justsearch.indexerworker.services.WorkerSearchService#MAX_CONTENT_CHARS 200K transport
     */
    private static final int MAX_CONTEXT_CHARS = 8000;

    /** Timeout for vision completion per page (seconds). */
    private static final long VDU_VISION_TIMEOUT_SECONDS = 120;

    /** Timeout for chat completion (seconds). */
    private static final long VDU_CHAT_TIMEOUT_SECONDS = 60;

    // Tempdoc 518 Appendix G W4.2: three per-role injections replace the concrete ILM holder.
    // Each role is documented at its use site (introspection for vision-capability check;
    // lifecycle for enter/exit VDU mode restart pair; service for vision + chat completions).
    private final OnlineAiRuntimeIntrospection introspection;
    private final OnlineAiLifecycleControl lifecycleControl;
    private final OnlineAiService aiService;
    private final TempFileManager tempFileManager;
    private final ImagePreparer imagePreparer;
    private final VduMetricCatalog catalog;

    /** The chat controller's holds; {@code null} where the processor drives the lifecycle itself. */
    private final ChatHolds chatHolds;

    /** The batch's open VDU hold, between {@link #enterVduMode} and {@link #exitVduMode}. */
    private final AtomicReference<VduHold> vduHold = new AtomicReference<>();

    /**
     * Creates a new VduProcessor without telemetry (backward compatibility).
     */
    public VduProcessor(OnlineAiRuntimeIntrospection introspection,
                        OnlineAiLifecycleControl lifecycleControl,
                        OnlineAiService aiService,
                        TempFileManager tempFileManager,
                        ImagePreparer imagePreparer) {
        this(introspection, lifecycleControl, aiService, tempFileManager, imagePreparer,
            VduMetricCatalog.noop());
    }

    /**
     * Creates a new VduProcessor with a {@link VduMetricCatalog} for typed metric emission.
     *
     * <p>Tempdoc 417 Phase 3d: pass timers (pass1, pass2, total) now emit through {@code catalog}
     * histograms ({@code vdu.pass1.duration_ms}, {@code vdu.pass2.duration_ms},
     * {@code vdu.total.duration_ms}) instead of the legacy {@code Telemetry.Timer} indirection.
     *
     * <p>Tempdoc 518 Appendix G W4.2: the previously-injected concrete {@code
     * InferenceLifecycleManager} is replaced by three role-typed handles. The migration
     * decouples this class from the inference internals and lets {@code
     * InferenceModuleBoundaryTest} tighten the ArchUnit rule to {@code
     * app-services.bootstrap..} only.
     *
     * @param introspection vision-capability probe ({@link OnlineAiRuntimeIntrospection})
     * @param lifecycleControl enter/exit VDU mode ({@link OnlineAiLifecycleControl})
     * @param aiService vision + chat completions ({@link OnlineAiService})
     * @param tempFileManager manager for temporary files
     * @param imagePreparer prepares images for VLM consumption
     * @param catalog VDU metric catalog (use {@link VduMetricCatalog#noop()} when wireup absent)
     */
    public VduProcessor(OnlineAiRuntimeIntrospection introspection,
                        OnlineAiLifecycleControl lifecycleControl,
                        OnlineAiService aiService,
                        TempFileManager tempFileManager,
                        ImagePreparer imagePreparer,
                        VduMetricCatalog catalog) {
        this(introspection, lifecycleControl, null, aiService, tempFileManager, imagePreparer, catalog);
    }

    /**
     * The Engine's processor: VDU mode is a hold on the chat controller (Spec A step 6), which
     * brings the server up with vision-safe flags and returns it to the user's intent on close.
     */
    public VduProcessor(OnlineAiRuntimeIntrospection introspection,
                        ChatHolds chatHolds,
                        OnlineAiService aiService,
                        TempFileManager tempFileManager,
                        ImagePreparer imagePreparer,
                        VduMetricCatalog catalog) {
        this(introspection, null, java.util.Objects.requireNonNull(chatHolds, "chatHolds"),
            aiService, tempFileManager, imagePreparer, catalog);
    }

    private VduProcessor(OnlineAiRuntimeIntrospection introspection,
                         OnlineAiLifecycleControl lifecycleControl,
                         ChatHolds chatHolds,
                         OnlineAiService aiService,
                         TempFileManager tempFileManager,
                         ImagePreparer imagePreparer,
                         VduMetricCatalog catalog) {
        this.introspection = introspection;
        this.lifecycleControl = lifecycleControl;
        this.chatHolds = chatHolds;
        this.aiService = aiService;
        this.tempFileManager = tempFileManager;
        this.imagePreparer = imagePreparer;
        this.catalog = catalog;
    }

    /**
     * Enter VDU mode (restarts server with vision-safe flags, {@code -np 1, --cache-ram 0}).
     * Tempdoc 672 follow-up: callers must scope this to the whole batch, not per-document — each
     * call is a full {@code llama-server} restart. {@link #process} no longer enters/exits VDU
     * mode itself; the batch caller does it once around the whole loop.
     */
    public void enterVduMode() throws VduException {
        enterVduMode(null);
    }

    /** A non-null checkpoint selects the controller's idle-only automatic loaded-chat hold. */
    public void enterVduMode(BooleanSupplier automaticEligibility) throws VduException {
        if (chatHolds != null) {
            long launchesBefore = chatHolds.serverLaunches();
            try {
                VduHold hold = automaticEligibility == null ? chatHolds.holdVdu("vdu-batch")
                    : chatHolds.holdAutomaticVdu("automatic-vdu",
                        java.time.Duration.ofMillis(VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS),
                        automaticEligibility);
                if (!vduHold.compareAndSet(null, hold)) {
                    hold.close();
                    throw new VduException("Failed to enter VDU mode: a VDU batch is already held",
                        null);
                }
            } catch (ChatRefusal refused) {
                String message =
                    "Failed to enter VDU mode: the chat server refused (" + refused.reason() + ")";
                long launchesAfter = chatHolds.serverLaunches();
                if (launchesBefore >= 0 && launchesAfter == launchesBefore) {
                    throw new EntryRefusedWithoutLaunch(message, refused);
                }
                throw new VduException(message, refused);
            }
            return;
        }
        if (automaticEligibility != null) {
            throw new VduException("Automatic loaded-chat VDU requires the chat controller", null);
        }
        try {
            lifecycleControl.enterVduMode();
        } catch (ModeTransitionException e) {
            throw new VduException("Failed to enter VDU mode: " + e.getMessage(), e);
        }
    }

    /** Exit VDU mode (restores normal server configuration). */
    public void exitVduMode() {
        if (chatHolds != null) {
            VduHold hold = vduHold.getAndSet(null);
            if (hold != null) hold.close();
            return;
        }
        try {
            lifecycleControl.exitVduMode();
        } catch (ModeTransitionException e) {
            throw new IllegalStateException(
                "Failed to exit VDU mode; server may remain in vision-safe config", e);
        }
    }

    /** The chat controller's holds, or {@code null} where the processor drives the lifecycle itself. */
    ChatHolds chatHolds() {
        return chatHolds;
    }

    /** Whether the chat controller asked the batch's VDU hold to stop (Spec A §2.2). */
    public boolean vduInterruptRequested() {
        VduHold hold = vduHold.get();
        return hold != null && hold.interruptRequested();
    }

    public boolean hasVisionCapability() {
        return introspection.hasVisionCapability();
    }

    /**
     * Process a document through the two-pass VDU pipeline.
     *
     * <p>Synchronous operation - blocks until complete.
     * Temp images are cleaned up automatically via try-with-resources.
     *
     * @param filePath path to PDF or image
     * @return extracted and enriched content
     * @throws VduException if processing fails
     */
    public VduResult process(Path filePath, io.justsearch.core.context.EngineContext engineContext) throws VduException {
        return process(filePath, engineContext, () -> true);
    }

    /** Eligibility is cooperative between inference requests; terminal cancellation still interrupts them. */
    public VduResult process(Path filePath, io.justsearch.core.context.EngineContext engineContext,
            BooleanSupplier eligible) throws VduException {
        if (!hasVisionCapability()) {
            throw new VduException(
                "Vision capability not available — no vision projector (mmproj) configured. "
                    + "Set JUSTSEARCH_MMPROJ_MODEL or ensure the AI pack includes a vision projector.",
                null);
        }

        String fileName = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        LOG.info("Starting VDU processing for: {}", filePath.getFileName());
        long startTime = System.currentTimeMillis();

        // Tempdoc 672 follow-up: VDU mode is entered/exited once per batch by the caller
        // (VduBatchProcessor), not per document — see enterVduMode()/exitVduMode() above.

        // Use try-with-resources to ensure temp image cleanup; record total latency via catalog
        // histogram on the way out (Phase 3d: replaces legacy Telemetry.Timer.Sample).
        // Phase 3 critical-analysis fix B1: capture totalStartNanos AFTER PdfImageRenderer
        // construction so the legacy "no record on resource-construction failure" semantics are
        // preserved. Outer finally checks for null to skip the record on that path.
        Long totalStartNanos = null;
        Path sourceSnapshot = null;
        try (PdfImageRenderer pdfRenderer = new PdfImageRenderer(tempFileManager)) {
            totalStartNanos = System.nanoTime();
            requireEligible(eligible);
            sourceSnapshot = tempFileManager.createTempFile("vdu_source_", fileName.endsWith(".pdf") ? ".pdf" : ".image");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(filePath), digest);
                 var output = Files.newOutputStream(sourceSnapshot)) {
                input.transferTo(output);
            }
            String sourceSha256 = HexFormat.of().formatHex(digest.digest());
            List<Path> pageImages;
            int documentPageCount = 1;

            if (fileName.endsWith(".pdf")) {
                pageImages = pdfRenderer.render(sourceSnapshot);
                documentPageCount = pdfRenderer.getDocumentPageCount();
                LOG.debug("Rendered {} pages from PDF", pageImages.size());
            } else {
                // Direct image - no temp files needed from renderer
                pageImages = List.of(sourceSnapshot);
            }

            // Tempdoc 677 Stage 0: measure input legibility on every page BEFORE any model call
            // (ImagePreparer.prepare() also reads the file, but re-reads it via a fresh
            // bounded decoding here so this measurement runs on the raw page image, independent of
            // ImagePreparer's own resize path — ImagePreparer is intentionally not touched by
            // this slice). Below-floor pages are skipped from the model call entirely; if EVERY
            // page is below floor, abstain without calling the model at all (Stage 0 CAUTION,
            // see VduAbstentionGate javadoc: catch only "no textual signal for anything").
            List<LegibilityMeasures> pageMeasures = new ArrayList<>(pageImages.size());
            List<Integer> legiblePageIndices = new ArrayList<>();
            for (Path pageImage : pageImages) {
                BufferedImage rawImage = VduImageLimits.read(pageImage);
                if (rawImage == null) {
                    throw new IOException("Failed to read image (unsupported format?): " + pageImage);
                }
                LegibilityMeasures measures = ImageLegibility.measure(rawImage);
                pageMeasures.add(measures);
                if (!VduAbstentionGate.inputVerdict(measures).rejected()) {
                    legiblePageIndices.add(pageMeasures.size() - 1);
                }
            }

            if (legiblePageIndices.isEmpty()) {
                GateVerdict stage0Verdict = buildStage0RejectVerdict(pageMeasures);
                LOG.info("VDU Stage 0 rejected all {} page(s) for {} (no input legibility signal)",
                    pageImages.size(), filePath.getFileName());
                return new VduResult("", null, pageImages.size(), stage0Verdict, false, sourceSha256);
            }
            if (legiblePageIndices.size() < pageImages.size()) {
                // Partial legibility: some pages are below the Stage 0 floor but not all. Still
                // send the legible ones — the model may genuinely read them; only a document
                // where NO page carries any textual signal abstains at Stage 0.
                LOG.info("VDU Stage 0: sending {}/{} legible pages for {}",
                    legiblePageIndices.size(), pageImages.size(), filePath.getFileName());
            }

            // Pass 1: Extract text from each legible page (timed)
            long pass1StartNanos = System.nanoTime();
            String extractedText;
            List<OnlineAiService.VisionCompletionResult> sentPageResults =
                new ArrayList<>(legiblePageIndices.size());
            try {
                StringBuilder allText = new StringBuilder();
                for (int idx : legiblePageIndices) {
                    LOG.debug("Processing page {}/{}", idx + 1, pageImages.size());

                    requireEligible(eligible);
                    byte[] imageBytes = imagePreparer.prepare(pageImages.get(idx));
                    requireEligible(eligible);
                    OnlineAiService.VisionCompletionResult pageResult = awaitDocument(
                        asVduBulkWork(() -> aiService.visionCompletionDetailed(PASS1_PROMPT,
                                imageBytes, PASS1_MAX_TOKENS, SamplingParams.VDU, null, engineContext))
                            .orTimeout(VDU_VISION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    sentPageResults.add(pageResult);

                    if (pageResult.content() == null || pageResult.content().isBlank()) continue;
                    if (pageImages.size() > 1) {
                        allText.append("--- Page ").append(idx + 1).append(" ---\n");
                    }
                    allText.append(pageResult.content()).append("\n\n");
                }
                extractedText = allText.toString().trim();
            } finally {
                catalog.pass1DurationMs.record(elapsedMs(pass1StartNanos), VduPassTags.of());
            }
            LOG.debug("Extracted {} characters from {} pages", extractedText.length(), pageImages.size());
            if (LOG.isTraceEnabled() && !extractedText.isEmpty()) {
                LOG.trace("VDU Pass 1 sample (first 500 chars): {}",
                    extractedText.substring(0, Math.min(500, extractedText.length())));
            }

            // Tempdoc 677 Stage 1: aggregate the same-call confidence signals across the pages
            // actually sent, then consult the gate before trusting Pass 1's output.
            GateVerdict stage1Verdict =
                VduAbstentionGate.outputVerdict(aggregateSignals(sentPageResults));

            if (stage1Verdict.band() == GateVerdict.Band.REJECT) {
                // Pass 2 is not a check — it summarizes Pass 1's own output, trusting it
                // unconditionally (tempdoc 677 code map finding). Do not summarize suspect text:
                // skip Pass 2 entirely for a document the gate already rejected.
                LOG.info("VDU Stage 1 rejected output for {} (suspect confabulation)",
                    filePath.getFileName());
                return new VduResult(extractedText, null, pageImages.size(), stage1Verdict, false, sourceSha256);
            }

            // Tempdoc 677 Stage 2: an AMBIGUOUS Stage-1 band is not yet a decision — re-sample the
            // single worst-signal page once (temperature/seed varied) and compare it against its
            // own Pass 1 text. Low agreement resolves to a rejection; high agreement resolves to a
            // pass, and processing continues into Pass 2 exactly as the PASS band would.
            boolean completeCoverage = pageImages.size() == documentPageCount
                && legiblePageIndices.size() == documentPageCount
                && sentPageResults.stream().noneMatch(page -> "length".equals(page.finishReason()));
            if (extractedText.isBlank()) {
                return new VduResult("", null, pageImages.size(), GateVerdict.passed(),
                    completeCoverage, sourceSha256);
            }
            GateVerdict resolvedVerdict = stage1Verdict;
            if (stage1Verdict.band() == GateVerdict.Band.AMBIGUOUS) {
                requireEligible(eligible);
                resolvedVerdict = runAgreementProbe(legiblePageIndices, pageImages, sentPageResults, filePath, engineContext, eligible);
                if (resolvedVerdict.rejected()) {
                    LOG.info("VDU Stage 2 rejected output for {} (agreement={}, probedPage={})",
                        filePath.getFileName(), resolvedVerdict.agreement(), resolvedVerdict.probedPage());
                    return new VduResult(extractedText, null, pageImages.size(), resolvedVerdict, completeCoverage, sourceSha256);
                }
            }

            // Pass 2 supplies optional metadata for text accepted by Stage 1 or Stage 2.
            long pass2StartNanos = System.nanoTime();
            String enrichment;
            try {
                requireEligible(eligible);
                String pass2Prompt = String.format(PASS2_PROMPT_TEMPLATE,
                    truncateForPrompt(extractedText, MAX_CONTEXT_CHARS));
                enrichment = awaitDocument(
                    asVduBulkWork(() -> aiService.chatCompletion(
                            List.of(Map.of("role", "user", "content", pass2Prompt)),
                            PASS2_MAX_TOKENS,
                            SamplingParams.VDU, engineContext))
                        .orTimeout(VDU_CHAT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (EligibilityDeferred deferred) {
                enrichment = null;
            } catch (RuntimeException failure) {
                EngineFutures.rethrowCancellation(failure);
                if (Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("VDU metadata interrupted");
                }
                LOG.warn("VDU metadata unavailable for {}; retaining transcription", filePath.getFileName(), failure);
                enrichment = null;
            } finally {
                catalog.pass2DurationMs.record(elapsedMs(pass2StartNanos), VduPassTags.of());
            }
            if (LOG.isTraceEnabled() && enrichment != null && !enrichment.isEmpty()) {
                LOG.trace("VDU Pass 2 enrichment (first 300 chars): {}",
                    enrichment.substring(0, Math.min(300, enrichment.length())));
            }

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("VDU complete for {}: {} chars, {}/{} pages, {}ms, coverage={}",
                filePath.getFileName(), extractedText.length(), pageImages.size(), documentPageCount, elapsed,
                completeCoverage ? "complete" : "incomplete");

            return new VduResult(extractedText, enrichment, pageImages.size(), resolvedVerdict, completeCoverage, sourceSha256);

        } catch (EligibilityDeferred deferred) {
            throw deferred;
        } catch (IOException e) {
            LOG.error("VDU I/O error for: {}", filePath, e);
            throw new VduException("Failed to read/render document: " + e.getMessage(), e);
        } catch (Exception e) {
            EngineFutures.rethrowCancellation(e);
            // Check for timeout (wrapped in CompletionException)
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException || e instanceof TimeoutException) {
                LOG.error("VDU timeout for: {} (vision={}s, chat={}s limits)",
                    filePath, VDU_VISION_TIMEOUT_SECONDS, VDU_CHAT_TIMEOUT_SECONDS);
                catalog.timeoutTotal.increment(VduTimeoutTags.of());
                throw new VduException("VDU timeout exceeded", e);
            }
            LOG.error("VDU processing failed for: {}", filePath, e);
            throw new VduException("VDU processing failed: " + e.getMessage(), e);
        } finally {
            // Tempdoc 672 follow-up: VDU mode exit is now the batch caller's responsibility
            // (VduBatchProcessor), once per batch — see exitVduMode() above.
            // Record total duration on both success and failure paths IF the
            // PdfImageRenderer was successfully constructed (legacy Timer.Sample was opened
            // inside the try-with-resources head; failure to acquire the renderer never started
            // the timer). Phase 3 critical-analysis fix B1.
            if (sourceSnapshot != null) tempFileManager.cleanup(sourceSnapshot);
            if (totalStartNanos != null) {
                catalog.totalDurationMs.record(elapsedMs(totalStartNanos), VduPassTags.of());
            }
        }
    }

    private static void requireEligible(BooleanSupplier eligible) throws EligibilityDeferred {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("VDU processing interrupted");
        }
        if (!eligible.getAsBoolean()) throw new EligibilityDeferred();
    }

    /** A deferral returns the owned claim and its retry allowance to the next eligible pass. */
    public static final class EligibilityDeferred extends VduException {
        public EligibilityDeferred() { super("VDU eligibility changed between requests", null); }
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** Truncate text to fit within prompt context window. */
    private String truncateForPrompt(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... [truncated]";
    }

    /**
     * Builds the document-level Stage 0 rejection verdict when EVERY page is below the input
     * legibility floor. {@link VduAbstentionGate#inputVerdict} only judges a single page; this
     * aggregates the (all-rejected) per-page measurements into one verdict for the caller.
     *
     * <p>Reports the MAX laplacianVariance and MAX rmsContrast independently across all pages —
     * i.e. the strongest per-signal counter-evidence against rejecting (the page that came
     * closest to passing on that signal), even though each still failed both floors. The two
     * maxima are not necessarily from the same page; this is evidence for a human/log reader, not
     * a value fed back into a decision, so that mismatch is immaterial.
     */
    private static GateVerdict buildStage0RejectVerdict(List<LegibilityMeasures> pageMeasures) {
        double maxLaplacian = Double.NEGATIVE_INFINITY;
        double maxContrast = Double.NEGATIVE_INFINITY;
        for (LegibilityMeasures measures : pageMeasures) {
            maxLaplacian = Math.max(maxLaplacian, measures.laplacianVariance());
            maxContrast = Math.max(maxContrast, measures.rmsContrast());
        }
        return new GateVerdict(
            true, GateVerdict.Band.REJECT, VduAbstentionGate.STAGE_INPUT_LEGIBILITY,
            null, null, null, null,
            maxLaplacian, maxContrast, null, null);
    }

    /**
     * Reduces the per-page {@link OnlineAiService.VisionCompletionResult}s from every page sent
     * to the model into the document-level {@link AggregatedPageSignals} that {@link
     * VduAbstentionGate#outputVerdict} consumes (tempdoc 677 Stage 1).
     *
     * <p>{@code meanLogprob}/{@code lowConfidenceFraction} are token-weighted means (a page's
     * result contributes proportionally to its own token count) rather than a simple average or
     * a max — a max would be "too twitchy" per the tempdoc's task brief (one thin page dominating
     * the whole-document verdict), and an unweighted average lets a short page count as much as a
     * long one. Pages with no logprob signal (null) do not contribute to either weighted sum —
     * NO SIGNAL must not be conflated with a real low value.
     *
     * <p>{@code finishReason} aggregation applies a priority: an anomalous (non-{@code "stop"},
     * non-{@code "length"}) reason on ANY page wins outright (it is the strongest evidence);
     * otherwise {@code "length"} if any page was truncated; otherwise {@code "stop"} if every
     * reporting page completed cleanly; otherwise {@code null} if no page reported a reason at
     * all. {@link VduAbstentionGate#outputVerdict} itself excludes {@code "length"} from
     * triggering rejection alone, so this method does not need to special-case it beyond ordering
     * it below a genuinely anomalous reason.
     */
    private static AggregatedPageSignals aggregateSignals(
        List<OnlineAiService.VisionCompletionResult> sentPageResults) {
        double weightedLogprobSum = 0.0;
        long weightedLogprobTokens = 0;
        double weightedFractionSum = 0.0;
        long weightedFractionTokens = 0;
        int totalTokenCount = 0;
        String anomalousFinishReason = null;
        boolean sawTruncation = false;
        boolean sawStop = false;

        for (OnlineAiService.VisionCompletionResult result : sentPageResults) {
            totalTokenCount += result.tokenCount();
            if (result.meanLogprob() != null && result.tokenCount() > 0) {
                weightedLogprobSum += result.meanLogprob() * result.tokenCount();
                weightedLogprobTokens += result.tokenCount();
            }
            if (result.lowConfidenceFraction() != null && result.tokenCount() > 0) {
                weightedFractionSum += result.lowConfidenceFraction() * result.tokenCount();
                weightedFractionTokens += result.tokenCount();
            }
            String finishReason = result.finishReason();
            if (finishReason == null) {
                continue;
            }
            if ("stop".equals(finishReason)) {
                sawStop = true;
            } else if ("length".equals(finishReason)) {
                sawTruncation = true;
            } else if (anomalousFinishReason == null) {
                anomalousFinishReason = finishReason;
            }
        }

        Double meanLogprob = weightedLogprobTokens > 0 ? weightedLogprobSum / weightedLogprobTokens : null;
        Double lowConfidenceFraction =
            weightedFractionTokens > 0 ? weightedFractionSum / weightedFractionTokens : null;
        String aggregateFinishReason;
        if (anomalousFinishReason != null) {
            aggregateFinishReason = anomalousFinishReason;
        } else if (sawTruncation) {
            aggregateFinishReason = "length";
        } else if (sawStop) {
            aggregateFinishReason = "stop";
        } else {
            aggregateFinishReason = null;
        }

        return new AggregatedPageSignals(
            meanLogprob, lowConfidenceFraction, totalTokenCount, aggregateFinishReason);
    }

    /**
     * Tempdoc 677 Stage 2: fixed seed for the re-sample agreement probe's HTTP request. Varying
     * temperature (see {@link SamplingParams#VDU_PROBE}) is what makes the probe capable of
     * disagreeing with Pass 1's own output; the seed is fixed (not random) purely so that
     * re-processing the same document twice reproduces the same probe result — useful for
     * debugging a rejection — not because the specific value carries any meaning.
     */
    private static final long STAGE2_PROBE_SEED = 677L;

    /**
     * Tempdoc 677 Stage 2: resolves an AMBIGUOUS Stage-1 verdict by re-sampling ONE page — the
     * page whose Pass 1 signals were worst among those sent to the model ({@link
     * #worstSignalIndex}) — and comparing its original Pass 1 text against the re-sample via
     * {@link VduAbstentionGate#jaccardAgreement}.
     *
     * <p><b>Why only one page, not every page:</b> probing every page would cost N extra
     * inference calls per ambiguous document; the worst-signal page is the most informative single
     * page to re-sample (if the document's output is confabulated, the worst page is the most
     * likely to expose it) — one extra call per ambiguous document is the price of resolving the
     * ambiguity (tempdoc 677 task: "run the probe on the SINGLE page with the worst page-level
     * signals... one extra inference per ambiguous doc, the worst page is the most informative").
     *
     * @param legiblePageIndices indices into {@code pageImages} that were sent to the model, in
     *     the same order as {@code sentPageResults}
     * @param pageImages every rendered page image (the full Stage 0 list)
     * @param sentPageResults Pass 1 results for the pages sent, same order/index as {@code
     *     legiblePageIndices}
     * @param filePath the document being processed (log context only)
     * @return the Stage 2 verdict: {@link GateVerdict#rejected()} when the probe disagrees with
     *     Pass 1 (agreement below {@link VduAbstentionGate#AGREEMENT_FLOOR}), otherwise a passing
     *     verdict; either way {@link GateVerdict#agreement()} and {@link GateVerdict#probedPage()}
     *     (1-based) are populated as evidence
     */
    private GateVerdict runAgreementProbe(
        List<Integer> legiblePageIndices,
        List<Path> pageImages,
        List<OnlineAiService.VisionCompletionResult> sentPageResults,
        Path filePath, io.justsearch.core.context.EngineContext engineContext, BooleanSupplier eligible)
            throws IOException, EligibilityDeferred {
        int worstIdx = worstSignalIndex(sentPageResults);
        int pageIndex = legiblePageIndices.get(worstIdx);
        String originalPageText = sentPageResults.get(worstIdx).content();

        byte[] imageBytes = imagePreparer.prepare(pageImages.get(pageIndex));
        requireEligible(eligible);
        OnlineAiService.VisionCompletionResult probeResult = awaitDocument(
            asVduBulkWork(() -> aiService.visionCompletionDetailed(
                    PASS1_PROMPT, imageBytes, PASS1_MAX_TOKENS,
                    SamplingParams.VDU_PROBE, STAGE2_PROBE_SEED, engineContext))
                .orTimeout(VDU_VISION_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        double agreement = VduAbstentionGate.jaccardAgreement(originalPageText, probeResult.content());
        LOG.info("VDU Stage 2 agreement probe for {} page {}: agreement={}",
            filePath.getFileName(), pageIndex + 1, agreement);

        return VduAbstentionGate.agreementVerdict(agreement).withProbedPage(pageIndex + 1);
    }

    /** Terminal cancellation abandons the document and interrupts its owned HTTP task as well. */
    private static <T> T awaitDocument(java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            try { future.cancel(true); }
            catch (RuntimeException | Error cleanup) { interrupted.addSuppressed(cleanup); }
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CompletionException(interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new java.util.concurrent.CompletionException(failed.getCause());
        }
    }

    /**
     * Spec E step E2: VDU's own generation is bulk work, not chat, so its leases are not counted as
     * chat generating and do not slow indexing (Spec E §1.2; RV3 F19).
     */
    private static <T> T asVduBulkWork(java.util.function.Supplier<T> call) {
        return io.justsearch.app.api.chat.DemandScope.bulk(
            io.justsearch.app.api.status.operating.BulkWorkKind.VDU, call);
    }

    /**
     * Picks the index (into {@code sentPageResults}, NOT {@code pageImages}) of the page with the
     * worst per-page confidence signal — the lowest {@code meanLogprob} among pages that reported
     * one. A page with no logprob signal (NO SIGNAL, null) is never picked over one that reported
     * an actual value, since there's nothing to compare a re-sample against for a page the gate
     * has no opinion on. If EVERY page has a null meanLogprob (uniform NO SIGNAL — the whole
     * reason this document reached the AMBIGUOUS band via the lowConfidenceFraction arm instead),
     * this falls back to page 0 — an arbitrary but harmless choice, since no signal exists to
     * distinguish any page from any other in that case.
     */
    private static int worstSignalIndex(List<OnlineAiService.VisionCompletionResult> sentPageResults) {
        int worst = 0;
        Double worstLogprob = null;
        for (int i = 0; i < sentPageResults.size(); i++) {
            Double logprob = sentPageResults.get(i).meanLogprob();
            if (logprob == null) {
                continue;
            }
            if (worstLogprob == null || logprob < worstLogprob) {
                worstLogprob = logprob;
                worst = i;
            }
        }
        return worst;
    }

    /**
     * Result of VDU processing.
     *
     * @param completeCoverage every source page was accepted without token truncation
     * @param sourceSha256 hash of the immutable source snapshot used throughout processing
     * @param gateVerdict the tempdoc 677 abstention cascade's verdict on this document —
     *     {@link GateVerdict#passed()} when neither Stage 0 nor Stage 1 rejected it. The 3-arg
     *     constructor below defaults to {@code passed()} for callers (tests, mocks) predating the
     *     gate that have no opinion on it.
     */
    public record VduResult(String extractedText, String enrichment, int pageCount, GateVerdict gateVerdict,
                            boolean completeCoverage, String sourceSha256) {

        public VduResult(String extractedText, String enrichment, int pageCount, GateVerdict gateVerdict) {
            this(extractedText, enrichment, pageCount, gateVerdict, true, "");
        }

        /** Back-compat constructor: assumes the gate passed (no rejection). */
        public VduResult(String extractedText, String enrichment, int pageCount) {
            this(extractedText, enrichment, pageCount, GateVerdict.passed());
        }
    }

    /** VDU-specific exception for better error handling. */
    public static class VduException extends Exception {
        public VduException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The chat controller refused VDU entry before launching or relaunching llama-server (a reload
     * back-off, a load in progress, GPU pressure and the like). The automatic-pass breaker does not
     * count it: nothing was launched for picture reading.
     */
    public static final class EntryRefusedWithoutLaunch extends VduException {
        public EntryRefusedWithoutLaunch(String message, ChatRefusal cause) {
            super(message, cause);
        }
    }
}
