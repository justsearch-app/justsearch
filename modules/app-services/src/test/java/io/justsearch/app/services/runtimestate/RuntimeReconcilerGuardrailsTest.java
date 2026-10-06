/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;

/**
 * Single-writer enforcement for the managed chat server (tempdoc 737 §12a; Spec A step 2).
 *
 * <p>The chat controller ({@code io.justsearch.app.services.chat.ChatServerControllerImpl}) is the
 * guarded single writer: it alone calls the actuation primitives the lifecycle manager exposes
 * ({@link ChatActuator}), on its one thread. The composition root
 * ({@code BootstrapInferenceFactory}) alone installs it. Every other start, stop or relaunch request
 * becomes a step the controller serializes and coalesces.
 *
 * <p>{@link RuntimeReconciler} stays the only {@code app-services} class that forwards spec and
 * procedure intent as a mode switch. Two rules cover both declarations of the switch primitives:
 *
 * <ul>
 *   <li>the {@link OnlineAiLifecycleControl}-declared {@code switchToOnlineMode()} /
 *       {@code switchToIndexingMode()} (the bootstrap / VDU / reconciler surface), and
 *   <li>the {@link OnlineAiService}-declared default overloads of the same names (the user-facing
 *       surface the install self-test and the {@code switchInferenceMode} operation used to call
 *       directly — now routed through the reconciler / the {@code core.set-chat-enabled} spec write,
 *       tempdoc 737 fix pack fix 3/4).
 * </ul>
 *
 * <p><b>Fix pack:</b> the only remaining {@code OnlineAiService}-declared caller is
 * {@code AiInstallService}'s smoke-test legacy fallback (used only when no reconciler is wired),
 * allowlisted below with a {@code LEGACY-FALLBACK} marker.
 */
@AnalyzeClasses(
    packages = "io.justsearch.app.services",
    importOptions = ImportOption.DoNotIncludeTests.class)
class RuntimeReconcilerGuardrailsTest {

  /** Spec A step 2 (reconciliation F4): only the controller calls the actuation primitives. */
  @ArchTest
  static final ArchRule onlyChatControllerMayCallActuationPrimitives =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.services..")
          // The single writer.
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.chat.ChatServerControllerImpl")
          .should()
          .callMethod(ChatActuator.class, "drain", java.time.Duration.class)
          .orShould()
          .callMethod(ChatActuator.class, "execute", ChatActuation.class,
              ChatActuator.DrainHold.class)
          .orShould()
          .callMethod(ChatActuator.class, "terminalStop", java.time.Duration.class);

  /** Only the composition root installs the controller and hands it the primitives. */
  @ArchTest
  static final ArchRule onlyCompositionRootInstallsTheChatController =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.services..")
          .and()
          .doNotHaveFullyQualifiedName(
              "io.justsearch.app.services.bootstrap.BootstrapInferenceFactory")
          .should()
          .callMethod(InferenceLifecycleManager.class, "installActuationRouter",
              ChatActuationRouter.class)
          .orShould()
          .callMethod(InferenceLifecycleManager.class, "chatActuator");

  @ArchTest
  static final ArchRule onlyReconcilerMayDriveLifecycleTransitions =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.services..")
          // The only forwarder of spec and procedure intent.
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.runtimestate.RuntimeReconciler")
          .should()
          .callMethod(OnlineAiLifecycleControl.class, "switchToOnlineMode")
          .orShould()
          .callMethod(OnlineAiLifecycleControl.class, "switchToIndexingMode");

  /**
   * Tempdoc 737 fix pack (fix 5): the {@code OnlineAiService}-declared default overloads must not be
   * called directly either. Allowlist: {@link RuntimeReconciler} (the intent forwarder) and
   * {@code AiInstallService} (its smoke-test // LEGACY-FALLBACK raw call, live only when no
   * reconciler is wired — a test / non-configured construction).
   */
  @ArchTest
  static final ArchRule onlyReconcilerMayCallServiceSwitchPrimitives =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.services..")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.runtimestate.RuntimeReconciler")
          // LEGACY-FALLBACK: AiInstallService.smokeTestBestEffort keeps a raw switchToOnlineMode()
          // ONLY for the no-reconciler path (test / non-configured); production wires the reconciler
          // and takes the procedureRequireEngine path.
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.app.services.ai.install.AiInstallService")
          .should()
          .callMethod(OnlineAiService.class, "switchToOnlineMode")
          .orShould()
          .callMethod(OnlineAiService.class, "switchToIndexingMode");
}
