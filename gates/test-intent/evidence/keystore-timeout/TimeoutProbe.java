import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
public class TimeoutProbe {
  public static void main(String[] args) {
    var request = LauncherDiscoveryRequestBuilder.request()
        .selectors(DiscoverySelectors.selectMethod("io.justsearch.app.services.encryption.KeystoreDurabilityTest#processKillAtEachPublicationStepRetainsUnlockableData"))
        .configurationParameter("junit.jupiter.execution.timeout.default", "30s").build();
    var summary = new SummaryGeneratingListener();
    LauncherFactory.create().execute(request, summary);
    summary.getSummary().printTo(new java.io.PrintWriter(System.out, true));
    summary.getSummary().printFailuresTo(new java.io.PrintWriter(System.out, true));
    System.exit(summary.getSummary().getTestsSucceededCount() == 1 ? 0 : 1);
  }
}
