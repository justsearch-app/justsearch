/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.ControlledRealTimeReopenThread;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;

record Components(
    boolean commitMetadataEnabled,
    Directory directory,
    IndexWriter writer,
    SearcherManager searcherManager,
    ControlledRealTimeReopenThread<IndexSearcher> crtrt,
    Path indexPath,
    boolean ephemeralPath,
    String softDeleteField,
    String idField,
    KnnVectorsFormat knnVectorsFormat,
    int vectorEfSearch,
    Analyzer indexAnalyzer,
    ResolvedConfig resolvedConfig,
    long nrtTargetMaxStaleMs,
    long nrtHardMaxStaleMs,
    NrtMode nrtMode,
    long nrtOnDemandMaxStaleMs,
    long reopenTargetMs,
    long reopenHardMs,
    IndexRuntimeConfiguration runtimeConfiguration) {}
