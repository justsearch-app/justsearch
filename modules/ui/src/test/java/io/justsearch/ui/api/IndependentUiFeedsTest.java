package io.justsearch.ui.api;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
/** Further actual UI observation paths found in the shared feed and Health implementations. */
public class IndependentUiFeedsTest {
 @ParameterizedTest @ValueSource(strings={"/api/shell-events/stream","/api/indexing-jobs/stream","/api/condition-recovery-index/stream","/api/indexing/failed-jobs"})
 void unattendedFeedsDoNotCreateActivity(String path) throws Exception {
  new IndependentObserverHttpTest().automaticUiTimerDoesNotChangeCapturedActivity(path);
 }
}
