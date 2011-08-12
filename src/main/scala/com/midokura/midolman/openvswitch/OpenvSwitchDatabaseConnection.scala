/**
 * OpenvSwitchDatabaaseConnection.scala - OVSDB connection management classes.
 *
 * A pure Scala implementation of the Open vSwitch database protocol used to
 * configure bridges, ports, etc.
 *
 * This module can connect to the ovsdb daemon using a TCP server socket,
 * using a 'tcp:...' URL.  Other connection schemes (Unix domain, etc.) are
 * not supported.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.openvswitch

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import java.io.{IOException, InputStreamReader, OutputStreamWriter, Writer}
import java.net.{Socket, SocketException}
import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue}
import java.util.{UUID, Timer, TimerTask}

import org.codehaus.jackson.{JsonNode, JsonFactory, JsonGenerator, JsonParser}
import org.codehaus.jackson.node.JsonNodeFactory
import org.codehaus.jackson.map.ObjectMapper
import org.slf4j.LoggerFactory

import com.fasterxml.jackson.module.scala.ScalaModule


/**
 * Static methods and constants for OpenvSwitchDatabaseConnection.
 */
object OpenvSwitchDatabaseConnectionImpl {
    private final val InterfaceTypeSystem: String  = "system"
    private final val InterfaceTypeInternal: String = "internal"
    private final val InterfaceTypeTap: String = "tap"
    private final val InterfaceTypeGre: String = "gre"
    private final val TableBridge: String = "Bridge"
    private final val TableController: String = "Controller"
    private final val TableInterface: String = "Interface"
    private final val TableOpenvSwitch: String = "Open_vSwitch"
    private final val TablePort: String = "Port"
    private final val TableQos: String = "QoS"
    private final val TableQueue: String = "Queue"
    private final val echo_interval = 1000
    private final val log = LoggerFactory.getLogger(this.getClass)

    /**
     * Generates a new UUID to identify a newly added row.
     *
     * @return A new UUID.
     */
    def generateUUID(): String = UUID.randomUUID.toString

    /**
     * Transforms a newly inserted row's temporary UUID into a "UUID name", to
     * reference the inserted row in the same transaction.
     *
     * @param uuid The UUID to convert.
     * @return The converted UUID.
     */
    def getUUIDNameFromUUID(uuid: String) = "row" + uuid.replace("-", "_")

    /**
     * Converts a row UUID into an Open vSwitch UUID reference to a row
     * inserted in the same transaction.
     *
     * @param uuid The UUID to convert.
     * @return The Open vSwitch DB row UUID reference.
     */
    def getNewRowOvsUUID(uuid: String): List[String] = {
        List("named-uuid", getUUIDNameFromUUID(uuid))
    }

    /**
     * Create a where clause to select a bridge given its identifier.
     *
     * @param bridgeId If a string, the name of the bridge.  If an integer,
     *                 the datapath identifier of the bridge.
     */
    def bridgeWhereClause(bridgeId: Long): List[List[String]] = {
        List(List("datapath_id", "==", "%016x".format(bridgeId)))
    }

    /**
     * Create a where clause to select a bridge given its identifier.
     *
     * @param bridgeId If a string, the name of the bridge.  If an integer,
     *                 the datapath identifier of the bridge.
     */
    def bridgeWhereClause(bridgeName: String): List[List[String]] = {
        List(List("name", "==", bridgeName))
    }

    /**
     * Get a JOSN-encodable 'where' clause to match the row with the uuid.
     *
     * @param uuid The UUID string of the row to match with returned 'where'
     *             clause.
     * @return A JSON-encodable value that represents a 'where' clause matching
     *         row with the given UUID.
     */
    def whereUUIDEquals(uuid: String): List[List[_]] = {
        List(List("_uuid", "==", List("uuid", uuid)))
    }

    /**
     * Converts an Open vSwitch DB map into a Map.
     *
     * @param table   The name of the table containing the row to select.
     * @param where   The JSON-encodable 'where' clause to be matched by the row.
     * @param columns The List of columns to return.
     * @return The List of selected rows.
     */
    def ovsMapToMap(ovsMap: JsonNode): Map[String, String] = {
        require(ovsMap.get(0).toString != "map",
                "map should be the first entry.")
        (for {
            node <- ovsMap.get(1)
            key = node.get(0) if key != null
            value = node.get(1) if value != null
        } yield (key.getTextValue,
                 value.getTextValue)).toMap[String, String]
     }

    /**
     * Converts and Open vSwitch DB map into a Map
     *
     * @param map The Map to convert.
     * @return An Open vSwitch DB map with the key-value pairs of the Map.
     */
    def mapToOvsMap(map: Map[String, _]): List[_] = {
        List("map", (for ((k, v) <- map) yield List(k, v)).toList)
    }
}

/**
 * An implementation of a connection to an Open vSwitch database server.
 */
class OpenvSwitchDatabaseConnectionImpl(val database: String, val addr: String,
                                        val port: Int)
extends OpenvSwitchDatabaseConnection with Runnable {
    import OpenvSwitchDatabaseConnectionImpl._

    private var nextRequestid = 0
    private val pendingJsonRpcRequests =
        new mutable.HashMap[Long, BlockingQueue[JsonNode]]()
    private val objectMapper = new ObjectMapper().withModule(new ScalaModule())
    private val jsonFactory = new JsonFactory(objectMapper)
    private val factory = JsonNodeFactory.instance
    private val socket = new Socket(addr, port)
    private val jsonParser = jsonFactory.createJsonParser(
        new InputStreamReader(socket.getInputStream))
    private val jsonGenerator = jsonFactory.createJsonGenerator(
        new OutputStreamWriter(socket.getOutputStream))
    private val timer = new Timer()
    private var continue = true

    { val me = new Thread(this); me.setDaemon(true); me.start }

    timer.schedule(new TimerTask() {
        override def run = synchronized {
            val transact = Map(
                "method" -> "echo",
                "params" -> objectMapper.createArrayNode, "id" -> "echo")
            try {
                objectMapper.writeValue(jsonGenerator, transact)
                jsonGenerator.flush
            } catch {
                case e: IOException =>
                    { log.warn("echo", e); throw new RuntimeException(e) }
            }
        }
    }, echo_interval)

    def stop = { continue = false }

    /**
     * Apply a operation to the database.
     *
     * @param tx The instance of the transaction.
     * @return The Java representation of the JSON object.
     */
    private def doJsonRpc(tx: Transaction,
                          async: Boolean = false): JsonNode = synchronized {
        val requestId = nextRequestid
        val request = tx.createJsonRpcRequest(requestId)
        val queue = new ArrayBlockingQueue[JsonNode](1)

        nextRequestid += 1
        pendingJsonRpcRequests.synchronized {
            // TODO: Check that no queue is already registered with that
            // requestId.
            pendingJsonRpcRequests.put(requestId, queue)
        }
        log.debug("doJsonRpc request: ", request)
        try {
            // Serialize the JSON-RPC 1.0 request into JSON text in the output
            // channel.
            try {
                objectMapper.writeValue(jsonGenerator, request)
                jsonGenerator.flush
            } catch {
                case e: IOException =>
                    { log.warn("doJsonRpc", e); throw new RuntimeException(e) }
            }
            // Block until the response is received and parsed.
            // TODO: Set a timeout for the response, using poll() instead of
            // take().
            var response: JsonNode = null
            try {
                response = queue.take
            } catch {
                case e: InterruptedException =>
                    { log.warn("doJsonRpc", e); throw new RuntimeException(e) }
            }
            val errorValue = response.get("error")
            if (!errorValue.isNull) {
                log.warn("doJsonRpc: error from server: ", errorValue)
                throw new RuntimeException(
                    "OVSDB request error: " + errorValue.toString)
            }
            return response.get("result")
        } finally {
            pendingJsonRpcRequests.synchronized {
                pendingJsonRpcRequests.remove(requestId)
            }
        }
    }

    override def run() = {
        while (continue) {
            try {
                val json = jsonParser.readValueAsTree
                log.debug("OVSDB response: ", json)

                if (json.get("result") != null) {
                    assume(json.get("id") != null, "Invalid JSON object.")
                    val requestId = json.get("id").getValueAsLong
                    pendingJsonRpcRequests.synchronized {
                        pendingJsonRpcRequests.get(requestId) match {
                            // Pass the JSON object to the caller, and
                            // notify it.
                            case Some(queue) => queue.add(json)
                            case None => throw new RuntimeException(
                                "Invalid requestId %d".format(requestId))
                        }
                    }
                }
                //TODO: handle "notification" type
            } catch {
                case e: InterruptedException =>
                    { log.warn("run", e) }
                case e: IOException =>
                    { log.warn("run", e) }
            }
        }
    }

    /**
     * A transaction to be performed by an Open vSwitch database server.
     */
    private class Transaction(val database: String) {
        private var dryRun: Boolean = false
        private val comments = ListBuffer[String]()
        private val rowSelections = ListBuffer[Map[String, _]]()
        private val rowDeletions = ListBuffer[Map[String, _]]()
        private val rowInsertions = ListBuffer[Map[String, _]]()
        private val rowUpdates = ListBuffer[Map[String, _]]()
        private val rowMutations = ListBuffer[Map[String, _]]()

        /**
         * The transaction to abort all changes, or not.
         *
         * @param dryRun If true, the transaction's changes are unconditionally
         *               aborted at the end of the transaction.
         */
        def setDryRun(dryRun: Boolean) = {
            this.dryRun = dryRun
        }

        /**
         * Add a comment to be added into the logs when the transaction is
         * successfully committed.
         *
         * @param comment The comment to log.
         */
        def addComment(comment: String) = {
            comments += comment
        }

        /**
         * Get a JSON-encodable representation of this transaction's changes
         *
         * @param id An unambigous JSON-RPC request ID assigned to the
         *           newrequest.
         * @return A Scala value that can be encoded into JSON to represent
         *         this transaction in a JSON-RPC call to a DB.
         */
        def createJsonRpcRequest(requestId: Long): Map[String, _] = {
            var params: ListBuffer[Any] = ListBuffer(database)

            if (!rowSelections.isEmpty)  params ++= rowSelections
            if (!rowDeletions.isEmpty)   params ++= rowDeletions
            if (!rowInsertions.isEmpty)  params ++= rowInsertions
            if (!rowUpdates.isEmpty)     params ++= rowUpdates
            if (!rowMutations.isEmpty)   params ++= rowMutations
            if (!this.comments.isEmpty)
                params += Map("op" -> "comment",
                              "comment" -> comments.mkString("\n"))
            if (dryRun)
                params += Map("op" -> "abort")
            Map("method" -> "transact", "params" -> params, "id" -> requestId)
        }

        /**
         * Select columns for the rows that match the 'where' clause.
         *
         * @param table The name of the table containing the rows to select.
         * @param where The JSON-encodable 'where' clauses to be matched by
         *              the row.
         * @param columns The list of columns to return.
         */
        def select(table: String, where: List[List[_]],
                   columns: List[String]) = {
            rowSelections += Map("op" -> "select", "table" -> table,
                                 "where" -> where, "columns" -> columns)
        }

        /**
         * Delete a row in this transaction.
         *
         * @param table The name of the table containing the row to delete.
         * @param rowUuid The UUID string of the row to delete.
         */
        def delete(table: String, rowUUID: Option[String]) = {
            val where: List[List[_]] = rowUUID match {
                case Some(s) => whereUUIDEquals(s)
                case None => List()
            }
            rowDeletions += Map("op" -> "delete", "table" -> table,
                                "where" -> where)
        }

        /**
         * Insert a row in this transaction.
         *
         * @param table The name of the table to contain the inserted row.
         * @param rowUUID The UUID string of the row to insert.
         * @param row A Map of the column / values of the inserted row.
         */
        def insert(table: String, rowUUID: String, row: Map[String, _]) = {
            rowInsertions += Map("op" -> "insert", "table" -> table,
                                 "uuid-name" -> getUUIDNameFromUUID(rowUUID),
                                 "row" -> row)
        }

        /**
         * Update a row in this transaction.
         *
         * @param table   The name of the table containing the row to update.
         * @param rowUUID The UUID string of the row to update.
         * @param row     A Map of the column / values updated.
         */
        def update(table: String, rowUUID: Option[String],
                   row: Map[String, _]) = {
            val where: List[List[_]] = rowUUID match {
                case Some(s) => whereUUIDEquals(s)
                case None => List()
            }
            rowUpdates += Map("op" -> "update", "table" -> table,
                              "where" -> where, "row" -> row)
        }

        /**
         * Increment values in columns for a given row in this transaction.
         *
         * @param table   The name of the table containig the row to update.
         * @param rowUUID The UUID string of the row to update.
         * @param columns The List of column names of the columns to increment.
         */
        def increment(table: String, rowUUID: Option[String],
                      columns: List[String]) = {
            val where: List[List[_]] = rowUUID match {
                case Some(s) => whereUUIDEquals(s)
                case None => List()
            }
            rowMutations += Map("op" -> "mutate", "table" -> table,
                                "where" -> where,
                                "mutations" -> (for (column <- columns)
                                                yield List(column, "+=", 1)))
        }

        /**
         * Increment values in columns for a given row in this transaction.
         *
         * This method is overloaded alias for
         *   increment(table: String, rowUUID: String, columns: List[String])
         *
         * @param table   The name of the table containig the row to update.
         * @param rowUUID The UUID string of the row to update.
         * @param columns The List of column names of the columns to increment.
         * @see           #increment(String, Option[String], List[String]): Unit
         */
        def increment(table: String, rowUUID: Option[String],
                      columns: String): Unit = {
            increment(table, rowUUID, List(columns))
        }

        /**
         * Insert a value into a set column for a given row in this transaction.
         *
         * @param table   The name of the table containing the row to update.
         * @param rowUUID The UUID string of th row update.
         * @param column  The set column to update.
         * @param value   The value to insert into the set.
         */
        def setInsert(table: String, rowUUID: Option[String],
                      column: String , value: Any) = {
            val where: List[List[_]] = rowUUID match {
                case Some(s) => whereUUIDEquals(s)
                case None => List()
            }
            rowMutations += Map("op" -> "mutate", "table" -> table,
                                "where" -> where,
                                "mutations" -> List(List(column, "insert",
                                                         value)))
        }

        /**
         * Delete a value from a set column for a given row in this transaction.
         *
         * @param table   The name of the table containing the row to update
         * @param rowUUID The UUID string of the row to update.
         * @param column  The set column to update.
         * @param value   The value to delete from the set
         */
        def setDelete(table: String, rowUUID: Option[String],
                      column: String, value: Any) = {
            val where: List[List[_]] = rowUUID match {
                case Some(s) => whereUUIDEquals(s)
                case None => List()
            }
            rowMutations += Map("op" -> "mutate", "table" -> table,
                                "where" -> where,
                                "mutations" -> List(
                                    List(column, "delete", value)))
        }
    }

    /**
     * Select data from the database.
     *
     * @param table   The name of the table containing the rows to select.
     * @param where   The JSON-encodable 'where' clause to be matched by the
     *                rows.
     * @param columns The list of columns to return.
     * @return The list of selected rows.
     */
    private def select(table: String, where: List[List[_]],
                       column: List[String]): JsonNode = {
        val tx = new Transaction(database)
        tx.select(table, where, column)
        val json = doJsonRpc(tx)
        assume(json.get(0) != null, "Invalid JSON object.")
        json.get(0).get("rows")
    }

    /**
     * Get rows of columns in table that contains key-val pair in the
     * external_ids column.
     *
     * @param table The name of the table containing the rows to select.
     * @param key key to seek in the external_ids column.
     * @param value value to seek in the external_ids column.
     * @param columns The list of columns to return.
     * @return The list of selected rows.
     */
    def selectByExternalId(table: String, key: String, value: String,
                           columns: List[String]): JsonNode = {
        val tx = new Transaction(database)
        tx.select(table, List(List("external_ids", "includes",
                                   mapToOvsMap(Map(key -> value)))), columns)
        val json = doJsonRpc(tx)
        assume(json.get(0) != null, "Invalid JSON object.")
        json.get(0).get("rows")
    }

    /**
     * Query the UUID of a bridge given its datapath ID or its name.
     *
     * @param bridgeId The datapath identifier of the bridge; significant
     *                 only if bridgeName is null.
     * @return The UUID of the bridge associated with the given bridge id.
     */
    private def getBridgeUUID(bridgeId: Long): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeId),
                                List("_uuid"))
        for {
            bridgeRow <- bridgeRows
            _uuid = bridgeRow.get("_uuid") if _uuid != null
            bridgeUUID = _uuid.get(1) if bridgeUUID != null
        } return bridgeUUID.getTextValue

        return ""
    }

    /**
     * Query the UUID of a bridge given its datapath ID or its name.
     *
     * @param bridgeName The name of the bridge; may be null, in which case the
     *                   bridgeId is used to identify the bridge.
     * @return The UUID of the bridge associated with the given bridge id.
     */
    private def getBridgeUUID(bridgeName: String): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeName),
                                List("_uuid"))
        for {
            bridgeRow <- bridgeRows
            _uuid = bridgeRow.get("_uuid") if _uuid != null
            bridgeUUID = _uuid.get(1) if bridgeUUID != null
        } return bridgeUUID.getTextValue

        return ""
    }

    /**
     * A BridgeBuilder that uses a synchronous OVSDB connection.
     */
    private class BridgeBuilderImpl(val name: String) extends BridgeBuilder {
        require(name != null, "The name of the bridge is required.")
        private var ifRow = Map[String, String]("name" -> name)
        private var portRow = Map[String, Any]("name" -> name)
        private var bridgeRow = Map[String, Any]("name" -> name,
                                                 "datapath_type" -> "")
        private var bridgeExternalIds = Map[String, String]()

        /**
         * Add an external id.
         *
         * @param key The key of the external id entry.
         * @param key The value of the external id entry.
         * @return This SBridgeBuilder instance.
         */
        override def externalId(key: String, value: String) = {
            bridgeExternalIds += (key -> value)
            this
        }

        /**
         * Set the fail mode.
         *
         * @param failMode The failMode instance to set.
         * @return This SBridgeBuilder instance.
         */
        override def failMode(failMode: BridgeFailMode) = {
            bridgeRow += ("fail_mode" -> failMode.getMode)
            this
        }

        /**
         * Build the bridge base on this BridgeBuilderImpl instance.
         */
        override def build() = {
            val tx = new Transaction(database)
            val ifUUID: String = generateUUID
            tx.insert(TableInterface, ifUUID, ifRow)
            val portUUID = generateUUID
            portRow += ("interfaces" -> getNewRowOvsUUID(ifUUID))
            tx.insert(TablePort, portUUID, portRow)
            val bridgeUUID = generateUUID
            bridgeRow += ("ports" -> getNewRowOvsUUID(portUUID))
            bridgeRow += ("external_ids" -> mapToOvsMap(bridgeExternalIds))
            tx.insert(TableBridge, bridgeUUID, bridgeRow)
            tx.setInsert(TableOpenvSwitch, None, "bridges",
                         getNewRowOvsUUID(bridgeUUID))
            val extIds: Iterable[String] =
                for ((k, v) <- bridgeExternalIds) yield "%s=%s".format(k, v)
            val extIdsStr: String = extIds.mkString(", ")
            tx.addComment("added bridge %s with external ids %s.".format(
                bridgeUUID, extIdsStr))
            tx.increment(TableOpenvSwitch, None, "next_cfg")
            doJsonRpc(tx)
        }
    }

    /**
     * A BridgeBuilder that uses a synchronous OVSDB connection.
     */
    private class PortBuilderImpl(
        val ifType: String, val bridgeId: Long = 0, val portName: String,
        val bridgeName: String="") extends PortBuilder {
        private var ifRow = Map[String, String]("type" -> ifType,
                                                "name" -> portName)
        private var portRow = Map[String, String]("name" -> portName)
        private var portExternalIds = Map[String, String]()

        /**
         * Add an external id.
         *
         * @param key The key of the external id entry.
         * @param key The value of the external id entry.
         * @return This SBridgeBuilder instance.
         */
        override def externalId(key: String, value: String) = {
            portExternalIds += (key -> value)
            this
        }

        /**
         * Add a MAC.
         *
         * @param ifMac The MAC address of the interface to add.
         * @return This SBridgeBuilder instance.
         */
        override def ifMac(ifMac: String) = {
            ifRow += ("mac" -> ifMac)
            portRow += ("mac" -> ifMac)
            this
        }

        /**
         * Build the port base on this PortBuilder instance.
         */
        override def build() = {
            val bridgeUUID = if (!bridgeName.isEmpty) {
                getBridgeUUID(bridgeName)
            } else {
                getBridgeUUID(bridgeId)
            }
            addPort(bridgeUUID, ifRow, portRow, None, Some(portExternalIds))
        }
    }

    /**
     * A GrePortBuilder that uses an synchronous OVSDB connection.
     */
    private class GrePortBuilderImpl(val bridgeId: Long = 0, val portName: String,
                                     val remoteIp: String,
                                     val bridgeName: String="")
            extends GrePortBuilder {
        private var ifRow: Map[String, String] =
            Map("type" -> InterfaceTypeGre, "name" -> portName)
        private var portRow: Map[String, String] = Map("name" -> portName)

        private var ifOptions: Map[String, _] = Map("remote_ip" -> remoteIp)
        private var portExternalIds = Map[String, String]()

        override def externalId(key: String, value: String) =
            { portExternalIds += (key -> value); this }
        override def ifMac(ifMac: String) =
            { ifRow += ("mac" -> ifMac); this }
        override def localIp(localIp: String) =
            { ifOptions += ("local_ip" -> localIp); this }
        override def outKey(outKey: Int) =
            { ifOptions += ("out_key" -> outKey.toString); this }
        override def outKeyFlow() =
            { ifOptions += ("out_key" -> "flow"); this }
        override def inKey(inKey: Int) =
            { ifOptions += ("in_key" -> inKey.toString); this }
        override def inKeyFlow() =
            { ifOptions += ("in_key" -> "flow"); this }
        override def key(key: Int) =
            { ifOptions += ("key" -> key.toString); this }
        override def keyFlow() =
            { ifOptions += ("key" -> "flow"); this }
        override def tos(tos: Byte) =
            { ifOptions += ("tos" -> tos.toString); this }
        override def tosInherit() =
            { ifOptions += ("tos" -> "inherit"); this }
        override def ttl(ttl: Byte) =
            { ifOptions += ("ttl" -> ttl.toString); this }
        override def ttlInherit() =
            { ifOptions += ("ttl" -> "inherit"); this }
        override def enableCsum() =
            { ifOptions += ("csum" -> true); this }
        override def disablePmtud() =
            { ifOptions += ("pmtud" -> false); this }
        override def disableHeaderCache() =
            { ifOptions += ("header_cache" -> false); this }
        override def build() = {
            val bridgeUUID = if (!bridgeName.isEmpty) {
                getBridgeUUID(bridgeName)
            } else {
                getBridgeUUID(bridgeId)
            }
            addPort(bridgeUUID, ifRow, portRow, Some(ifOptions),
                    Some(portExternalIds))
        }
     }

    /**
     * A ControllerBuilder that uses an synchronous OVSDB connection.
     */
    private class ControllerBuilderImpl(val bridgeId: Long = 0, val target: String,
                                        val bridgeName: String="")
            extends ControllerBuilder {
        private var ctrlRow: Map[String, _] = Map("target" -> target)
        private var ctrlExternalIds: Map[String, String] = Map()

        override def externalId(key: String, value: String) =
            { ctrlExternalIds += (key -> value); this }
        override def connectionMode(connectionMode: ControllerConnectionMode) =
            { ctrlRow += ("connection_mode" -> connectionMode.getMode); this }
        override def maxBackoff(maxBackoff: Int) =
            { ctrlRow += ("max_backoff" -> maxBackoff.toString); this }
        override def inactivityProbe(inactivityProbe: Int) = {
            ctrlRow += ("inactivity_probe" -> inactivityProbe.toString); this
        }
        override def controllerRateLimit(controllerRateLimit: Int) = {
            ctrlRow += ("controller_rate_limit" -> controllerRateLimit); this
        }
        override def controllerBurstLimit(controllerBurstLimit: Int) = {
            ctrlRow += (
                "controller_burst_limit" -> controllerBurstLimit.toString)
            this
        }
        override def discoverAcceptRegex(discoverAcceptRegex: String) = {
            ctrlRow += ("discover_accept_regex" -> discoverAcceptRegex); this
        }
        override def discoverUpdateResolvConf(
            discoverUpdateResolvConf: Boolean) = {
            ctrlRow += ("discover_update_resolv_conf" ->
                        (if (discoverUpdateResolvConf) true else false))
            this
        }
        override def localIp(localIp: String) = {
            ctrlRow += ("local_ip" -> localIp); this
        }
        override def localNetmask(localNetmask: String) = {
            ctrlRow += ("local_netmask" -> localNetmask); this
        }
        override def localGateway(localGateway: String) = {
            ctrlRow += ("local_gateway" -> localGateway); this
        }
        override def build() = {
            val bridgeUUID: String = if (!bridgeName.isEmpty) {
                getBridgeUUID(bridgeName)
            } else {
                getBridgeUUID(bridgeId)
            }
            val tx = new Transaction(database)
            val ctrlUUID: String = generateUUID
            ctrlRow += ("external_ids" -> mapToOvsMap(ctrlExternalIds))
            tx.insert(TableController, ctrlUUID, ctrlRow)
            tx.setInsert(TableBridge, Some(bridgeUUID), "controller",
                         getNewRowOvsUUID(ctrlUUID))
            tx.increment(TableOpenvSwitch, None, "next_cfg")
            doJsonRpc(tx)
        }
    }

    /**
     * Add a new bridge with the given name.
     *
     * @param name The name of the bridge to add.
     * @return A builder to set optional parameters of the bridge and add it.
     */
    override def addBridge(name: String): BridgeBuilder = {
        val bb = new BridgeBuilderImpl(name)
        return bb
    }

    /**
    * Add a port.
    *
    * @param bridgeUUID      The UUID of the bridge to add the port to.
    * @param ifRow           The interface's attributes.
    * @param ifOptions       The interface's options; may be null.
    * @param portRow         The port's attributes.
    * @param portExternalIds Arbitrary pairs of key-value strings associated
    *                        with the port.
    */
    private def addPort(bridgeUUID: String, ifRow: Map[String, _],
                        portRow: Map[String, _],
                        ifOptions: Option[Map[String, _]],
                        portExternalIds: Option[Map[String, _]]) = {
        val tx = new Transaction(database)
        val ifUUID: String = generateUUID
        var portRowUpdated: Map[String, Any] = portRow
        var ifRowUpdated: Map[String, Any] = ifRow
        if (!ifOptions.isEmpty)
            ifRowUpdated += ("options" -> mapToOvsMap(ifOptions.get))
        tx.insert(TableInterface, ifUUID, ifRowUpdated)
        val portUUID: String = generateUUID
        if (!portExternalIds.isEmpty)
            portRowUpdated =
                portRow + ("external_ids" -> mapToOvsMap(portExternalIds.get))
        portRowUpdated += ("interfaces" -> getNewRowOvsUUID(ifUUID))
        tx.insert(TablePort, portUUID, portRowUpdated)
        tx.setInsert(TableBridge, Some(bridgeUUID),
                     "ports", getNewRowOvsUUID(portUUID))
        if (!portExternalIds.isEmpty) {
            val extIds = for ((k, v) <- portExternalIds.get)
                         yield "%s=%s".format(k, v)
            val extIdsStr: String = extIds.mkString(", ")
            tx.addComment(
                "added port %s to bridge %s with external ids %s".format(
                    portRow("name"), bridgeUUID, extIdsStr))
        } else {
            tx.addComment("added port %s to bridge %s".format(
                portRow("name"), bridgeUUID))
        }
        tx.increment(TableOpenvSwitch, None, "next_cfg")
        doJsonRpc(tx)
    }

    /**
     * Create a port and a system interface, and add the port to a bridge.
     *
     * A system interface is for instance a physical Ethernet interface.
     *
     * @param bridgeId The datapath identifier of the bridge to add the port to.
     * @param portName The name of the port and of the interface to create.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addSystemPort(bridgeId: Long,
                               portName: String): PortBuilder =
        new PortBuilderImpl(InterfaceTypeSystem, bridgeId, portName)

    /**
     * Create a port and a system interface, and add the port to a bridge.
     *
     * A system interface is for instance a physical Ethernet interface.
     *
     * @param bridgeName The name of the bridge to add the port to.
     * @param portName   The name of the port and of the interface to create.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addSystemPort(bridgeName: String,
                               portName: String): PortBuilder =
        new PortBuilderImpl(InterfaceTypeSystem, portName=portName,
                            bridgeName=bridgeName)

    /**
     * Create a port and an internal interface, and add the port to a bridge
     *
     * An internal interface is a virtual physical Ethernet interface usable
     * to exchange packets only with the bridge.
     *
     * @param bridgeId The datapath identifier of the bridge to add the port to.
     * @param portName The name of the port and of the interface to create.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addInternalPort(bridgeId: Long,
                        portName: String): PortBuilder =
        new PortBuilderImpl(InterfaceTypeInternal, bridgeId, portName)

    /**
     * Create a port and an internal interface, and add the port to a bridge
     *
     * An internal interface is a virtual physical Ethernet interface usable
     * to exchange packets only with the bridge.
     *
     * @param bridgeName The name of the bridge to add the port to.
     * @param portName   The name of the port and of the interface to create.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addInternalPort(bridgeName: String,
                        portName: String): PortBuilder =
        new PortBuilderImpl(InterfaceTypeInternal, portName=portName,
                        bridgeName=bridgeName)

    /**
     * Create a port and a TAP interface, and add the port to a bridge.
     *
     * @param bridgeId The datapath identifier of the bridge to add the port to.
     * @param portName The name of the port and of the TAP interface to create.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addTapPort(bridgeId: Long, portName: String): PortBuilder =
        new PortBuilderImpl(InterfaceTypeTap, bridgeId, portName)

    /**
     * Create a port and a TAP interface, and add the port to a bridge.
     *
     * @param bridgeName The name of the bridge to add the port to.
     * @param portName   The name of the port and of the TAP interface to create.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addTapPort(bridgeName: String, portName: String): PortBuilder =
        new PortBuilderImpl(InterfaceTypeTap, portName=portName,
                            bridgeName=bridgeName)

    /**
     * Create a port and a GRE interface, and add the port to a bridge.
     *
     * @param bridgeId The datapath identifier of the bridge to add the port to.
     * @param portName The name of the port and of the TAP interface to create.
     * @param remoteIp The tunnel remote endpoint's IP address.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addGrePort(bridgeId: Long,  portName: String,
                   remoteIp: String): GrePortBuilder =
        new GrePortBuilderImpl(bridgeId, portName, remoteIp)

    /**
     * Create a port and a GRE interface, and add the port to a bridge.
     *
     * @param bridgeName The name of the bridge to add the port to.
     * @param portName   The name of the port and of the TAP interface to create.
     * @param remoteIp   The tunnel remote endpoint's IP address.
     * @return A builder to set optional parameters of the port and add it.
     */
    override def addGrePort(bridgeName: String,  portName: String,
                   remoteIp: String): GrePortBuilder =
        new GrePortBuilderImpl(bridgeName=bridgeName, portName=portName,
                               remoteIp=remoteIp)

    /**
     * Delete a port with the given port name.
     *
     * @param portName The name of the port to delete.
     */
    override def delPort(portName: String) = {
        val tx: Transaction = new Transaction(database)
        val portRows = select(TablePort, List(List("name", "==", portName)),
                              List("_uuid", "interfaces"))
        for {
            portRow <- portRows
            _uuid = portRow.get("_uuid") if _uuid != null
            uuidVal = _uuid.get(1) if uuidVal != null
        } {
            val portUUID: String = uuidVal.getTextValue
            tx.delete(TablePort, Some(portUUID))

            val ifs: JsonNode = portRow.get("interfaces")
            assume(ifs != null, "Invalid JSON object.")
            // ifs could be a single array, ["uuid", "1234"], or
            // a set of array, ["set" [["uuid", "1234"], ["uuid", "5678"]]].
            val ifUUIDs: List[JsonNode] =
                if (ifs.get(0) == "uuid") {
                    List(ifs)
                } else if (ifs.get(0) == "set") {
                    assume(ifs.get(1) != null, "Invalid JSON object.")
                    ifs.get(1).getElements.toList
                } else {
                    List()
                }
            for {
                uuidArray <- ifUUIDs
                uuidArrayVal = uuidArray.get(1) if uuidArrayVal != null
            } {
                val ifUUID = uuidArrayVal.getTextValue
                tx.delete("Interface", Some(ifUUID))
            }
            tx.setDelete(TableBridge, None, "ports", List("uuid", portUUID))
        }
        tx.addComment("deleted port %s".format(portName))
        tx.increment(TableOpenvSwitch, None, List("next_cfg"))
        doJsonRpc(tx)
    }

    /**
     * Add an OpenFlow controller for a bridge.
     *
     * An OpenFlow controller target may be in any of the following forms for a
     * primary controller (i.e. a normal OpenFlow controller):
     *     'ssl:$(ip)[:$(port)s]': The specified SSL port (default: 6633) on the
     *         host at the given ip, which must be expressed as an IP address
     *         (not a DNS name).
     *     'tcp:$(ip)[:$(port)s]': The specified TCP port (default: 6633) on the
     *         host at the given ip, which must be expressed as an IP address
     *         (not a DNS name).
     *     'discover': The switch discovers the controller by broadcasting DHCP
     *         requests with vendor class identifier 'OpenFlow'.
     *
     * An OpenFlow controller target may be in any of the following forms for a
     * service controller (i.e. a controller that only connects temporarily and
     * doesn't affect the datapath's failMode):
     *     'pssl:$(ip)[:$(port)s]': The specified SSL port (default: 6633) and
     *         ip Open vSwitch listens on for connections from controller; the
     *         given ip must be expressed as an IP address (not a DNS name).
     *     'ptcp:$(ip)[:$(port)s]': The specified TCP port (default: 6633) and
     *         ip Open vSwitch listens on for connections from controllers; the
     *         given ip must be expressed as an IP address (not a DNS name).
     *
     * @param bridgeId The datapath identifier of the bridge to add the
     *                 controller to.
     * @param target   The target to connect to the OpenFlow controller.
     * @return A builder to set optional parameters of the controller
     *         connection and add it.
     */
    override def addBridgeOpenflowController(
        bridgeId: Long, target: String): ControllerBuilder =
            new ControllerBuilderImpl(bridgeId, target)

    /**
     * Add an OpenFlow controller for a bridge.
     *
     * An OpenFlow controller target may be in any of the following forms for a
     * primary controller (i.e. a normal OpenFlow controller):
     *     'ssl:$(ip)[:$(port)s]': The specified SSL port (default: 6633) on the
     *         host at the given ip, which must be expressed as an IP address
     *         (not a DNS name).
     *     'tcp:$(ip)[:$(port)s]': The specified TCP port (default: 6633) on the
     *         host at the given ip, which must be expressed as an IP address
     *         (not a DNS name).
     *     'discover': The switch discovers the controller by broadcasting DHCP
     *         requests with vendor class identifier 'OpenFlow'.
     *
     * An OpenFlow controller target may be in any of the following forms for a
     * service controller (i.e. a controller that only connects temporarily and
     * doesn't affect the datapath's failMode):
     *     'pssl:$(ip)[:$(port)s]': The specified SSL port (default: 6633) and
     *         ip Open vSwitch listens on for connections from controllers; the
     *         given ip must be expressed as an IP address (not a DNS name).
     *     'ptcp:$(ip)[:$(port)s]': The specified TCP port (default: 6633) and
     *         ip Open vSwitch listens on for connections from controllers; the
     *         given ip must be expressed as an IP address (not a DNS name).
     *
     * @param bridgeName The name of the bridge to add the controller to.
     * @param target     The target to connect to the OpenFlow controller.
     * @return A builder to set optional parameters of the controller
     *         connection and add it.
     */
    override def addBridgeOpenflowController(
        bridgeName: String, target: String): ControllerBuilder =
            new ControllerBuilderImpl(bridgeName=bridgeName, target=target)

    private def delBridgeOpenflowControllers(
        bridgeRows: JsonNode, bridge: String) = {
        val tx = new Transaction(database)

        for {
            bridgeRow <- bridgeRows
            _uuid = bridgeRow.get("_uuid") if _uuid != null
            uuidVal = _uuid.get(1) if uuidVal != null
            controllers = bridgeRow.get("controller") if controllers != null
            controllersKey = controllers.get(0) if controllersKey != null
        } {
            val bridgeUUID: String = uuidVal.getTextValue
            val controllerUUIDs: List[JsonNode] =
                if (controllersKey.getTextValue == "uuid") {
                    List(controllers)
                } else if (controllersKey.getTextValue == "set") {
                    assume(controllers.get(1) != null, "Invalid JSON object.")
                    controllers.get(1).getElements.toList
                } else {
                    List()
                }

            for {
                controllerUUID <- controllerUUIDs
                controllerUUIDVal = controllerUUID.get(1)
                if controllerUUIDVal != null
            } {
                val uuid = controllerUUIDVal.getTextValue
                tx.delete(TableController, Some(uuid))
                tx.setDelete(TableBridge, Some(bridgeUUID),
                             "controller", List("uuid", uuid))
            }
        }
        tx.addComment("deleted controllers for bridge with id " + bridge)
        tx.increment(TableOpenvSwitch, None, List("next_cfg"))
        doJsonRpc(tx)
    }

    /**
     * Delete all the OpenFlow controller targets for a bridge.
     *
     * @param bridgeId The datapath identifier of the bridge.
     */
    override def delBridgeOpenflowControllers(bridgeId: Long) = {
        val bridgeRows =
            select(TableBridge, whereUUIDEquals(getBridgeUUID(bridgeId)),
                   List("_uuid", "controller"))
        delBridgeOpenflowControllers(bridgeRows, bridgeId.toString)
    }

    /**
     * Delete all the OpenFlow controller targets for a bridge.
     *
     * @param bridgeName The name of the bridge.
     */
    override def delBridgeOpenflowControllers(bridgeName: String) = {
        val bridgeRows =
            select(TableBridge, whereUUIDEquals(getBridgeUUID(bridgeName)),
                   List("_uuid", "controller"))
        delBridgeOpenflowControllers(bridgeRows, bridgeName)
    }

    /**
     * Determine whether a bridge with a given ID exists.
     *
     * @param bridgeId The datapath identifier of the bridge.
     * @return Whether a bridge with the given ID exists.
     */
    override def hasBridge(bridgeId: Long) = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeId),
                                List("_uuid"))
        !bridgeRows.getElements.isEmpty
    }

    /**
     * Determine whether a bridge with a given name exists.
     *
     * @param bridgeName The name of the bridge.
     * @return Whether a bridge with the given name exists.
     */
    override def hasBridge(bridgeName: String) = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeName),
                                List("_uuid"))
        !bridgeRows.getElements.isEmpty
    }

    /**
     * Determine whether a port with a given name exists.
     *
     * @param portName The name of the port.
     * @return Whether a bridge with the given name exists.
     */
    def hasPort(portName: String) = {
        val portRows = select(TablePort, List(List("name", "==", portName)),
                                List("_uuid"))
        !portRows.getElements.isEmpty
    }

    /**
     * Determine whether a controller with a given target exists.
     *
     * @param target The target of the controller.
     * @return Whether a bridge with the given name exists.
     */
    def hasController(target: String) = {
        val controllerRows = select(
            TableController, List(List("target", "==", target)), List("_uuid"))
        !controllerRows.getElements.isEmpty
    }

    private def delBridge(bridgeRows: Iterator[JsonNode], bridge: String) = {
        val tx = new Transaction(database)

        for {
            bridgeRow <- bridgeRows
            _uuid = bridgeRow.get("_uuid") if _uuid != null
            bridgeUUID = _uuid.get(1) if bridgeUUID != null
        } {
            tx.delete(TableBridge, Some(bridgeUUID.getTextValue))
            // The 'Open_vSwitch' table should contain only one row, so pass
            // None as the UUID to update all the rows in there.  Delete the
            // bridge UUID from the set of activated bridges:
            tx.setDelete(TableOpenvSwitch, None, "bridges",
                         List("uuid", bridgeUUID.getTextValue))

            val ports = bridgeRow.get("ports")
            assume(ports != null, "Invalid JSON object.")
            assume(ports.get(0) != null, "Invalid JSON object.")
            val portUUIDs: List[JsonNode] =
                if (ports.get(0).getTextValue == "uuid") {
                    List(ports)
                } else if (ports.get(0).getTextValue == "set") {
                    assume(ports.get(1) != null, "Invalid JSON object.")
                    ports.get(1).getElements.toList
                } else {
                    List()
                }
            for {
                portUUID <- portUUIDs
                portUUIDVal = portUUID.get(1) if portUUIDVal != null
            } {
                tx.delete(TablePort, Some(portUUIDVal.getTextValue))
                val portRow = select(
                    TablePort,
                    whereUUIDEquals(portUUIDVal.getTextValue),
                    List("_uuid", "interfaces")).get(0)
                val ifs = portRow.get("interfaces")
                assume(ifs != null, "Invalid JSON object.")
                assume(ifs.get(0) != null, "Invalid JSON object.")
                val ifUUIDs: List[JsonNode] =
                    if (ifs.get(0).getTextValue == "uuid") {
                        List(ifs)
                    } else {
                        ifs.getElements.toList
                    }
                for {
                    ifUUID <- ifUUIDs
                    ifUUIDVal = ifUUID.get(1) if ifUUIDVal != null
                } tx.delete(TableInterface, Some(ifUUIDVal.getTextValue))
            }
        }

        // Trigger ovswitchd to reload the configuration.
        tx.increment(TableOpenvSwitch, None, "next_cfg")

        tx.addComment("deleted bridge with %s".format(bridge))

        doJsonRpc(tx)
    }

    /**
     * Delete the bridge with the given ID.
     *
     * @param bridgeId The datapath identifier of the bridge to delete.
     */
    override def delBridge(bridgeId: Long) = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeId),
                                List("_uuid", "ports"))
        delBridge(bridgeRows.getElements, bridgeId.toString)
    }

    /**
     * Delete the bridge with the given name.
     *
     * @param bridgeName The name of the bridge
     */
    override def delBridge(bridgeName: String) = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeName),
                                List("_uuid", "ports"))
        delBridge(bridgeRows.getElements, bridgeName)
    }

    /**
     * Get the datapath identifier of the bridge.
     *
     * @param bridgeName The name of the bridge.
     * @return The datapath identifier of the bridge.
     */
    def getDatapathId(bridgeName: String): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeName),
                                List("datapath_id", "ports"))
        for {
            bridgeRow <- bridgeRows
            datapathId = bridgeRow.get("datapath_id") if datapathId != null
        } return datapathId.getValueAsText

        return ""
    }

    /**
     * Get an external ID associated with a bridge given its ID.
     *
     * @param bridgeId The datapath identifier of the bridge.
     * @param externalIdKey The key of the external ID to look up.
     * @return The value of the external id, or null if no bridge with that
     *         datapath ID exists or if the bridge has no external ID with that
     *         key.
     */
    override def getDatapathExternalId(bridgeId: Long,
                                       externalIdKey: String): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeId),
                                List("external_ids"))
        for {
            bridgeRow <- bridgeRows
            ovsMap = bridgeRow.get("external_ids") if ovsMap != null
        } {
            val extIds = ovsMapToMap(ovsMap)
            for (externalIdVal <- extIds.get(externalIdKey)) {
                return externalIdVal
            }
        }
        return ""
    }

    /**
     * Get an external ID associated with a bridge given its name.
     *
     * @param bridgeName    The name of the bridge.
     * @param externalIdKey The key of the external ID to look up.
     * @return The value of the external id, or null if no bridge with that name
     *         exists or if the bridge has no external ID with that key.
     */
    override def getDatapathExternalId(bridgeName: String,
                                       externalIdKey: String): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeName),
                                List("external_ids"))
        for {
            bridgeRow <- bridgeRows
            ovsMap = bridgeRow.get("external_ids") if ovsMap != null
        } {
            val extIds = ovsMapToMap(ovsMap)
            for (externalIdVal <- extIds.get(externalIdKey)) {
                return externalIdVal
            }
        }
        return ""
    }

    private def getPortExternalId(
        bridgeRows: Iterator[JsonNode], portNum: Int,
        externalIdKey: String): String = {
        for {
            bridgeRow <- bridgeRows
            ports = bridgeRow.get("ports") if ports != null
            portsKey = ports.get(0) if portsKey != null
        } {
            val portUUIDs: List[JsonNode] =
                if (portsKey.getTextValue == "uuid") {
                    List(ports)
                } else if (portsKey.getTextValue == "set") {
                    assume(ports.get(1) != null, "Invalid JSON object.")
                    ports.get(1).getElements.toList
                } else {
                    List()
                }
            for {
                portUUID <- portUUIDs
                portUUIDVal = portUUID.get(1) if portUUIDVal !=  null
            } {
                val portRow = select(
                    TablePort, whereUUIDEquals(portUUID.get(1).getTextValue),
                    List("_uuid", "interfaces", "external_ids")).get(0)
                val ifs = portRow.get("interfaces")
                assume(ifs != null, "Invalid JSON object.")
                val ifUUIDs: List[JsonNode] =
                    if (ifs.get(0).getTextValue == "uuid") {
                        List(ifs)
                    } else {
                        assume(ifs.get(1) != null, "Invalid JSON object.")
                        ifs.get(1).getElements.toList
                    }
                for {
                    ifUUID <- ifUUIDs
                    ifUUIDVal = ifUUID.get(1) if ifUUIDVal != null
                } {
                    val ifRow =
                        select(TableInterface,
                               whereUUIDEquals(ifUUIDVal.getTextValue),
                               List("_uuid", "ofport")).get(0)
                    assume(ifRow.get("ofport") != null, "Invalid JSON object.")
                    if (ifRow.get("ofport").getValueAsInt == portNum) {
                        assume(portRow.get("external_ids") != null,
                               "Invalid JSON object.")
                        val extIds =
                            ovsMapToMap(portRow.get("external_ids"))
                        for (externalIdVal <- extIds.get(externalIdKey)) {
                            return externalIdVal
                        }
                    }
                }
            }
        }
        return ""
    }

    /**
     * Get an external ID associated with a port given its name.
     *
     * @param portName The name of the port.
     * @param externalIdKey The key of the external ID to look up.
     * @return The value of the external ID, or null if no port with that name
     *         exists or if the port has no external id with that key.
     */
    override def getPortExternalId(portName: String,
                                   externalIdKey: String): String = {
        val portRows = select(TablePort, List(List("name", "==", portName)),
                              List("_uuid", "external_ids"))
        for {
            portRow <- portRows
            ovsMap = portRow.get("external_ids") if ovsMap != null
        } {
            val extIds = ovsMapToMap(ovsMap)
            for (externalIdVal <- extIds.get(externalIdKey)) {
                return externalIdVal
            }
        }
        return ""
    }

    /**
     * Get an external ID associated with a given OpenFlow port number.
     *
     * @param bridgeId      The datapath identifier of the bridge that contains
     *                      the port.
     * @param portNum       The OpenFlow number of the port.
     * @param externalIdKey The key of the external ID to look up.
     * @return The value of the external ID, or null if no bridge with that
     *         datapath ID exists, or if no port with that number exists in that
     *         bridge, or if the port has no external ID with that key.
     */
    override def getPortExternalId(bridgeId: Long, portNum: Int,
                                    externalIdKey: String): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeId),
                                List("_uuid", "ports"))
        getPortExternalId(bridgeRows.getElements, portNum, externalIdKey)
    }

    /**
     * Get an external ID associated with a given OpenFlow port number.
     *
     * @param bridgeName    The name of the bridge that contains the port.
     * @param portNum       The OpenFlow number of the port.
     * @param externalIdKey The key of the external ID to look up.
     * @return The value of the external ID, or null if no bridge with that
     *         name exists, or if no port with that number exists in that
     *         bridge, or if the port has no external ID with that key.
     */
    override def getPortExternalId(bridgeName: String, portNum: Int,
                                   externalIdKey: String): String = {
        val bridgeRows = select(TableBridge, bridgeWhereClause(bridgeName),
                                List("_uuid", "ports"))
        getPortExternalId(bridgeRows.getElements, portNum, externalIdKey)
    }

    override def addQos(_type: String): QosBuilder = {
        throw new RuntimeException("not implemented") // TODO
    }

    override def updateQos(qosUuid: String, _type: String): QosBuilder = {
        throw new RuntimeException("not implemented") // TODO
    }

    override def clearQosQueues(qosUuid: String) = {
        throw new RuntimeException("not implemented") // TODO
    }

    override def delQos(qosUuid: String) = {
        throw new RuntimeException("not implemented") // TODO
    }

    override def setPortQos(portName: String, qosUuid: String) = {
        throw new RuntimeException("not implemented") // TODO
    }

    override def unsetPortQos(portName: String) = {
        throw new RuntimeException("not implemented") // TODO
    }

    override def addQueue(): QueueBuilder = {
        throw new RuntimeException("not implemented") // TODO
    }

    override def updateQueue(queueUuid: String): QueueBuilder = {
        throw new RuntimeException("not implemented") // TODO
    }

    override def delQueue(queueUuid: String) = {
        throw new RuntimeException("not implemented") // TODO
    }

    /**
     * Get the set of names of bridges that are associated a given external
     * ID key-value pair.
     *
     * @param key   The external ID key to lookup.
     * @param value The external ID to lookup.
     * @return The set of names of bridges that are associated the external ID.
     */
    override def getBridgeNamesByExternalId(
        key: String, value: String): java.util.Set[String] = {
        val rows = selectByExternalId(TableBridge, key, value, List("name"))
        mutable.Set(
            (for {
                row <- rows
                name = row.get("name") if name != null
            } yield name.getTextValue).toList: _*)
    }

    /**
     * Get the set of names of ports that are associated a given external
     * ID key-value pair.
     *
     * @param key   The external ID key to lookup.
     * @param value The external ID to lookup.
     * @return The set of names of ports that are associated the external ID.
     */
    override def getPortNamesByExternalId(
        key: String, value: String): java.util.Set[String] = {
        val rows = selectByExternalId(TablePort, key, value, List("name"))
        mutable.Set(
                (for {
                    row <- rows
                    name = row.get("name") if name != null
                } yield name.getTextValue).toList: _*)
    }

    /**
     * Close the connection.
     */
    override def close() = {
        timer.cancel
        this.stop
        try {
            socket.close
        } catch {
            case e: IOException => { log.warn("close", e) }
        }
    }
}
