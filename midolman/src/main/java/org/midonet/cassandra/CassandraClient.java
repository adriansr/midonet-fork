/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.cassandra;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.cassandra.service.CassandraHostConfigurator;
import me.prettyprint.cassandra.service.ExhaustedPolicy;
import me.prettyprint.cassandra.service.FailoverPolicy;
import me.prettyprint.hector.api.Cluster;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.beans.ColumnSlice;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.beans.OrderedRows;
import me.prettyprint.hector.api.beans.Row;
import me.prettyprint.hector.api.beans.Rows;
import me.prettyprint.hector.api.ddl.ColumnFamilyDefinition;
import me.prettyprint.hector.api.ddl.ComparatorType;
import me.prettyprint.hector.api.ddl.KeyspaceDefinition;
import me.prettyprint.hector.api.exceptions.HInvalidRequestException;
import me.prettyprint.hector.api.exceptions.HectorException;
import me.prettyprint.hector.api.factory.HFactory;
import me.prettyprint.hector.api.mutation.Mutator;
import me.prettyprint.hector.api.query.ColumnQuery;
import me.prettyprint.hector.api.query.MultigetSliceQuery;
import me.prettyprint.hector.api.query.QueryResult;
import me.prettyprint.hector.api.query.RangeSlicesQuery;
import me.prettyprint.hector.api.query.SliceQuery;
import org.apache.cassandra.locator.SimpleStrategy;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkLock;
import org.midonet.util.eventloop.Reactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CassandraClient {

    private static final Logger log =
            LoggerFactory.getLogger(CassandraClient.class);

    private static class CassandraConnection {
        private Cluster cluster;
        private Keyspace keyspace;

        CassandraConnection(Cluster cl, Keyspace ks) {
            this.cluster = cl;
            this.keyspace = ks;
        }
    }

    private CassandraConnection conn = null;

    private final String servers;
    private final int maxActiveConns;
    private final String clusterName;
    private final String keyspaceName;
    private final String columnFamily;
    private final int replicationFactor;
    private final int thriftSocketTimeout;
    private final int expirationSecs;
    private final boolean hostTimeoutTracker;
    private final int hostTimeoutCounter;
    private final int hostTimeoutWindow;
    private final Reactor reactor;
    private final ZkLock lock;

    private static StringSerializer ss = StringSerializer.get();

    public CassandraClient(String servers, int maxActiveConns,
                           String clusterName, String keyspaceName,
                           String columnFamily, int replicationFactor,
                           int expirationSecs, int thriftSocketTimeout,
                           boolean hostTimeoutTracker, int hostTimeoutCounter,
                           int hostTimeoutWindow, Reactor reactor,
                           ZkLock lock) {
        this.servers = servers;
        this.maxActiveConns = maxActiveConns;
        this.clusterName = clusterName;
        this.keyspaceName = keyspaceName;
        this.columnFamily = columnFamily;
        this.replicationFactor = replicationFactor;
        this.expirationSecs = expirationSecs;
        this.thriftSocketTimeout = thriftSocketTimeout;
        this.hostTimeoutTracker = hostTimeoutTracker;
        this.hostTimeoutCounter = hostTimeoutCounter;
        this.hostTimeoutWindow = hostTimeoutWindow;
        this.reactor = reactor;
        this.lock = lock;
    }

    public synchronized void connect() throws HectorException {
        boolean success = false;
        if (conn != null)
            return;

        Cluster cluster = null;
        Keyspace keyspace = null;

        CassandraHostConfigurator config = new CassandraHostConfigurator();
        config.setHosts(servers);
        config.setMaxActive(maxActiveConns);
        config.setUseSocketKeepalive(true);
        config.setRetryDownedHosts(true);
        config.setRetryDownedHostsDelayInSeconds(5);
        config.setCassandraThriftSocketTimeout(thriftSocketTimeout);
        // This controls the time that requests will wait for a connection
        // to be restored when hector has no clients available in a given
        // host's pool. This may happen for example if we're getting
        // timeouts to a host - this would close the clients, but not
        // remove them from the pool so Hector will block trying to fetch
        // a healthy client from the pool. We want to keep this short
        // so that we return from the request, and trigger a failure that
        // makes Hector mark the host as down. Should be unnecessary
        // given the ExhaustedPolicy below, but just to be safe.
        config.setMaxWaitTimeWhenExhausted(1000);
        config.setExhaustedPolicy(ExhaustedPolicy.WHEN_EXHAUSTED_FAIL);
        // These determine how many timeouts are allowed in a given host
        // within the given window (in ms) before the node gets suspended
        // and becomes non eligible for future operations.
        config.setUseHostTimeoutTracker(hostTimeoutTracker);
        config.setHostTimeoutCounter(hostTimeoutCounter);
        config.setHostTimeoutWindow(hostTimeoutWindow);

        try {
            lock.lock(Long.MAX_VALUE);
        } catch (StateAccessException e) {
            log.error("Failed to acquire ZK lock. Retrying.");
            scheduleReconnect();
            return;
        }
        try {
            cluster = HFactory.getOrCreateCluster(clusterName, config);
            keyspace = HFactory.createKeyspace(
                    keyspaceName, cluster,
                    HFactory.createDefaultConsistencyLevelPolicy(),
                    FailoverPolicy.ON_FAIL_TRY_ALL_AVAILABLE);

            log.debug("Check column family {} exists in keyspace {}",
                    columnFamily, keyspace);

            KeyspaceDefinition ksDef = cluster.describeKeyspace(keyspaceName);
            if (ksDef == null) {
                log.info("Creating keyspace {} in cluster {}",
                        keyspaceName, clusterName);
                ksDef = HFactory.createKeyspaceDefinition(
                        keyspaceName, SimpleStrategy.class.getName(),
                        replicationFactor, null);
                try {
                    cluster.addKeyspace(ksDef, true);
                } catch (HInvalidRequestException e) {
                    log.info("Someone beat us to creating keyspace {}",
                            keyspaceName);
                }
                ksDef = cluster.describeKeyspace(keyspaceName);
            }

            for (ColumnFamilyDefinition cfDef : ksDef.getCfDefs()) {
                if (cfDef.getName().equals(columnFamily)) {
                    log.debug("Column family {} found in keyspace {}",
                            columnFamily, keyspaceName);
                    success = true;
                    return;
                }
            }

            // Create a column family if it doesn't exist.
            log.info("Creating column family {}", columnFamily);
            ColumnFamilyDefinition cfDef =
                    HFactory.createColumnFamilyDefinition(
                            keyspaceName, columnFamily,
                            ComparatorType.BYTESTYPE);
            cfDef.setKeyValidationClass("BytesType");
            cfDef.setDefaultValidationClass("BytesType");
            try {
                cluster.addColumnFamily(cfDef, true);
            } catch (HInvalidRequestException e) {
                log.info("Someone beat us to creating column family {}",
                        columnFamily);
            }

            success = true;
        } catch (HectorException ex) {
            scheduleReconnect();
        } finally {
            lock.unlock();
            if (success) {
                log.info("Connection to Cassandra {}:{} ESTABLISHED",
                         keyspaceName, columnFamily);
                this.conn = new CassandraConnection(cluster, keyspace);
            } else {
                if (cluster != null)
                    HFactory.shutdownCluster(cluster);
                log.error("Connection to Cassandra {}:{} FAILED",
                          keyspaceName, columnFamily);
            }
        }

    }

    private synchronized void handleHectorException(HectorException ex) {
        // FIXME - guillermo, this ugly hack works around the fact that hector
        // cannot recover from an 'All host pools marked down' error and neither
        // does it offer an exception subclass or error code to signal that case.
        if (ex.getMessage().contains("All host pools marked down.")) {
            if (this.conn != null) {
                scheduleReconnect();
            }
        }
    }

    private synchronized void scheduleReconnect() {
        if (this.conn != null) {
            HFactory.shutdownCluster(this.conn.cluster);
            this.conn = null;
        }

        log.info("Scheduling cassandra reconnection retry");
        if (reactor != null) {
            reactor.schedule(new Runnable() {
                @Override
                public void run() {
                    log.info("Trying to reconnect to cassandra");
                    connect();
                }
            }, 5, TimeUnit.SECONDS);
        } else {
            log.error("Permanently lost connection to cassandra and there is "+
                      "no reactor to schedule reconnects");
        }
    }

    public void set(String key, String value, String newColumn) {
        setWithExpiration(key, value, newColumn, expirationSecs);
    }

    public void setWithExpiration(String key, String value, String newColumn,
                                  int overrideExpirationSeconds) {
        CassandraConnection c = this.conn;
        if (c == null) {
            log.debug("skipping write op while disconnected from cassandra");
            return;
        }
        try {
            // Mutator is not thread safe, cannot be accessed by different threads
            // at the same time. See the class or
            // http://comments.gmane.org/gmane.comp.db.hector.user/5046
            // We create a new one every time and only one thread will use it, should
            // be fine.
            Mutator<String> mutator = HFactory.createMutator(c.keyspace, ss);
            mutator.insert(key, columnFamily,
                           HFactory.createColumn(newColumn, value,
                                                 overrideExpirationSeconds,
                                                 ss, ss));
        } catch (HectorException e) {
            log.error("set failed", e);
            handleHectorException(e);
        }
    }

    public String get(String key, String columnName) {
        CassandraConnection c = this.conn;
        if (c == null) {
            log.debug("skipping read op while disconnected from cassandra");
            return null;
        }

        // All get operations in Hector are performed by ExecutingKeyspace.doExecute
        // which is thread safe
        HColumn<String, String> result = null;
        try {
            ColumnQuery<String, String, String> query =
                    HFactory.createColumnQuery(c.keyspace, ss, ss, ss);
            query.setColumnFamily(columnFamily).setKey(key).setName(columnName);
            result = query.execute().get();
        } catch (HectorException e) {
            log.error("get failed", e);
            handleHectorException(e);
            return null;
        }

        if (result == null) {
            return null;
        }
        String value = result.getValue();
        return (value.length() != 0) ? value : null;
    }

    // columnName can be null, in which case the whole column is removed
    public void delete(String key, String columnName) {
        CassandraConnection c = this.conn;
        if (c == null) {
            log.debug("skipping read op while disconnected from cassandra");
            return;
        }

        try {
            Mutator<String> mutator = HFactory.createMutator(c.keyspace, ss);
            mutator.delete(key, columnFamily, columnName, ss);
        } catch (HectorException e) {
            log.error("delete failed", e);
        }
    }

    public <T> Map<String, T> executeSliceQuery(String key, String startRange,
                                                String endRange,
                                                Class<T> returnValueClass,
                                                int maxResultItems) {
        Map<String, T> res = new HashMap<String, T>();

        CassandraConnection c = this.conn;
        if (c == null) {
            log.debug("skipping query while disconnected from cassandra");
            return res;
        }

        try {
            SliceQuery<String, String, String> sliceQuery = HFactory.createSliceQuery(
                    c.keyspace, ss, ss, ss);
            sliceQuery.setColumnFamily(columnFamily);
            sliceQuery.setKey(key);
            sliceQuery.setRange(startRange, endRange, false, maxResultItems);

            QueryResult<ColumnSlice<String, String>> result = sliceQuery.execute();
            for (HColumn<String, String> entry : result.get().getColumns()) {
                T value = convertSafely(returnValueClass,
                                        entry.getValue());
                res.put(entry.getName(), value);
            }
        } catch (HectorException e) {
            log.error("slice query failed", e);
            handleHectorException(e);
        }
        return res;
    }

    public <T> Map<String, T> executeSliceQuery(List<String> keys,
                                                String startRange,
                                                String endRange,
                                                Class<T> returnValueClass,
                                                int maxResultItems) {
        Map<String, T> res = new HashMap<String, T>();

        CassandraConnection c = this.conn;
        if (c == null) {
            log.debug("skipping query while disconnected from cassandra");
            return res;
        }

        try {
            MultigetSliceQuery<String, String, String> sliceQuery = HFactory.createMultigetSliceQuery(
                    c.keyspace, ss, ss, ss);
            sliceQuery.setColumnFamily(columnFamily);
            sliceQuery.setKeys(keys);
            sliceQuery.setRange(startRange, endRange, false, maxResultItems);
            QueryResult<Rows<String, String, String>> result = sliceQuery.execute();
            for (Row<String, String, String> row : result.get()) {
                for (HColumn<String, String> entry : row.getColumnSlice()
                                                        .getColumns()) {
                    T value = convertSafely(returnValueClass,
                                            entry.getValue());
                    res.put(entry.getName(), value);
                }
            }
        } catch (HectorException e) {
            log.error("multiget slice query failed", e);
            handleHectorException(e);
        }
        return res;
    }

    public <T> List<T> getAllColumnsValues(String key,
                                           Class<T> returnValueClass,
                                           int maxResultItems) {
        List<T> res = new ArrayList<T>();

        CassandraConnection c = this.conn;
        if (c == null) {
            log.debug("skipping query while disconnected from cassandra");
            return res;
        }

        try {
            SliceQuery<String, String, String> sliceQuery = HFactory.createSliceQuery(
                    c.keyspace, ss, ss, ss);
            sliceQuery.setColumnFamily(columnFamily);
            sliceQuery.setKey(key);
            sliceQuery.setRange(null, null, false, maxResultItems);

            QueryResult<ColumnSlice<String, String>> result = sliceQuery.execute();
            for (HColumn<String, String> entry : result.get().getColumns()) {
                T value = convertSafely(returnValueClass,
                                        entry.getValue());
                res.add(value);
            }
        } catch (HectorException e) {
            log.error("getAllColumnsValues failed", e);
            handleHectorException(e);
            return null;
        }
        return res;
    }

    private <T> T convertSafely(Class<T> returnValueClass,
                                String entryValue) {
        T value = null;
        try {
            Constructor constructor = returnValueClass.getConstructor(
                    new Class[]{String.class});
            value = returnValueClass.cast(constructor.newInstance(entryValue));
        } catch (Exception e) {
            log.error("Error in casting the value from String to {}",
                      new Object[]{returnValueClass.toString(), e});
        }
        return value;
    }

    public int getExpirationSeconds() {
        return expirationSecs;
    }

    public <T> Map<String, List<T>> dumpTable(String startRange,
                                              String endRange,
                                              Class<T> returnValueClass,
                                              int maxRowCount,
                                              int maxResultItems) {
        Map<String, List<T>> res = new HashMap<String, List<T>>();
        List<T> retList = new ArrayList<T>();
        CassandraConnection c = this.conn;
        if (c == null) {
            log.debug("skipping query while disconnected from cassandra");
            return res;
        }

        try {
            RangeSlicesQuery<String, String, String> sliceQuery = HFactory.createRangeSlicesQuery(
                    c.keyspace, ss, ss, ss);
            sliceQuery.setColumnFamily(columnFamily);
            /*
             * maxResultItems==0 means caller wants all the entries
             * in this column family; though Hector mandates that
             * there has to be an actual maximum value in a slice query
             */
            int maxColumnsPerFetch = 1000;
            sliceQuery.setRange(startRange, endRange, false,
                    (maxResultItems==0)? maxColumnsPerFetch :maxResultItems);
            sliceQuery.setKeys("", "");
            int maxRowsPerFetch = 1000;
            sliceQuery.setRowCount((maxRowCount==0)?
                                       maxRowsPerFetch :maxRowCount);

            QueryResult<OrderedRows<String, String, String>> result =
                                        sliceQuery.execute();
            OrderedRows<String, String, String> orderedRows = result.get();
            for (Row<String, String, String> r : orderedRows) {
                String key = r.getKey();
                if (r.getColumnSlice().getColumns().isEmpty()) {
                    // filter out the tombstones
                    log.debug("Entry {} contains no data - tombstone", key);
                } else {
                    for (HColumn<String, String> entry :
                        r.getColumnSlice().getColumns()) {
                        T value = convertSafely(returnValueClass,
                                            entry.getValue());
                        retList.add(value);
                    }
                    res.put(key, retList);
                }
            }
        } catch (HectorException e) {
            log.error("slice query failed", e);
        }
        return res;
    }
}
