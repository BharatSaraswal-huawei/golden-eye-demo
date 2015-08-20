/*
 * Copyright 2015 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.onosproject.ui.impl;

import com.google.common.collect.ImmutableList;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.Link;
import org.onosproject.net.LinkKey;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions.OutputInstruction;
import org.onosproject.net.intent.FlowRuleIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.LinkCollectionIntent;
import org.onosproject.net.intent.OpticalConnectivityIntent;
import org.onosproject.net.intent.OpticalPathIntent;
import org.onosproject.net.intent.PathIntent;
import org.onosproject.net.statistic.Load;
import org.onosproject.ui.impl.topo.BiLink;
import org.onosproject.ui.impl.topo.IntentSelection;
import org.onosproject.ui.impl.topo.LinkStatsType;
import org.onosproject.ui.impl.topo.NodeSelection;
import org.onosproject.ui.impl.topo.ServicesBundle;
import org.onosproject.ui.impl.topo.TopoUtils;
import org.onosproject.ui.impl.topo.TopologyViewIntentFilter;
import org.onosproject.ui.impl.topo.TrafficClass;
import org.onosproject.ui.topo.Highlights;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import static org.onosproject.net.DefaultEdgeLink.createEdgeLink;
import static org.onosproject.ui.impl.TrafficMonitorObject.Mode.IDLE;
import static org.onosproject.ui.impl.TrafficMonitorObject.Mode.SEL_INTENT;
import static org.onosproject.ui.topo.LinkHighlight.Flavor.PRIMARY_HIGHLIGHT;
import static org.onosproject.ui.topo.LinkHighlight.Flavor.SECONDARY_HIGHLIGHT;

/**
 * Encapsulates the behavior of monitoring specific traffic patterns.
 */
public class TrafficMonitorObject {

    // 4 Kilo Bytes as threshold
    private static final double BPS_THRESHOLD = 4 * TopoUtils.KILO;

    private static final Logger log =
            LoggerFactory.getLogger(TrafficMonitorObject.class);

    /**
     * Designates the different modes of operation.
     */
    public enum Mode {
        IDLE,
        ALL_FLOW_TRAFFIC,
        ALL_PORT_TRAFFIC,
        DEV_LINK_FLOWS,
        RELATED_INTENTS,
        SEL_INTENT
    }

    private final long trafficPeriod;
    private final ServicesBundle servicesBundle;
    private final TopologyViewMessageHandler messageHandler;
    private final TopologyViewIntentFilter intentFilter;

    private final Timer timer = new Timer("topo-traffic");

    private TimerTask trafficTask = null;
    private Mode mode = IDLE;
    private NodeSelection selectedNodes = null;
    private IntentSelection selectedIntents = null;


    /**
     * Constructs a traffic monitor.
     *
     * @param trafficPeriod   traffic task period in ms
     * @param servicesBundle  bundle of services
     * @param messageHandler  our message handler
     */
    public TrafficMonitorObject(long trafficPeriod,
                                ServicesBundle servicesBundle,
                                TopologyViewMessageHandler messageHandler) {
        this.trafficPeriod = trafficPeriod;
        this.servicesBundle = servicesBundle;
        this.messageHandler = messageHandler;

        intentFilter = new TopologyViewIntentFilter(servicesBundle);
    }

    // =======================================================================
    // === API === // TODO: add javadocs

    public synchronized void monitor(Mode mode) {
        log.debug("monitor: {}", mode);
        this.mode = mode;

        switch (mode) {
            case ALL_FLOW_TRAFFIC:
                clearSelection();
                scheduleTask();
                sendAllFlowTraffic();
                break;

            case ALL_PORT_TRAFFIC:
                clearSelection();
                scheduleTask();
                sendAllPortTraffic();
                break;

            case SEL_INTENT:
                scheduleTask();
                sendSelectedIntentTraffic();
                break;

            default:
                log.debug("Unexpected call to monitor({})", mode);
                clearAll();
                break;
        }
    }

    public synchronized void monitor(Mode mode, NodeSelection nodeSelection) {
        log.debug("monitor: {} -- {}", mode, nodeSelection);
        this.mode = mode;
        this.selectedNodes = nodeSelection;

        switch (mode) {
            case DEV_LINK_FLOWS:
                // only care about devices (not hosts)
                if (selectedNodes.devices().isEmpty()) {
                    sendClearAll();
                } else {
                    scheduleTask();
                    sendDeviceLinkFlows();
                }
                break;

            case RELATED_INTENTS:
                if (selectedNodes.none()) {
                    sendClearAll();
                } else {
                    selectedIntents = new IntentSelection(selectedNodes, intentFilter);
                    if (selectedIntents.none()) {
                        sendClearAll();
                    } else {
                        sendSelectedIntents();
                    }
                }
                break;

            default:
                log.debug("Unexpected call to monitor({}, {})", mode, nodeSelection);
                clearAll();
                break;
        }
    }

    public synchronized void monitor(Intent intent) {
        log.debug("monitor intent: {}", intent.id());
        selectedNodes = null;
        selectedIntents = new IntentSelection(intent);
        mode = SEL_INTENT;
        scheduleTask();
        sendSelectedIntentTraffic();
    }

    public synchronized void selectNextIntent() {
        if (selectedIntents != null) {
            selectedIntents.next();
            sendSelectedIntents();
        }
    }

    public synchronized void selectPreviousIntent() {
        if (selectedIntents != null) {
            selectedIntents.prev();
            sendSelectedIntents();
        }
    }

    public synchronized void pokeIntent() {
        if (mode == SEL_INTENT) {
            sendSelectedIntentTraffic();
        }
    }

    public synchronized void stop() {
        log.debug("STOP");
        if (mode != IDLE) {
            sendClearAll();
        }
    }


    // =======================================================================
    // === Helper methods ===

    private void sendClearAll() {
        clearAll();
        sendClearHighlights();
    }

    private void clearAll() {
        this.mode = IDLE;
        clearSelection();
        cancelTask();
    }

    private void clearSelection() {
        selectedNodes = null;
        selectedIntents = null;
    }

    private synchronized void  scheduleTask() {
        if (trafficTask == null) {
            log.debug("Starting up background traffic task...");
            trafficTask = new TrafficMonitor();
            timer.schedule(trafficTask, trafficPeriod, trafficPeriod);
        } else {
            // TEMPORARY until we are sure this is working correctly
            log.debug("(traffic task already running)");
        }
    }

    private synchronized void cancelTask() {
        if (trafficTask != null) {
            trafficTask.cancel();
            trafficTask = null;
        }
    }

    // ---

    private void sendAllFlowTraffic() {
        log.debug("sendAllFlowTraffic");
        sendHighlights(trafficSummary(LinkStatsType.FLOW_STATS));
    }

    private void sendAllPortTraffic() {
        log.debug("sendAllPortTraffic");
        sendHighlights(trafficSummary(LinkStatsType.PORT_STATS));
    }

    private void sendDeviceLinkFlows() {
        log.debug("sendDeviceLinkFlows: {}", selectedNodes);
        sendHighlights(deviceLinkFlows());
    }

    private void sendSelectedIntents() {
        log.debug("sendSelectedIntents: {}", selectedIntents);
        sendHighlights(intentGroup());
    }

    private void sendSelectedIntentTraffic() {
        log.debug("sendSelectedIntentTraffic: {}", selectedIntents);
        sendHighlights(intentTraffic());
    }

    private void sendClearHighlights() {
        log.debug("sendClearHighlights");
        sendHighlights(new Highlights());
    }

    private void sendHighlights(Highlights highlights) {
        messageHandler.sendHighlights(highlights);
    }


    // =======================================================================
    // === Generate messages in JSON object node format

    private Highlights trafficSummary(LinkStatsType type) {
        Highlights highlights = new Highlights();

        // compile a set of bilinks (combining pairs of unidirectional links)
        Map<LinkKey, BiLink> linkMap = new HashMap<>();
        compileLinks(linkMap);
        addEdgeLinks(linkMap);

        for (BiLink blink : linkMap.values()) {
            if (type == LinkStatsType.FLOW_STATS) {
                attachFlowLoad(blink);
            } else if (type == LinkStatsType.PORT_STATS) {
                attachPortLoad(blink);
            }

            // we only want to report on links deemed to have traffic
            if (blink.hasTraffic()) {
                highlights.add(blink.generateHighlight(type));
            }
        }
        return highlights;
    }

    // create highlights for links, showing flows for selected devices.
    private Highlights deviceLinkFlows() {
        Highlights highlights = new Highlights();

        if (selectedNodes != null && !selectedNodes.devices().isEmpty()) {
            // capture flow counts on bilinks
            Map<LinkKey, BiLink> linkMap = new HashMap<>();

            for (Device device : selectedNodes.devices()) {
                Map<Link, Integer> counts = getLinkFlowCounts(device.id());
                for (Link link : counts.keySet()) {
                    BiLink blink = TopoUtils.addLink(linkMap, link);
                    blink.addFlows(counts.get(link));
                }
            }

            // now report on our collated links
            for (BiLink blink : linkMap.values()) {
                highlights.add(blink.generateHighlight(LinkStatsType.FLOW_COUNT));
            }

        }
        return highlights;
    }

    private Highlights intentGroup() {
        Highlights highlights = new Highlights();

        if (selectedIntents != null && !selectedIntents.none()) {
            // If 'all' intents are selected, they will all have primary
            // highlighting; otherwise, the specifically selected intent will
            // have primary highlighting, and the remainder will have secondary
            // highlighting.
            Set<Intent> primary;
            Set<Intent> secondary;
            int count = selectedIntents.size();

            Set<Intent> allBut = new HashSet<>(selectedIntents.intents());
            Intent current;

            if (selectedIntents.all()) {
                primary = allBut;
                secondary = Collections.emptySet();
                log.debug("Highlight all intents ({})", count);
            } else {
                current = selectedIntents.current();
                primary = new HashSet<>();
                primary.add(current);
                allBut.remove(current);
                secondary = allBut;
                log.debug("Highlight intent: {} ([{}] of {})",
                          current.id(), selectedIntents.index(), count);
            }
            TrafficClass tc1 = new TrafficClass(PRIMARY_HIGHLIGHT, primary);
            TrafficClass tc2 = new TrafficClass(SECONDARY_HIGHLIGHT, secondary);
            // classify primary links after secondary (last man wins)
            highlightIntents(highlights, tc2, tc1);
        }
        return highlights;
    }

    private Highlights intentTraffic() {
        Highlights highlights = new Highlights();

        if (selectedIntents != null && selectedIntents.single()) {
            Intent current = selectedIntents.current();
            Set<Intent> primary = new HashSet<>();
            primary.add(current);
            log.debug("Highlight traffic for intent: {} ([{}] of {})",
                      current.id(), selectedIntents.index(), selectedIntents.size());
            TrafficClass tc1 = new TrafficClass(PRIMARY_HIGHLIGHT, primary, true);
            highlightIntents(highlights, tc1);
        }
        return highlights;
    }


    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    private void compileLinks(Map<LinkKey, BiLink> linkMap) {
        servicesBundle.linkService().getLinks()
                .forEach(link -> TopoUtils.addLink(linkMap, link));
    }

    private void addEdgeLinks(Map<LinkKey, BiLink> biLinks) {
        servicesBundle.hostService().getHosts().forEach(host -> {
            TopoUtils.addLink(biLinks, createEdgeLink(host, true));
            TopoUtils.addLink(biLinks, createEdgeLink(host, false));
        });
    }

    private Load getLinkFlowLoad(Link link) {
        if (link != null && link.src().elementId() instanceof DeviceId) {
            return servicesBundle.flowStatsService().load(link);
        }
        return null;
    }

    private void attachFlowLoad(BiLink link) {
        link.addLoad(getLinkFlowLoad(link.one()));
        link.addLoad(getLinkFlowLoad(link.two()));
    }

    private void attachPortLoad(BiLink link) {
        // For bi-directional traffic links, use
        // the max link rate of either direction
        // (we choose 'one' since we know that is never null)
        Link one = link.one();
        Load egressSrc = servicesBundle.portStatsService().load(one.src());
        Load egressDst = servicesBundle.portStatsService().load(one.dst());
//        link.addLoad(maxLoad(egressSrc, egressDst), BPS_THRESHOLD);
        link.addLoad(maxLoad(egressSrc, egressDst), 10);    // FIXME - debug only
    }

    private Load maxLoad(Load a, Load b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.rate() > b.rate() ? a : b;
    }

    // ---

    // Counts all flow entries that egress on the links of the given device.
    private Map<Link, Integer> getLinkFlowCounts(DeviceId deviceId) {
        // get the flows for the device
        List<FlowEntry> entries = new ArrayList<>();
        for (FlowEntry flowEntry : servicesBundle.flowService().getFlowEntries(deviceId)) {
            entries.add(flowEntry);
        }

        // get egress links from device, and include edge links
        Set<Link> links = new HashSet<>(servicesBundle.linkService().getDeviceEgressLinks(deviceId));
        Set<Host> hosts = servicesBundle.hostService().getConnectedHosts(deviceId);
        if (hosts != null) {
            for (Host host : hosts) {
                links.add(createEdgeLink(host, false));
            }
        }

        // compile flow counts per link
        Map<Link, Integer> counts = new HashMap<>();
        for (Link link : links) {
            counts.put(link, getEgressFlows(link, entries));
        }
        return counts;
    }

    // Counts all entries that egress on the link source port.
    private int getEgressFlows(Link link, List<FlowEntry> entries) {
        int count = 0;
        PortNumber out = link.src().port();
        for (FlowEntry entry : entries) {
            TrafficTreatment treatment = entry.treatment();
            for (Instruction instruction : treatment.allInstructions()) {
                if (instruction.type() == Instruction.Type.OUTPUT &&
                        ((OutputInstruction) instruction).port().equals(out)) {
                    count++;
                }
            }
        }
        return count;
    }

    // ---
    private void highlightIntents(Highlights highlights,
                                  TrafficClass... trafficClasses) {
        Map<LinkKey, BiLink> linkMap = new HashMap<>();


        for (TrafficClass trafficClass : trafficClasses) {
            classifyLinkTraffic(linkMap, trafficClass);
        }

        for (BiLink blink : linkMap.values()) {
            highlights.add(blink.generateHighlight(LinkStatsType.TAGGED));
        }
    }

    private void classifyLinkTraffic(Map<LinkKey, BiLink> linkMap,
                                     TrafficClass trafficClass) {
        for (Intent intent : trafficClass.intents()) {
            boolean isOptical = intent instanceof OpticalConnectivityIntent;
            List<Intent> installables = servicesBundle.intentService()
                    .getInstallableIntents(intent.key());
            Iterable<Link> links = null;

            if (installables != null) {
                for (Intent installable : installables) {

                    if (installable instanceof PathIntent) {
                        links = ((PathIntent) installable).path().links();
                    } else if (installable instanceof FlowRuleIntent) {
                        links = linkResources(installable);
                    } else if (installable instanceof LinkCollectionIntent) {
                        links = ((LinkCollectionIntent) installable).links();
                    } else if (installable instanceof OpticalPathIntent) {
                        links = ((OpticalPathIntent) installable).path().links();
                    }

                    classifyLinks(trafficClass, isOptical, linkMap, links);
                }
            }
        }
    }

    private void classifyLinks(TrafficClass trafficClass, boolean isOptical,
                               Map<LinkKey, BiLink> linkMap,
                               Iterable<Link> links) {
        if (links != null) {
            for (Link link : links) {
                BiLink blink = TopoUtils.addLink(linkMap, link);
                if (trafficClass.showTraffic()) {
                    blink.addLoad(getLinkFlowLoad(link));
                    blink.setAntMarch(true);
                }
                blink.setOptical(isOptical);
                blink.tagFlavor(trafficClass.flavor());
            }
        }
    }

    // Extracts links from the specified flow rule intent resources
    private Collection<Link> linkResources(Intent installable) {
        ImmutableList.Builder<Link> builder = ImmutableList.builder();
        installable.resources().stream().filter(r -> r instanceof Link)
                .forEach(r -> builder.add((Link) r));
        return builder.build();
    }

    // =======================================================================
    // === Background Task

    // Provides periodic update of traffic information to the client
    private class TrafficMonitor extends TimerTask {
        @Override
        public void run() {
            try {
                switch (mode) {
                    case ALL_FLOW_TRAFFIC:
                        sendAllFlowTraffic();
                        break;
                    case ALL_PORT_TRAFFIC:
                        sendAllPortTraffic();
                        break;
                    case DEV_LINK_FLOWS:
                        sendDeviceLinkFlows();
                        break;
                    case SEL_INTENT:
                        sendSelectedIntentTraffic();
                        break;

                    default:
                        // RELATED_INTENTS and IDLE modes should never invoke
                        // the background task, but if they do, they have
                        // nothing to do
                        break;
                }

            } catch (Exception e) {
                log.warn("Unable to process traffic task due to {}", e.getMessage());
                log.warn("Boom!", e);
            }
        }
    }

}
