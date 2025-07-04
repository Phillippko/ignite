/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.persistence.db.wal;

import java.io.File;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.processors.cache.persistence.filename.NodeFileTree;
import org.apache.ignite.internal.util.lang.GridAbsPredicate;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

import static org.apache.ignite.internal.processors.cache.persistence.wal.FileWriteAheadLogManager.WAL_SEGMENT_TEMP_FILE_COMPACTED_FILTER;

/**
 * Load without compaction -> Stop -> Enable WAL Compaction -> Start.
 */
public class WalCompactionSwitchOnTest extends GridCommonAbstractTest {
    /** Compaction enabled. */
    private boolean compactionEnabled;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        cfg.setDataStorageConfiguration(new DataStorageConfiguration()
                .setDefaultDataRegionConfiguration(
                        new DataRegionConfiguration()
                            .setPersistenceEnabled(true)
                            .setMaxSize(256 * 1024 * 1024))
                .setWalSegmentSize(512 * 1024)
                .setWalSegments(100)
                .setWalCompactionEnabled(compactionEnabled));

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        cleanPersistenceDir();
    }

    /**
     * Load without compaction -> Stop -> Enable WAL Compaction -> Start.
     *
     * @throws Exception On exception.
     */
    @Test
    public void testWalCompactionSwitch() throws Exception {
        IgniteEx ex = startGrid(0);

        ex.cluster().state(ClusterState.ACTIVE);

        IgniteCache<Integer, Integer> cache = ex.getOrCreateCache(
            new CacheConfiguration<Integer, Integer>()
                    .setName("c1")
                    .setGroupName("g1")
                    .setCacheMode(CacheMode.PARTITIONED)
        );

        for (int i = 0; i < 500; i++)
            cache.put(i, i);

        NodeFileTree ft = ex.context().pdsFolderResolver().fileTree();

        forceCheckpoint();

        GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                File[] archivedFiles = ft.walSegments();

                return archivedFiles.length == 39;
            }
        }, 5000);

        stopGrid(0);

        compactionEnabled = true;

        ex = startGrid(0);

        ex.cluster().state(ClusterState.ACTIVE);

        File archiveDir = ex.context().pdsFolderResolver().fileTree().walArchive();

        GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                File[] archivedFiles = archiveDir.listFiles(NodeFileTree::walCompactedSegment);

                return archivedFiles.length == 20;
            }
        }, 5000);

        File[] tmpFiles = archiveDir.listFiles(WAL_SEGMENT_TEMP_FILE_COMPACTED_FILTER);

        assertEquals(0, tmpFiles.length);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();
    }
}
