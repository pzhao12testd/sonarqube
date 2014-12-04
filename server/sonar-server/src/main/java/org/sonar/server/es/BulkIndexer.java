/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.es;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequestBuilder;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.picocontainer.Startable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.server.util.ProgressTask;

import java.util.Map;
import java.util.Timer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Helper to bulk requests in an efficient way :
 * <ul>
 *   <li>bulk request is sent on the wire when its size is higher than 5Mb</li>
 *   <li>on large table indexing, replicas and automatic refresh can be temporarily disabled</li>
 *   <li>index refresh is optional (enabled by default)</li>
 * </ul>
 */
public class BulkIndexer implements Startable {

  private static final long FLUSH_BYTE_SIZE = new ByteSizeValue(5, ByteSizeUnit.MB).bytes();
  private static final String REFRESH_INTERVAL_SETTING = "index.refresh_interval";

  private final EsClient client;
  private final String indexName;
  private boolean large = false;
  private boolean refresh = true;
  private long flushByteSize = FLUSH_BYTE_SIZE;
  private BulkRequestBuilder bulkRequest = null;
  private Map<String, Object> largeInitialSettings = null;

  private final AtomicLong counter = new AtomicLong(0L);
  private final ProgressTask progressTask = new ProgressTask(counter, LoggerFactory.getLogger("BulkIndex")).setRowPluralName("requests");
  private final Timer timer = new Timer("Bulk index progress");

  public BulkIndexer(EsClient client, String indexName) {
    this.client = client;
    this.indexName = indexName;
  }

  /**
   * Large indexing is an heavy operation that populates an index generally from scratch. Replicas and
   * automatic refresh are disabled during bulk indexing and lucene segments are optimized at the end.
   */

  public BulkIndexer setLarge(boolean b) {
    Preconditions.checkState(bulkRequest == null, "Bulk indexing is already started");
    this.large = b;
    return this;
  }

  public BulkIndexer setRefresh(boolean b) {
    Preconditions.checkState(bulkRequest == null, "Bulk indexing is already started");
    this.refresh = b;
    return this;
  }

  /**
   * Default value is {@link org.sonar.server.es.BulkIndexer#FLUSH_BYTE_SIZE}
   * @see org.elasticsearch.common.unit.ByteSizeValue
   */
  public BulkIndexer setFlushByteSize(long l) {
    this.flushByteSize = l;
    return this;
  }

  @Override
  public void start() {
    Preconditions.checkState(bulkRequest == null, "Bulk indexing is already started");
    if (large) {
      largeInitialSettings = Maps.newHashMap();
      Map<String, Object> bulkSettings = Maps.newHashMap();
      GetSettingsResponse settingsResp = client.nativeClient().admin().indices().prepareGetSettings(indexName).get();

      // deactivate replicas
      int initialReplicas = Integer.parseInt(settingsResp.getSetting(indexName, IndexMetaData.SETTING_NUMBER_OF_REPLICAS));
      if (initialReplicas > 0) {
        largeInitialSettings.put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, initialReplicas);
        bulkSettings.put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0);
      }

      // deactivate periodical refresh
      String refreshInterval = settingsResp.getSetting(indexName, REFRESH_INTERVAL_SETTING);
      largeInitialSettings.put(REFRESH_INTERVAL_SETTING, refreshInterval);
      bulkSettings.put(REFRESH_INTERVAL_SETTING, "-1");

      updateSettings(bulkSettings);
    }
    bulkRequest = client.prepareBulk();
    timer.schedule(progressTask, ProgressTask.PERIOD_MS, ProgressTask.PERIOD_MS);
  }

  public void add(ActionRequest request) {
    bulkRequest.request().add(request);
    counter.getAndIncrement();
    if (bulkRequest.request().estimatedSizeInBytes() >= flushByteSize) {
      executeBulk(bulkRequest);
      bulkRequest = client.prepareBulk();
    }
  }

  @Override
  public void stop() {
    if (bulkRequest.numberOfActions() > 0) {
      executeBulk(bulkRequest);
    }

    // Log final advancement and reset counter
    progressTask.log();
    counter.set(0L);
    timer.cancel();
    timer.purge();

    if (refresh) {
      client.prepareRefresh(indexName).get();
    }
    if (large) {
      // optimize lucene segments and revert index settings
      // Optimization must be done before re-applying replicas:
      // http://www.elasticsearch.org/blog/performance-considerations-elasticsearch-indexing/
      // TODO do not use nativeClient, else request is not profiled
      client.nativeClient().admin().indices().prepareOptimize(indexName)
        .setMaxNumSegments(1)
        .setWaitForMerge(true)
        .get();

      updateSettings(largeInitialSettings);
    }
    bulkRequest = null;
  }

  private void updateSettings(Map<String, Object> settings) {
    UpdateSettingsRequestBuilder req = client.nativeClient().admin().indices().prepareUpdateSettings(indexName);
    req.setSettings(settings);
    req.get();
  }

  private void executeBulk(BulkRequestBuilder bulkRequest) {
    bulkRequest.get();

    // TODO check failures
    // WARNING - complexity of response#hasFailures() and #buildFailureMessages() is O(n)
  }
}
