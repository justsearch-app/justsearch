/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JobQueryPlansTest {
  @TempDir Path temp;

  @Test void everyStatusReadAndBothClaimSourcesSearchIndexesWithoutScanningJobs() throws Exception {
    try(var queue=new SqliteJobQueue(temp.resolve("plans.db"))) {
      queue.open();queue.enqueue(List.of(temp.resolve("a"),temp.resolve("b"),temp.resolve("c")));
      Connection original=JobCountsTest.connection(queue);
      try(var stmt=original.createStatement()) {stmt.executeUpdate("UPDATE jobs SET retry_after=0 WHERE path LIKE '%/b'");}
      var sqls=new LinkedHashSet<String>();
      Connection observed=mock(Connection.class,org.mockito.AdditionalAnswers.delegatesTo(original));
      doAnswer(call -> {
        String sql=call.getArgument(0);sqls.add(sql);return original.prepareStatement(sql);
      }).when(observed).prepareStatement(anyString());
      doAnswer(call -> {
        var stmt=original.createStatement();
        var observedStatement=mock(java.sql.Statement.class,org.mockito.AdditionalAnswers.delegatesTo(stmt));
        doAnswer(query -> {String sql=query.getArgument(0);sqls.add(sql);return stmt.executeQuery(sql);})
            .when(observedStatement).executeQuery(anyString());
        return observedStatement;
      }).when(observed).createStatement();
      var field=SqliteJobQueue.class.getDeclaredField("connection");field.setAccessible(true);field.set(queue,observed);
      try {
        queue.jobStateCounts();queue.jobStateCountsStrict();queue.queueDepth();queue.pendingBytes();
        queue.completedCount();queue.failureSummary();assertTrue(queue.hasPendingIngestWork());
        assertEquals(1,queue.pollPending(1).size());assertEquals(1,queue.pollPending(1).size());
      } finally {field.set(queue,original);}
      var plans=new ArrayList<String>();
      for(String sql:sqls) {
        if(!sql.stripLeading().startsWith("SELECT") && !sql.startsWith("UPDATE jobs INDEXED"))continue;
        try(var explain=original.prepareStatement("EXPLAIN QUERY PLAN "+sql)) {
          for(int p=1;p<=explain.getParameterMetaData().getParameterCount();p++)explain.setLong(p,0);
          try(var rows=explain.executeQuery()) {
            while(rows.next()) {
              String plan=rows.getString(4);plans.add(plan);
              if(sql.contains("ORDER BY last_updated,path LIMIT"))
                assertFalse(plan.contains("TEMP B-TREE"),sql+" -> "+plan);
              assertFalse(plan.matches("(?i).*SCAN jobs(?:\\s.*)?"),sql+" -> "+plan);
            }
          }
        }
      }
      for(String index:List.of("idx_jobs_pending_ready","idx_jobs_pending_retry","idx_jobs_state_retry","sqlite_autoindex_jobs_1"))
        assertTrue(plans.stream().anyMatch(plan->plan.startsWith("SEARCH jobs") && plan.contains(index)),index+": "+plans);
      assertTrue(sqls.stream().anyMatch(sql->sql.contains("(last_updated,path)>")),"exercise resumed keyset seek");
    }
  }
}
