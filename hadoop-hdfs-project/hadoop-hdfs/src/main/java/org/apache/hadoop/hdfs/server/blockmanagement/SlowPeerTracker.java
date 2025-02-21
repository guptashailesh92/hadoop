/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfs.server.blockmanagement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.apache.hadoop.classification.VisibleForTesting;
import org.apache.hadoop.thirdparty.com.google.common.collect.ImmutableMap;
import org.apache.hadoop.thirdparty.com.google.common.primitives.Ints;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.server.protocol.OutlierMetrics;
import org.apache.hadoop.hdfs.server.protocol.SlowPeerReports;
import org.apache.hadoop.util.JacksonUtil;
import org.apache.hadoop.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;


/**
 * This class aggregates information from {@link SlowPeerReports} received via
 * heartbeats.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class SlowPeerTracker {
  public static final Logger LOG =
      LoggerFactory.getLogger(SlowPeerTracker.class);

  /**
   * Time duration after which a report is considered stale. This is
   * set to DFS_DATANODE_OUTLIERS_REPORT_INTERVAL_KEY * 3 i.e.
   * maintained for at least two successive reports.
   */
  private final long reportValidityMs;

  /**
   * Timer object for querying the current time. Separated out for
   * unit testing.
   */
  private final Timer timer;

  /**
   * ObjectWriter to convert JSON reports to String.
   */
  private static final ObjectWriter WRITER = JacksonUtil.getSharedWriter();

  /**
   * Number of nodes to include in JSON report. We will return nodes with
   * the highest number of votes from peers.
   */
  private volatile int maxNodesToReport;

  /**
   * Information about peers that have reported a node as being slow.
   * Each outer map entry is a map of (DatanodeId) {@literal ->} (timestamp),
   * mapping reporting nodes to the timestamp of the last report from
   * that node.
   *
   * DatanodeId could be the DataNodeId or its address. We
   * don't care as long as the caller uses it consistently.
   *
   * Stale reports are not evicted proactively and can potentially
   * hang around forever.
   */
  private final ConcurrentMap<String, ConcurrentMap<String, LatencyWithLastReportTime>>
      allReports;

  public SlowPeerTracker(Configuration conf, Timer timer) {
    this.timer = timer;
    this.allReports = new ConcurrentHashMap<>();
    this.reportValidityMs = conf.getTimeDuration(
        DFSConfigKeys.DFS_DATANODE_OUTLIERS_REPORT_INTERVAL_KEY,
        DFSConfigKeys.DFS_DATANODE_OUTLIERS_REPORT_INTERVAL_DEFAULT,
        TimeUnit.MILLISECONDS) * 3;
    this.setMaxSlowPeersToReport(conf.getInt(DFSConfigKeys.DFS_DATANODE_MAX_NODES_TO_REPORT_KEY,
        DFSConfigKeys.DFS_DATANODE_MAX_NODES_TO_REPORT_DEFAULT));
  }

  /**
   * If SlowPeerTracker is enabled, return true, else returns false.
   *
   * @return true if slow peer tracking is enabled, else false.
   */
  public boolean isSlowPeerTrackerEnabled() {
    return true;
  }

  /**
   * Add a new report. DatanodeIds can be the DataNodeIds or addresses
   * We don't care as long as the caller is consistent.
   *
   * @param slowNode DataNodeId of the peer suspected to be slow.
   * @param reportingNode DataNodeId of the node reporting on its peer.
   * @param slowNodeMetrics Aggregate latency metrics of slownode as reported by the
   *     reporting node.
   */
  public void addReport(String slowNode, String reportingNode, OutlierMetrics slowNodeMetrics) {
    ConcurrentMap<String, LatencyWithLastReportTime> nodeEntries = allReports.get(slowNode);

    if (nodeEntries == null) {
      // putIfAbsent guards against multiple writers.
      allReports.putIfAbsent(slowNode, new ConcurrentHashMap<>());
      nodeEntries = allReports.get(slowNode);
    }

    // Replace the existing entry from this node, if any.
    nodeEntries.put(reportingNode,
        new LatencyWithLastReportTime(timer.monotonicNow(), slowNodeMetrics));
  }

  /**
   * Retrieve the non-expired reports that mark a given DataNode
   * as slow. Stale reports are excluded.
   *
   * @param slowNode target node Id.
   * @return set of reports which implicate the target node as being slow.
   */
  public Set<SlowPeerLatencyWithReportingNode> getReportsForNode(String slowNode) {
    final ConcurrentMap<String, LatencyWithLastReportTime> nodeEntries =
        allReports.get(slowNode);

    if (nodeEntries == null || nodeEntries.isEmpty()) {
      return Collections.emptySet();
    }

    return filterNodeReports(nodeEntries, timer.monotonicNow());
  }

  /**
   * Retrieve all reports for all nodes. Stale reports are excluded.
   *
   * @return map from SlowNodeId {@literal ->} (set of nodes reporting peers).
   */
  public Map<String, SortedSet<SlowPeerLatencyWithReportingNode>> getReportsForAllDataNodes() {
    if (allReports.isEmpty()) {
      return ImmutableMap.of();
    }

    final Map<String, SortedSet<SlowPeerLatencyWithReportingNode>> allNodesValidReports =
        new HashMap<>();
    final long now = timer.monotonicNow();

    for (Map.Entry<String, ConcurrentMap<String, LatencyWithLastReportTime>> entry
        : allReports.entrySet()) {
      SortedSet<SlowPeerLatencyWithReportingNode> validReports =
          filterNodeReports(entry.getValue(), now);
      if (!validReports.isEmpty()) {
        allNodesValidReports.put(entry.getKey(), validReports);
      }
    }
    return allNodesValidReports;
  }

  /**
   * Filter the given reports to return just the valid ones.
   *
   * @param reports Current set of reports.
   * @param now Current time.
   * @return Set of valid reports that were created within last reportValidityMs millis.
   */
  private SortedSet<SlowPeerLatencyWithReportingNode> filterNodeReports(
      ConcurrentMap<String, LatencyWithLastReportTime> reports, long now) {
    final SortedSet<SlowPeerLatencyWithReportingNode> validReports = new TreeSet<>();

    for (Map.Entry<String, LatencyWithLastReportTime> entry : reports.entrySet()) {
      if (now - entry.getValue().getTime() < reportValidityMs) {
        OutlierMetrics outlierMetrics = entry.getValue().getLatency();
        validReports.add(
            new SlowPeerLatencyWithReportingNode(entry.getKey(), outlierMetrics.getActualLatency(),
                outlierMetrics.getMedian(), outlierMetrics.getMad(),
                outlierMetrics.getUpperLimitLatency()));
      }
    }
    return validReports;
  }

  /**
   * Retrieve all valid reports as a JSON string.
   * @return serialized representation of valid reports. null if
   *         serialization failed.
   */
  public String getJson() {
    Collection<SlowPeerJsonReport> validReports = getJsonReports(
        maxNodesToReport);
    try {
      return WRITER.writeValueAsString(validReports);
    } catch (JsonProcessingException e) {
      // Failed to serialize. Don't log the exception call stack.
      LOG.debug("Failed to serialize statistics" + e);
      return null;
    }
  }

  /**
   * Returns all tracking slow peers.
   * @param numNodes
   * @return
   */
  public List<String> getSlowNodes(int numNodes) {
    Collection<SlowPeerJsonReport> jsonReports = getJsonReports(numNodes);
    ArrayList<String> slowNodes = new ArrayList<>();
    for (SlowPeerJsonReport jsonReport : jsonReports) {
      slowNodes.add(jsonReport.getSlowNode());
    }
    if (!slowNodes.isEmpty()) {
      LOG.warn("Slow nodes list: " + slowNodes);
    }
    return slowNodes;
  }

  /**
   * Retrieve reports in a structure for generating JSON, limiting the
   * output to the top numNodes nodes i.e nodes with the most reports.
   * @param numNodes number of nodes to return. This is to limit the
   *                 size of the generated JSON.
   */
  private Collection<SlowPeerJsonReport> getJsonReports(int numNodes) {
    if (allReports.isEmpty()) {
      return Collections.emptyList();
    }

    final PriorityQueue<SlowPeerJsonReport> topNReports = new PriorityQueue<>(allReports.size(),
        (o1, o2) -> Ints.compare(o1.getSlowPeerLatencyWithReportingNodes().size(),
            o2.getSlowPeerLatencyWithReportingNodes().size()));

    final long now = timer.monotonicNow();

    for (Map.Entry<String, ConcurrentMap<String, LatencyWithLastReportTime>> entry
        : allReports.entrySet()) {
      SortedSet<SlowPeerLatencyWithReportingNode> validReports =
          filterNodeReports(entry.getValue(), now);
      if (!validReports.isEmpty()) {
        if (topNReports.size() < numNodes) {
          topNReports.add(new SlowPeerJsonReport(entry.getKey(), validReports));
        } else if (topNReports.peek() != null
            && topNReports.peek().getSlowPeerLatencyWithReportingNodes().size()
            < validReports.size()) {
          // Remove the lowest element
          topNReports.poll();
          topNReports.add(new SlowPeerJsonReport(entry.getKey(), validReports));
        }
      }
    }
    return topNReports;
  }

  @VisibleForTesting
  long getReportValidityMs() {
    return reportValidityMs;
  }

  public synchronized void setMaxSlowPeersToReport(int maxSlowPeersToReport) {
    this.maxNodesToReport = maxSlowPeersToReport;
  }

  private static class LatencyWithLastReportTime {
    private final Long time;
    private final OutlierMetrics latency;

    LatencyWithLastReportTime(Long time, OutlierMetrics latency) {
      this.time = time;
      this.latency = latency;
    }

    public Long getTime() {
      return time;
    }

    public OutlierMetrics getLatency() {
      return latency;
    }
  }

}
