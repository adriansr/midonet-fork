/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.host.commands.HostCommandGenerator;
import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.monitoring.store.Store;
import com.midokura.midolman.state.DirectoryCallback;
import com.midokura.midolman.state.PathBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortConfigCache;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConfigSerializer;
import com.midokura.midolman.state.zkManagers.*;
import com.midokura.midonet.cluster.data.*;
import com.midokura.midonet.cluster.data.dhcp.Subnet;
import com.midokura.midonet.cluster.data.host.Command;
import com.midokura.midonet.cluster.data.host.Host;
import com.midokura.midonet.cluster.data.host.Interface;
import com.midokura.midonet.cluster.data.host.VirtualPortMapping;
import com.midokura.midonet.cluster.data.ports.LogicalBridgePort;
import com.midokura.midonet.cluster.data.ports.LogicalRouterPort;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.functors.Callback2;
import com.midokura.util.functors.CollectionFunctors;
import com.midokura.util.functors.Functor;

//TODO(ross) most methods don't use the reactor when accessing zk, this needs to be
// fixed!
public class LocalDataClientImpl implements DataClient {

    @Inject
    private TenantZkManager tenantZkManager;

    @Inject
    private BridgeDhcpZkManager dhcpZkManager;

    @Inject
    private BgpZkManager bgpZkManager;

    @Inject
    private AdRouteZkManager adRouteZkManager;

    @Inject
    private BridgeZkManager bridgeZkManager;

    @Inject
    private ChainZkManager chainZkManager;

    @Inject
    private RouteZkManager routeZkManager;

    @Inject
    private RouterZkManager routerZkManager;

    @Inject
    private RuleZkManager ruleZkManager;

    @Inject
    private PortZkManager portZkManager;

    @Inject
    private PortConfigCache portCache;

    @Inject
    private RouteZkManager routeMgr;

    @Inject
    private PortGroupZkManager portGroupZkManager;

    @Inject
    private HostZkManager hostZkManager;

    @Inject
    private TunnelZoneZkManager zonesZkManager;

    @Inject
    private VpnZkManager vpnZkManager;

    @Inject
    private PortSetZkManager portSetZkManager;

    @Inject
    private PathBuilder pathBuilder;

    @Inject
    private ZkConfigSerializer serializer;

    @Inject
    private ClusterRouterManager routerManager;

    @Inject
    private ClusterBridgeManager bridgeManager;

    @Inject
    @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    private Reactor reactor;

    Set<Callback2<UUID, Boolean>> subscriptionPortsActive =
        new HashSet<Callback2<UUID, Boolean>>();

    @Inject
    private Store monitoringStore;

    private final static Logger log =
            LoggerFactory.getLogger(LocalDataClientImpl.class);

    @Override
    public AdRoute adRoutesGet(UUID id) throws StateAccessException {
        log.debug("Entered: id={}", id);

        AdRoute adRoute = null;
        if (adRouteZkManager.exists(id)) {
            adRoute = Converter.fromAdRouteConfig(adRouteZkManager.get(id));
            adRoute.setId(id);
        }

        log.debug("Exiting: adRoute={}", adRoute);
        return adRoute;
    }

    @Override
    public void adRoutesDelete(UUID id) throws StateAccessException {
        adRouteZkManager.delete(id);
    }

    @Override
    public UUID adRoutesCreate(@Nonnull AdRoute adRoute)
            throws StateAccessException {
        return adRouteZkManager.create(Converter.toAdRouteConfig(adRoute));
    }

    @Override
    public List<AdRoute> adRoutesFindByBgp(UUID bgpId)
            throws StateAccessException {
        List<UUID> adRouteIds = adRouteZkManager.list(bgpId);
        List<AdRoute> adRoutes = new ArrayList<AdRoute>();
        for (UUID adRouteId : adRouteIds) {
            adRoutes.add(adRoutesGet(adRouteId));
        }
        return adRoutes;
    }

    @Override
    public BGP bgpGet(UUID id) throws StateAccessException {
        return bgpZkManager.getBGP(id);
    }

    @Override
    public void bgpDelete(UUID id) throws StateAccessException {
        bgpZkManager.delete(id);
    }

    @Override
    public UUID bgpCreate(@Nonnull BGP bgp) throws StateAccessException {
        return bgpZkManager.create(bgp);
    }

    @Override
    public List<BGP> bgpFindByPort(UUID portId) throws StateAccessException {
        List<UUID> bgpIds = bgpZkManager.list(portId);
        List<BGP> bgps = new ArrayList<BGP>();
        for (UUID bgpId : bgpIds) {
            bgps.add(bgpGet(bgpId));
        }
        return bgps;
    }

    @Override
    public Bridge bridgesGetByName(String tenantId, String name)
            throws StateAccessException {
        log.debug("Entered: tenantId={}, name={}", tenantId, name);

        Bridge bridge = null;
        String path = pathBuilder.getTenantBridgeNamePath(tenantId, name)
                .toString();

        if (bridgeZkManager.exists(path)) {
            byte[] data = bridgeZkManager.get(path);
            BridgeName.Data bridgeNameData =
                    serializer.deserialize(data, BridgeName.Data.class);
            bridge = bridgesGet(bridgeNameData.id);
        }

        log.debug("Exiting: bridge={}", bridge);
        return bridge;
    }

    @Override
    public List<Bridge> bridgesFindByTenant(String tenantId)
            throws StateAccessException  {
        log.debug("bridgesFindByTenant entered: tenantId={}", tenantId);

        List<Bridge> bridges = new ArrayList<Bridge>();

        String path = pathBuilder.getTenantBridgeNamesPath(tenantId);
        if (bridgeZkManager.exists(path)) {
            Set<String> bridgeNames = bridgeZkManager.getChildren(path);
            for (String name : bridgeNames) {
                Bridge bridge = bridgesGetByName(tenantId, name);
                if (bridge != null) {
                    bridges.add(bridge);
                }
            }
        }

        log.debug("bridgesFindByTenant exiting: {} bridges found",
                bridges.size());
        return bridges;
    }

    @Override
    public UUID bridgesCreate(@Nonnull Bridge bridge)
            throws StateAccessException {
        log.debug("bridgesCreate entered: bridge={}", bridge);

        if (bridge.getId() == null) {
            bridge.setId(UUID.randomUUID());
        }

        BridgeZkManager.BridgeConfig bridgeConfig = Converter.toBridgeConfig(
                bridge);

        List<Op> ops =
                bridgeZkManager.prepareBridgeCreate(bridge.getId(),
                        bridgeConfig);

        // Create the top level directories for
        String tenantId = bridge.getProperty(Bridge.Property.tenant_id);
        ops.addAll(tenantZkManager.prepareCreate(tenantId));

        // Create the bridge names directory if it does not exist
        String bridgeNamesPath = pathBuilder.getTenantBridgeNamesPath(tenantId);
        if (!bridgeZkManager.exists(bridgeNamesPath)) {
            ops.add(bridgeZkManager.getPersistentCreateOp(
                    bridgeNamesPath, null));
        }

        // Index the name
        String bridgeNamePath = pathBuilder.getTenantBridgeNamePath(tenantId,
                bridgeConfig.name);
        byte[] data = serializer.serialize((new BridgeName(bridge)).getData());
        ops.add(bridgeZkManager.getPersistentCreateOp(bridgeNamePath, data));

        bridgeZkManager.multi(ops);

        log.debug("BridgeZkDaoImpl.create exiting: bridge={}", bridge);
        return bridge.getId();
    }

    @Override
    public void bridgesUpdate(@Nonnull Bridge bridge)
            throws StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // Get the original data
        Bridge oldBridge = bridgesGet(bridge.getId());

        BridgeZkManager.BridgeConfig bridgeConfig = Converter.toBridgeConfig(
            bridge);

        // Update the config
        Op op = bridgeZkManager.prepareUpdate(bridge.getId(), bridgeConfig);
        if (op != null) {
            ops.add(op);
        }

        // Update index if the name changed
        String oldName = oldBridge.getData().name;
        String newName = bridgeConfig.name;
        if (oldName == null ? newName != null : !oldName.equals(newName)) {

            String tenantId = oldBridge.getProperty(Bridge.Property.tenant_id);

            String path = pathBuilder.getTenantBridgeNamePath(tenantId,
                    oldBridge.getData().name);
            ops.add(bridgeZkManager.getDeleteOp(path));

            path = pathBuilder.getTenantBridgeNamePath(tenantId,
                    bridgeConfig.name);
            byte[] data = serializer.serialize(new BridgeName(bridge).getData());
            ops.add(bridgeZkManager.getPersistentCreateOp(path, data));
        }

        if (ops.size() > 0) {
            bridgeZkManager.multi(ops);
        }
    }

    @Override
    public Bridge bridgesGet(UUID id) throws StateAccessException {
        log.debug("Entered: id={}", id);

        Bridge bridge = null;
        if (bridgeZkManager.exists(id)) {
            bridge = Converter.fromBridgeConfig(bridgeZkManager.get(id));
            bridge.setId(id);
        }

        log.debug("Exiting: bridge={}", bridge);
        return bridge;
    }

    @Override
    public void bridgesDelete(UUID id) throws StateAccessException {

        Bridge bridge = bridgesGet(id);
        if (bridge == null) {
            return;
        }

        List<Op> ops = bridgeZkManager.prepareBridgeDelete(id);
        String path = pathBuilder.getTenantBridgeNamePath(
                bridge.getProperty(Bridge.Property.tenant_id),
                bridge.getData().name);
        ops.add(bridgeZkManager.getDeleteOp(path));

        bridgeZkManager.multi(ops);
    }

    @Override
    public Chain chainsGet(UUID id) throws StateAccessException {
        log.debug("Entered: id={}", id);

        Chain chain = null;
        if (chainZkManager.exists(id)) {
            chain = Converter.fromChainConfig(chainZkManager.get(id));
            chain.setId(id);
        }

        log.debug("Exiting: chain={}", chain);
        return chain;
    }

    @Override
    public void chainsDelete(UUID id) throws StateAccessException {
        Chain chain = chainsGet(id);
        if (chain == null) {
            return;
        }

        List<Op> ops = chainZkManager.prepareChainDelete(id);
        String path = pathBuilder.getTenantChainNamePath(
                chain.getProperty(Chain.Property.tenant_id),
                chain.getData().name);
        ops.add(chainZkManager.getDeleteOp(path));

        chainZkManager.multi(ops);
    }

    @Override
    public UUID chainsCreate(@Nonnull Chain chain) throws StateAccessException {
        log.debug("chainsCreate entered: chain={}", chain);

        if (chain.getId() == null) {
            chain.setId(UUID.randomUUID());
        }

        ChainZkManager.ChainConfig chainConfig =
                Converter.toChainConfig(chain);

        List<Op> ops =
                chainZkManager.prepareChainCreate(chain.getId(), chainConfig);

        // Create the top level directories for
        String tenantId = chain.getProperty(Chain.Property.tenant_id);
        ops.addAll(tenantZkManager.prepareCreate(tenantId));

        // Create the chain names directory if it does not exist
        String chainNamesPath = pathBuilder.getTenantChainNamesPath(tenantId);
        if (!chainZkManager.exists(chainNamesPath)) {
            ops.add(chainZkManager.getPersistentCreateOp(
                    chainNamesPath, null));
        }

        // Index the name
        String chainNamePath = pathBuilder.getTenantChainNamePath(tenantId,
                chainConfig.name);
        byte[] data = serializer.serialize((new ChainName(chain)).getData());
        ops.add(chainZkManager.getPersistentCreateOp(chainNamePath, data));

        chainZkManager.multi(ops);

        log.debug("chainsCreate exiting: chain={}", chain);
        return chain.getId();
    }

    @Override
    public void subscribeToLocalActivePorts(Callback2<UUID, Boolean> cb) {
        //TODO(ross) notify when the port goes down
        subscriptionPortsActive.add(cb);
    }

    @Override
    public UUID tunnelZonesCreate(TunnelZone<?, ?> zone)
        throws StateAccessException {
        return zonesZkManager.createZone(zone, null);
    }

    @Override
    public void tunnelZonesDelete(UUID uuid)
        throws StateAccessException {
        zonesZkManager.deleteZone(uuid);
    }

    @Override
    public boolean tunnelZonesExists(UUID uuid) throws StateAccessException {
        return zonesZkManager.exists(uuid);
    }

    @Override
    public TunnelZone<?, ?> tunnelZonesGet(UUID uuid)
        throws StateAccessException {
        return zonesZkManager.getZone(uuid, null);
    }

    @Override
    public List<TunnelZone<?, ?>> tunnelZonesGetAll()
            throws StateAccessException {
        Collection<UUID> ids = zonesZkManager.getZoneIds();

        List<TunnelZone<?, ?>> tunnelZones = new ArrayList<TunnelZone<?, ?>>();

        for (UUID id : ids) {
            TunnelZone zone = tunnelZonesGet(id);
            if (zone != null) {
                tunnelZones.add(zone);
            }
        }

        return tunnelZones;
    }

    @Override
    public void tunnelZonesUpdate(TunnelZone<?, ?> zone)
            throws StateAccessException {
        zonesZkManager.updateZone(zone);
    }

    @Override
    public boolean tunnelZonesMembershipExists(UUID uuid, UUID hostId)
            throws StateAccessException {
        return zonesZkManager.membershipExists(uuid, hostId);
    }

    @Override
    public TunnelZone.HostConfig<?, ?> tunnelZonesGetMembership(UUID id,
                                                                UUID hostId)
        throws StateAccessException {
        return zonesZkManager.getZoneMembership(id, hostId, null);
    }

    @Override
    public Set<TunnelZone.HostConfig<?, ?>> tunnelZonesGetMemberships(
            final UUID uuid)
        throws StateAccessException {

        return CollectionFunctors.map(
            zonesZkManager.getZoneMemberships(uuid, null),
            new Functor<UUID, TunnelZone.HostConfig<?, ?>>() {
                @Override
                public TunnelZone.HostConfig<?, ?> apply(UUID arg0) {
                    try {
                        return zonesZkManager.getZoneMembership(uuid, arg0,
                                                                null);
                    } catch (StateAccessException e) {
                        //
                        return null;
                    }
                }
            },
            new HashSet<TunnelZone.HostConfig<?, ?>>()
        );
    }

    @Override
    public UUID tunnelZonesAddMembership(UUID zoneId, TunnelZone.HostConfig<?, ?> hostConfig)
        throws StateAccessException {
        zonesZkManager.delMembership(zoneId, hostConfig.getId());
        return zonesZkManager.addMembership(zoneId, hostConfig);
    }

    @Override
    public void tunnelZonesDeleteMembership(UUID zoneId, UUID membershipId)
        throws StateAccessException {
        zonesZkManager.delMembership(zoneId, membershipId);
    }

    @Override
    public void portsSetLocalAndActive(final UUID portID,
                                       final boolean active) {
        // use the reactor thread for this operations
        reactor.submit(new Runnable() {

            @Override
            public void run() {
                PortConfig config = null;
                try {
                    config = portZkManager.get(portID);
                } catch (StateAccessException e) {
                    log.error("Error retrieving the configuration for port {}",
                              portID, e);
                }
                // update the subscribers
                for (Callback2<UUID, Boolean> cb : subscriptionPortsActive) {
                    cb.call(portID, active);
                }
                if (config instanceof PortDirectory.MaterializedRouterPortConfig) {
                    UUID deviceId = config.device_id;
                    routerManager.updateRoutesBecauseLocalPortChangedStatus(deviceId,
                                                                 portID, active);
                }
            }
        });
}


    public Chain chainsGetByName(@Nonnull String tenantId, String name)
            throws StateAccessException {
        log.debug("Entered: tenantId={}, name={}", tenantId, name);

        Chain chain = null;
        String path = pathBuilder.getTenantChainNamePath(tenantId, name);

        if (chainZkManager.exists(path)) {
            byte[] data = chainZkManager.get(path);
            ChainName.Data chainNameData =
                    serializer.deserialize(data, ChainName.Data.class);
            chain = chainsGet(chainNameData.id);
        }

        log.debug("Exiting: chain={}", chain);
        return chain;
    }

    @Override
    public List<Chain> chainsFindByTenant(String tenantId)
            throws StateAccessException {
        log.debug("chainsFindByTenant entered: tenantId={}", tenantId);

        List<Chain> chains = new ArrayList<Chain>();

        String path = pathBuilder.getTenantChainNamesPath(tenantId);
        if (chainZkManager.exists(path)) {
            Set<String> chainNames = chainZkManager.getChildren(path);
            for (String name : chainNames) {
                Chain chain = chainsGetByName(tenantId, name);
                if (chain != null) {
                    chains.add(chain);
                }
            }
        }

        log.debug("chainsFindByTenant exiting: {} chains found",
                chains.size());
        return chains;
    }

    @Override
    public void dhcpSubnetsCreate(UUID bridgeId, Subnet subnet)
            throws StateAccessException {
        dhcpZkManager.createSubnet(bridgeId,
                                   Converter.toDhcpSubnetConfig(subnet));
    }

    @Override
    public void dhcpSubnetsUpdate(UUID bridgeId, Subnet subnet)
            throws StateAccessException {
        dhcpZkManager.updateSubnet(bridgeId,
                                   Converter.toDhcpSubnetConfig(subnet));
    }

    @Override
    public void dhcpSubnetsDelete(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        dhcpZkManager.deleteSubnet(bridgeId, subnetAddr);
    }

    @Override
    public Subnet dhcpSubnetsGet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {

        Subnet subnet = null;
        if (dhcpZkManager.existsSubnet(bridgeId, subnetAddr)) {
            BridgeDhcpZkManager.Subnet subnetConfig =
                dhcpZkManager.getSubnet(bridgeId, subnetAddr);

            subnet = Converter.fromDhcpSubnetConfig(subnetConfig);
            subnet.setId(subnetAddr.toString());
        }

        return subnet;
    }

    @Override
    public List<Subnet> dhcpSubnetsGetByBridge(UUID bridgeId)
            throws StateAccessException {

        List<IntIPv4> subnetConfigs = dhcpZkManager.listSubnets(bridgeId);
        List<Subnet> subnets = new ArrayList<Subnet>(subnetConfigs.size());

        for (IntIPv4 subnetConfig : subnetConfigs) {
            subnets.add(dhcpSubnetsGet(bridgeId, subnetConfig));
        }

        return subnets;
    }

    @Override
    public void dhcpHostsCreate(
            UUID bridgeId, IntIPv4 subnet,
            com.midokura.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException {

        dhcpZkManager.addHost(bridgeId, subnet,
                Converter.toDhcpHostConfig(host));
    }

    @Override
    public void dhcpHostsUpdate(
            UUID bridgeId, IntIPv4 subnet,
            com.midokura.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException {
        dhcpZkManager.updateHost(bridgeId, subnet,
                                 Converter.toDhcpHostConfig(host));
    }

    @Override
    public com.midokura.midonet.cluster.data.dhcp.Host dhcpHostsGet(
            UUID bridgeId, IntIPv4 subnet, String mac)
            throws StateAccessException {

        com.midokura.midonet.cluster.data.dhcp.Host host = null;
        if (dhcpZkManager.existsHost(bridgeId, subnet, mac)) {
            BridgeDhcpZkManager.Host hostConfig =
                    dhcpZkManager.getHost(bridgeId, subnet, mac);
            host = Converter.fromDhcpHostConfig(hostConfig);
            host.setId(MAC.fromString(mac));
        }

        return host;
    }

    @Override
    public void dhcpHostsDelete(UUID bridgId, IntIPv4 subnet, String mac)
            throws StateAccessException {
        dhcpZkManager.deleteHost(bridgId, subnet, mac);
    }

    @Override
    public
    List<com.midokura.midonet.cluster.data.dhcp.Host> dhcpHostsGetBySubnet(
            UUID bridgeId, IntIPv4 subnet) throws StateAccessException {

        List<BridgeDhcpZkManager.Host> hostConfigs =
                dhcpZkManager.getHosts(bridgeId, subnet);
        List<com.midokura.midonet.cluster.data.dhcp.Host> hosts =
                new ArrayList<com.midokura.midonet.cluster.data.dhcp.Host>();
        for (BridgeDhcpZkManager.Host hostConfig : hostConfigs) {
            hosts.add(Converter.fromDhcpHostConfig(hostConfig));
        }

        return hosts;
    }

    @Override
    public Host hostsGet(UUID hostId) throws StateAccessException {

        Host host = null;
        if (hostsExists(hostId)) {

            HostDirectory.Metadata hostMetadata =
                    hostZkManager.getHostMetadata(hostId);

            host = Converter.fromHostConfig(hostMetadata);
            host.setId(hostId);
            host.setIsAlive(hostsIsAlive(hostId));
        }

        return host;
    }

    @Override
    public void hostsDelete(UUID hostId) throws StateAccessException {
        hostZkManager.deleteHost(hostId);
    }

    @Override
    public boolean hostsExists(UUID hostId) throws StateAccessException {
        return hostZkManager.hostExists(hostId);
    }

    @Override
    public boolean hostsIsAlive(UUID hostId) throws StateAccessException {
        return hostZkManager.isAlive(hostId);
    }

    @Override
    public List<Host> hostsGetAll() throws StateAccessException {
        Collection<UUID> ids = hostZkManager.getHostIds();

        List<Host> hosts = new ArrayList<Host>();

        for (UUID id : ids) {
            try {
                Host host = hostsGet(id);
                if (host != null) {
                    hosts.add(host);
                }
            } catch (StateAccessException e) {
                log.warn(
                        "Tried to read the information of a host that vanished "
                                + "or become corrupted: {}", id, e);
            }
        }

        return hosts;
    }

    @Override
    public List<Interface> interfacesGetByHost(UUID hostId)
            throws StateAccessException {
        List<Interface> interfaces = new ArrayList<Interface>();

        Collection<String> interfaceNames =
                hostZkManager.getInterfaces(hostId);
        for (String interfaceName : interfaceNames) {
            try {
                Interface anInterface = interfacesGet(hostId, interfaceName);
                if (anInterface != null) {
                    interfaces.add(anInterface);
                }
            } catch (StateAccessException e) {
                log.warn(
                        "An interface description went missing in action while "
                                + "we were looking for it host: {}, interface: "
                                + "{}.",
                        new Object[] { hostId, interfaceName, e });
            }
        }

        return interfaces;
    }

    @Override
    public Interface interfacesGet(UUID hostId, String interfaceName)
            throws StateAccessException {
        Interface anInterface = null;

        if (hostZkManager.existsInterface(hostId, interfaceName)) {
            HostDirectory.Interface interfaceData =
                    hostZkManager.getInterfaceData(hostId, interfaceName);
            anInterface = Converter.fromHostInterfaceConfig(interfaceData);
            anInterface.setId(interfaceName);
        }

        return anInterface;
    }

    @Override
    public Integer commandsCreateForInterfaceupdate(UUID hostId,
                                                    String curInterfaceId,
                                                    Interface newInterface)
            throws StateAccessException {

        HostCommandGenerator commandGenerator = new HostCommandGenerator();

        HostDirectory.Interface curHostInterface = null;

        if (curInterfaceId != null) {
            curHostInterface = hostZkManager.getInterfaceData(hostId,
                    curInterfaceId);
        }

        HostDirectory.Interface newHostInterface =
                Converter.toHostInterfaceConfig(newInterface);

        HostDirectory.Command command = commandGenerator.createUpdateCommand(
                curHostInterface, newHostInterface);

        return hostZkManager.createHostCommandId(hostId, command);
    }

    @Override
    public List<Command> commandsGetByHost(UUID hostId)
            throws StateAccessException {
        List<Integer> commandsIds = hostZkManager.getCommandIds(hostId);
        List<Command> commands = new ArrayList<Command>();
        for (Integer commandsId : commandsIds) {

            Command hostCommand = commandsGet(hostId, commandsId);

            if (hostCommand != null) {
                commands.add(hostCommand);
            }
        }

        return commands;
    }

    @Override
    public Command commandsGet(UUID hostId, Integer id)
            throws StateAccessException {
        Command command = null;

        try {
            HostDirectory.Command hostCommand =
                    hostZkManager.getCommandData(hostId, id);

            HostDirectory.ErrorLogItem errorLogItem =
                    hostZkManager.getErrorLogData(hostId, id);

            command = Converter.fromHostCommandConfig(hostCommand);
            command.setId(id);

            if (errorLogItem != null) {
                command.setErrorLogItem(
                        Converter.fromHostErrorLogItemConfig(errorLogItem));
            }

        } catch (StateAccessException e) {
            log.warn("Could not read command with id {} from datastore "
                    + "(for host: {})", new Object[] { id, hostId, e });
            throw e;
        }

        return command;
    }

    @Override
    public void commandsDelete(UUID hostId, Integer id)
            throws StateAccessException {
        hostZkManager.deleteHostCommand(hostId, id);
    }

    @Override
    public List<VirtualPortMapping> hostsGetVirtualPortMappingsByHost(
            UUID hostId) throws StateAccessException {
        Set<HostDirectory.VirtualPortMapping> zkMaps =
                hostZkManager.getVirtualPortMappings(hostId, null);

        List<VirtualPortMapping> maps =
                new ArrayList<VirtualPortMapping>(zkMaps.size());

        for(HostDirectory.VirtualPortMapping zkMap : zkMaps) {
            maps.add(Converter.fromHostVirtPortMappingConfig(zkMap));
        }

        return maps;
    }

    @Override
    public boolean hostsVirtualPortMappingExists(UUID hostId, UUID portId)
            throws StateAccessException {
        return hostZkManager.virtualPortMappingExists(hostId, portId);
    }

    @Override
    public VirtualPortMapping hostsGetVirtualPortMapping(
            UUID hostId, UUID portId) throws StateAccessException {
        HostDirectory.VirtualPortMapping mapping =
                hostZkManager.getVirtualPortMapping(hostId, portId);

        if (mapping == null) {
            return null;
        }

        return Converter.fromHostVirtPortMappingConfig(mapping);
    }

    @Override
    public boolean portsExists(UUID id) throws StateAccessException {
        return portZkManager.exists(id);
    }

    @Override
    public void portsDelete(UUID id) throws StateAccessException {
        portZkManager.delete(id);
    }

    @Override
    public List<Port<?, ?>> portsFindByBridge(UUID bridgeId)
            throws StateAccessException {

        Set<UUID> ids = portZkManager.getBridgePortIDs(bridgeId);
        List<Port<?, ?>> ports = new ArrayList<Port<?, ?>>();
        for (UUID id : ids) {
            ports.add(portsGet(id));
        }

        ids = portZkManager.getBridgeLogicalPortIDs(bridgeId);
        for (UUID id : ids) {
            ports.add(portsGet(id));
        }

        return ports;
    }

    @Override
    public List<Port<?, ?>> portsFindPeersByBridge(UUID bridgeId)
            throws StateAccessException {

        Set<UUID> ids = portZkManager.getBridgeLogicalPortIDs(bridgeId);
        List<Port<?, ?>> ports = new ArrayList<Port<?, ?>>();
        for (UUID id : ids) {
            Port<?, ?> portData = portsGet(id);
            if (portData instanceof LogicalBridgePort &&
                    ((LogicalBridgePort) portData).getPeerId() != null) {
                ports.add(portsGet(((LogicalBridgePort) portData).getPeerId()));
            }
        }

        return ports;
    }

    @Override
    public List<Port<?, ?>> portsFindByRouter(UUID routerId)
            throws StateAccessException {

        Set<UUID> ids = portZkManager.getRouterPortIDs(routerId);
        List<Port<?, ?>> ports = new ArrayList<Port<?, ?>>();
        for (UUID id : ids) {
            ports.add(portsGet(id));
        }

        return ports;
    }

    @Override
    public List<Port<?, ?>> portsFindPeersByRouter(UUID routerId)
            throws StateAccessException {

        Set<UUID> ids = portZkManager.getRouterPortIDs(routerId);
        List<Port<?, ?>> ports = new ArrayList<Port<?, ?>>();
        for (UUID id : ids) {
            Port<?, ?> portData = portsGet(id);
            if (portData instanceof LogicalRouterPort &&
                    ((LogicalRouterPort) portData).getPeerId() != null) {
                ports.add(portsGet(((LogicalRouterPort) portData).getPeerId()));
            }
        }

        return ports;
    }

    @Override
    public UUID portsCreate(final Port port) throws StateAccessException {
        Future<UUID> portIdFuture = reactor.submit(new Callable<UUID>(){
            @Override
            public UUID call() throws Exception {
                return portZkManager.create(Converter.toPortConfig(port));
            }
        }
        );
        UUID portId = null;
        try {
            portId = portIdFuture.get();
        } catch (Exception e) {
            log.error("portsCreate {}, got exception", port, e);
        }
        return portId;
    }

    @Override
    public Port portsGet(UUID id) throws StateAccessException {
        Port port = null;
        if (portZkManager.exists(id)) {
            port = Converter.fromPortConfig(portZkManager.get(id));
            port.setId(id);
        }

        return port;
    }

    @Override
    public void portsUpdate(@Nonnull Port port) throws StateAccessException {
        log.debug("portsUpdate entered: port={}", port);

        // Whatever sent is what gets stored.
        portZkManager.update(UUID.fromString(port.getId().toString()),
                Converter.toPortConfig(port));

        log.debug("portsUpdate exiting");
    }

    @Override
    public void portsLink(@Nonnull UUID portId, @Nonnull UUID peerPortId)
            throws StateAccessException {

        portZkManager.link(portId, peerPortId);

    }

    @Override
    public void portsUnlink(@Nonnull UUID portId) throws StateAccessException {
        portZkManager.unlink(portId);
    }

    @Override
    public List<Port<?, ?>> portsFindByPortGroup(UUID portGroupId)
            throws StateAccessException {
        Set<UUID> portIds = portZkManager.getPortGroupPortIds(portGroupId);
        List<Port<?, ?>> ports = new ArrayList<Port<?, ?>>(portIds.size());
        for (UUID portId : portIds) {
            ports.add(portsGet(portId));
        }

        return ports;
    }

    @Override
    public PortGroup portGroupsGetByName(String tenantId, String name)
            throws StateAccessException {
        log.debug("Entered: tenantId={}, name={}", tenantId, name);

        PortGroup portGroup = null;
        String path = pathBuilder.getTenantPortGroupNamePath(tenantId, name)
                .toString();

        if (portGroupZkManager.exists(path)) {
            byte[] data = portGroupZkManager.get(path);
            PortGroupName.Data portGroupNameData =
                    serializer.deserialize(data, PortGroupName.Data.class);
            portGroup = portGroupsGet(portGroupNameData.id);
        }

        log.debug("Exiting: portGroup={}", portGroup);
        return portGroup;
    }

    @Override
    public List<PortGroup> portGroupsFindByTenant(String tenantId)
            throws StateAccessException  {
        log.debug("portGroupsFindByTenant entered: tenantId={}", tenantId);

        List<PortGroup> portGroups = new ArrayList<PortGroup>();

        String path = pathBuilder.getTenantPortGroupNamesPath(tenantId);
        if (portGroupZkManager.exists(path)) {
            Set<String> portGroupNames = portGroupZkManager.getChildren(path);
            for (String name : portGroupNames) {
                PortGroup portGroup = portGroupsGetByName(tenantId, name);
                if (portGroup != null) {
                    portGroups.add(portGroup);
                }
            }
        }

        log.debug("portGroupsFindByTenant exiting: {} portGroups found",
                portGroups.size());
        return portGroups;
    }

    @Override
    public boolean portGroupsIsPortMember(@Nonnull UUID id,
                                          @Nonnull UUID portId)
        throws StateAccessException {
        return portGroupZkManager.portIsMember(id, portId);
    }

    @Override
    public void portGroupsAddPortMembership(@Nonnull UUID id,
                                            @Nonnull UUID portId)
            throws StateAccessException {
        portGroupZkManager.addPortToPortGroup(id, portId);
    }

    @Override
    public void portGroupsRemovePortMembership(@Nonnull UUID id,
                                               @Nonnull UUID portId)
            throws StateAccessException {
        portGroupZkManager.removePortFromPortGroup(id, portId);
    }

    @Override
    public UUID portGroupsCreate(@Nonnull PortGroup portGroup)
            throws StateAccessException {
        log.debug("portGroupsCreate entered: portGroup={}", portGroup);

        if (portGroup.getId() == null) {
            portGroup.setId(UUID.randomUUID());
        }

        PortGroupZkManager.PortGroupConfig portGroupConfig =
                Converter.toPortGroupConfig(portGroup);

        List<Op> ops =
                portGroupZkManager.prepareCreate(portGroup.getId(),
                        portGroupConfig);

        // Create the top level directories for
        String tenantId = portGroup.getProperty(PortGroup.Property.tenant_id);
        ops.addAll(tenantZkManager.prepareCreate(tenantId));

        // Create the portGroup names directory if it does not exist
        String portGroupNamesPath = pathBuilder.getTenantPortGroupNamesPath(
                tenantId);
        if (!portGroupZkManager.exists(portGroupNamesPath)) {
            ops.add(portGroupZkManager.getPersistentCreateOp(
                    portGroupNamesPath, null));
        }

        // Index the name
        String portGroupNamePath = pathBuilder.getTenantPortGroupNamePath(
                tenantId, portGroupConfig.name);
        byte[] data = serializer.serialize(
                (new PortGroupName(portGroup)).getData());
        ops.add(portGroupZkManager.getPersistentCreateOp(portGroupNamePath,
                data));

        portGroupZkManager.multi(ops);

        log.debug("portGroupsCreate exiting: portGroup={}", portGroup);
        return portGroup.getId();
    }

    @Override
    public boolean portGroupsExists(UUID id) throws StateAccessException {
        return portGroupZkManager.exists(id);
    }

    @Override
    public PortGroup portGroupsGet(UUID id) throws StateAccessException {
        log.debug("Entered: id={}", id);

        PortGroup portGroup = null;
        if (portGroupZkManager.exists(id)) {
            portGroup = Converter.fromPortGroupConfig(
                    portGroupZkManager.get(id));
            portGroup.setId(id);
        }

        log.debug("Exiting: portGroup={}", portGroup);
        return portGroup;
    }

    @Override
    public void portGroupsDelete(UUID id) throws StateAccessException {

        PortGroup portGroup = portGroupsGet(id);
        if (portGroup == null) {
            return;
        }

        List<Op> ops = portGroupZkManager.prepareDelete(id);
        String path = pathBuilder.getTenantPortGroupNamePath(
                portGroup.getProperty(PortGroup.Property.tenant_id),
                portGroup.getData().name);
        ops.add(portGroupZkManager.getDeleteOp(path));

        portGroupZkManager.multi(ops);
    }

    @Override
    public UUID hostsCreate(UUID hostId, Host host) throws StateAccessException {
        hostZkManager.createHost(hostId, Converter.toHostConfig(host));
        return hostId;
    }

    @Override
    public void hostsAddVrnPortMapping(UUID hostId, UUID portId, String localPortName)
            throws StateAccessException {
        hostZkManager.addVirtualPortMapping(
            hostId, new HostDirectory.VirtualPortMapping(portId, localPortName));
    }

    @Override
    public void hostsAddDatapathMapping(UUID hostId, String datapathName)
            throws StateAccessException {
        hostZkManager.addVirtualDatapathMapping(hostId, datapathName);
    }

    @Override
    public void hostsDelVrnPortMapping(UUID hostId, UUID portId)
            throws StateAccessException {
        hostZkManager.delVirtualPortMapping(hostId, portId);
    }

    @Override
    public Map<String, Long> metricsGetTSPoints(String type,
                                                String targetIdentifier,
                                                String metricName,
                                                long timeStart, long timeEnd) {
        return monitoringStore.getTSPoints(type, targetIdentifier, metricName,
                timeStart, timeEnd);
    }

    @Override
    public void metricsAddTypeToTarget(String targetIdentifier, String type) {
        monitoringStore.addMetricTypeToTarget(targetIdentifier, type);
    }

    @Override
    public List<String> metricsGetTypeForTarget(String targetIdentifier) {
        return monitoringStore.getMetricsTypeForTarget(targetIdentifier);
    }

    @Override
    public void metricsAddToType(String type, String metricName) {
        monitoringStore.addMetricToType(type, metricName);
    }

    @Override
    public List<String> metricsGetForType(String type) {
        return monitoringStore.getMetricsForType(type);
    }


    @Override
    public Route routesGet(UUID id) throws StateAccessException {
        log.debug("Entered: id={}", id);

        Route route = null;
        if (routeZkManager.exists(id)) {
            route = Converter.fromRouteConfig(routeZkManager.get(id));
            route.setId(id);
        }

        log.debug("Exiting: route={}", route);
        return route;
    }

    @Override
    public void routesDelete(UUID id) throws StateAccessException {
        routeZkManager.delete(id);
    }

    @Override
    public UUID routesCreate(@Nonnull Route route) throws StateAccessException {
        return routeZkManager.create(Converter.toRouteConfig(route));
    }

    @Override
    public UUID routesCreateEphemeral(@Nonnull Route route) throws StateAccessException {
        return routeZkManager.create(Converter.toRouteConfig(route), false);
    }

    @Override
    public List<Route> routesFindByRouter(UUID routerId)
            throws StateAccessException {

        List<UUID> routeIds = routeZkManager.list(routerId);
        List<Route> routes = new ArrayList<Route>();
        for (UUID id : routeIds) {
            routes.add(routesGet(id));
        }
        return routes;

    }

    @Override
    public Router routersGet(UUID id) throws StateAccessException {
        log.debug("Entered: id={}", id);

        Router router = null;
        if (routerZkManager.exists(id)) {
            router = Converter.fromRouterConfig(routerZkManager.get(id));
            router.setId(id);
        }

        log.debug("Exiting: router={}", router);
        return router;
    }

    @Override
    public void routersDelete(UUID id) throws StateAccessException {
        Router router = routersGet(id);
        if (router == null) {
            return;
        }

        List<Op> ops = routerZkManager.prepareRouterDelete(id);
        String path = pathBuilder.getTenantRouterNamePath(
                router.getProperty(Router.Property.tenant_id),
                router.getData().name);
        ops.add(routerZkManager.getDeleteOp(path));

        routerZkManager.multi(ops);
    }

    @Override
    public UUID routersCreate(@Nonnull Router router)
            throws StateAccessException {
        log.debug("routersCreate entered: router={}", router);

        if (router.getId() == null) {
            router.setId(UUID.randomUUID());
        }

        RouterZkManager.RouterConfig routerConfig =
                Converter.toRouterConfig(router);

        List<Op> ops =
                routerZkManager.prepareRouterCreate(router.getId(),
                        routerConfig);

        // Create the top level directories
        String tenantId = router.getProperty(Router.Property.tenant_id);
        ops.addAll(tenantZkManager.prepareCreate(tenantId));

        // Create the router names directory if it does not exist
        String routerNamesPath = pathBuilder.getTenantRouterNamesPath(tenantId);
        if (!routerZkManager.exists(routerNamesPath)) {
            ops.add(routerZkManager.getPersistentCreateOp(
                    routerNamesPath, null));
        }

        // Index the name
        String routerNamePath = pathBuilder.getTenantRouterNamePath(tenantId,
                routerConfig.name);
        byte[] data = serializer.serialize((new RouterName(router)).getData());
        ops.add(routerZkManager.getPersistentCreateOp(routerNamePath, data));

        routerZkManager.multi(ops);

        log.debug("routersCreate exiting: router={}", router);
        return router.getId();
    }

    @Override
    public void routersUpdate(@Nonnull Router router) throws
            StateAccessException {
        List<Op> ops = new ArrayList<Op>();

        // Get the original data
        Router oldRouter = routersGet(router.getId());

        RouterZkManager.RouterConfig routerConfig = Converter.toRouterConfig(
            router);


        // Update the config
        Op op = routerZkManager.prepareUpdate(router.getId(), routerConfig);
        if (op != null) {
            ops.add(op);
        }

        // Update index if the name changed
        String oldName = oldRouter.getData().name;
        String newName = routerConfig.name;
        if (oldName == null ? newName != null : !oldName.equals(newName)) {

            String tenantId = oldRouter.getProperty(Router.Property.tenant_id);

            String path = pathBuilder.getTenantRouterNamePath(tenantId,
                    oldRouter.getData().name);
            ops.add(routerZkManager.getDeleteOp(path));

            path = pathBuilder.getTenantRouterNamePath(tenantId,
                    routerConfig.name);
            byte[] data = serializer.serialize(new RouterName(router)
                                                   .getData());
            ops.add(routerZkManager.getPersistentCreateOp(path, data));
        }

        if (ops.size() > 0) {
            routerZkManager.multi(ops);
        }
    }

    @Override
    public Router routersGetByName(String tenantId, String name)
            throws StateAccessException {
        log.debug("Entered: tenantId={}, name={}", tenantId, name);

        Router router = null;
        String path = pathBuilder.getTenantRouterNamePath(tenantId, name)
                .toString();

        if (routerZkManager.exists(path)) {
            byte[] data = routerZkManager.get(path);
            RouterName.Data routerNameData =
                    serializer.deserialize(data, RouterName.Data.class);
            router = routersGet(routerNameData.id);
        }

        log.debug("Exiting: router={}", router);
        return router;
    }

    @Override
    public List<Router> routersFindByTenant(String tenantId)
            throws StateAccessException {
        log.debug("routersFindByTenant entered: tenantId={}", tenantId);

        List<Router> routers = new ArrayList<Router>();

        String path = pathBuilder.getTenantRouterNamesPath(tenantId);
        if (routerZkManager.exists(path)) {
            Set<String> routerNames = routerZkManager.getChildren(path);
            for (String name : routerNames) {
                Router router = routersGetByName(tenantId, name);
                if (router != null) {
                    routers.add(router);
                }
            }
        }

        log.debug("routersFindByTenant exiting: {} routers found",
                routers.size());
        return routers;
    }

    @Override
    public Rule<?, ?> rulesGet(UUID id) throws StateAccessException {
        log.debug("Entered: id={}", id);

        Rule rule = null;
        if (ruleZkManager.exists(id)) {
            rule = Converter.fromRuleConfig(ruleZkManager.get(id));
            rule.setId(id);
        }

        log.debug("Exiting: rule={}", rule);
        return rule;
    }

    @Override
    public void rulesDelete(UUID id) throws StateAccessException {
        ruleZkManager.delete(id);
    }

    @Override
    public UUID rulesCreate(@Nonnull Rule<?, ?> rule)
            throws StateAccessException, RuleIndexOutOfBoundsException {
        return ruleZkManager.create(Converter.toRuleConfig(rule));
    }

    @Override
    public List<Rule<?, ?>> rulesFindByChain(UUID chainId)
            throws StateAccessException {
        Set<UUID> ruleIds = ruleZkManager.getRuleIds(chainId);
        List<Rule<?, ?>> rules = new ArrayList<Rule<?, ?>>();
        for (UUID id : ruleIds) {
            rules.add(rulesGet(id));
        }
        return rules;
    }

    @Override
    public VPN vpnGet(UUID id) throws StateAccessException {
        log.debug("Entered: id={}", id);

        VPN vpn = null;
        if (vpnZkManager.exists(id)) {
            vpn = Converter.fromVpnConfig(vpnZkManager.get(id));
            vpn.setId(id);
        }

        log.debug("Exiting: vpn={}", vpn);
        return vpn;
    }

    @Override
    public void vpnDelete(UUID id) throws StateAccessException {
        vpnZkManager.delete(id);
    }

    @Override
    public UUID vpnCreate(@Nonnull VPN vpn) throws StateAccessException {
        return vpnZkManager.create(Converter.toVpnConfig(vpn));
    }

    @Override
    public List<VPN> vpnFindByPort(UUID portId) throws StateAccessException {
        List<UUID> vpnIds = vpnZkManager.list(portId);
        List<VPN> vpns = new ArrayList<VPN>();
        for (UUID vpnId : vpnIds) {
            vpns.add(vpnGet(vpnId));
        }
        return vpns;
    }

    @Override
    public void portSetsAsyncAddHost(UUID portSetId, UUID hostId,
                                     DirectoryCallback.Add callback) {
        portSetZkManager.addMemberAsync(portSetId, hostId, callback);
    }

    @Override
    public void portSetsAddHost(UUID portSetId, UUID hostId)
        throws StateAccessException {
        portSetZkManager.addMember(portSetId, hostId);
    }

    @Override
    public void portSetsAsyncDelHost(UUID portSetId, UUID hostId,
                                     DirectoryCallback.Void callback) {
        portSetZkManager.delMemberAsync(portSetId, hostId, callback);
    }

    @Override
    public void portSetsDelHost(UUID portSetId, UUID hostId)
        throws StateAccessException {
        portSetZkManager.delMember(portSetId, hostId);
    }

    @Override
    public Set<UUID> portSetsGet(UUID portSetId) throws StateAccessException {
        return portSetZkManager.getPortSet(portSetId, null);
    }

}
