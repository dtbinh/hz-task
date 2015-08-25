package ro.fortsoft.hztask.master;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.eventbus.AsyncEventBus;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.fortsoft.hztask.common.HzKeysConstants;
import ro.fortsoft.hztask.common.MemberType;
import ro.fortsoft.hztask.common.task.Task;
import ro.fortsoft.hztask.master.event.membership.AgentMembershipSubscriber;
import ro.fortsoft.hztask.master.listener.ClusterMembershipListener;
import ro.fortsoft.hztask.master.router.BalancedWorkloadRoutingStrategy;
import ro.fortsoft.hztask.master.router.RoundRobinRoutingStrategy;
import ro.fortsoft.hztask.master.router.RoutingStrategy;
import ro.fortsoft.hztask.master.service.ClusterDistributionService;
import ro.fortsoft.hztask.master.service.CommunicationService;
import ro.fortsoft.hztask.master.service.TaskCompletionHandlerProvider;
import ro.fortsoft.hztask.master.statistics.codahale.CodahaleStatisticsService;
import ro.fortsoft.hztask.master.statistics.IStatisticsService;
import ro.fortsoft.hztask.master.statistics.TaskTransition;
import ro.fortsoft.hztask.master.statistics.TaskTransitionLogKeeper;
import ro.fortsoft.hztask.master.topology.HazelcastTopologyService;

import java.util.Date;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * @author Serban Balamaci
 */
public class ClusterMaster {

    private static final Logger log = LoggerFactory.getLogger(ClusterMaster.class);

    private final HazelcastInstance hzInstance;

    private final ClusterDistributionService clusterDistributionService;
    private HazelcastTopologyService hazelcastTopologyService;

    private final TaskTransitionLogKeeper taskTransitionLogKeeper = new TaskTransitionLogKeeper();

    private AsyncEventBus eventBus = new AsyncEventBus(Executors.newCachedThreadPool());

    private ClusterMasterService clusterMasterService;


    public ClusterMaster(MasterConfig masterConfig, Config hazelcastConfig) {
        hzInstance = Hazelcast.newHazelcastInstance(hazelcastConfig);

        hzInstance.getUserContext().put(HzKeysConstants.USER_CONTEXT_MEMBER_TYPE, MemberType.MASTER);

        CommunicationService communicationService = new CommunicationService(hzInstance);
        hazelcastTopologyService = new HazelcastTopologyService(hzInstance, eventBus, communicationService);

        clusterDistributionService = initClusterDistributionService(masterConfig);

        checkNoOtherMasterClusterAmongMembers();

        registerMembershipListener();

        registerAlreadyPresentAgents();

        unassignAnyPreviousTasks();

        TaskCompletionHandlerProvider taskCompletionHandlerProvider = new TaskCompletionHandlerProvider(masterConfig);
        clusterMasterService = new ClusterMasterService(clusterDistributionService,
                communicationService, taskCompletionHandlerProvider);

        hzInstance.getUserContext().put(HzKeysConstants.USER_CONTEXT_CLUSTER_MASTER_SERVICE,
                clusterMasterService);
//        clusterMasterService.startUnassignedTasksReschedulerThread();
    }

    private ClusterDistributionService initClusterDistributionService(MasterConfig masterConfig) {
        ClusterDistributionService clusterDistributionService = new ClusterDistributionService(hazelcastTopologyService,
                new CodahaleStatisticsService());

        clusterDistributionService.setRoutingStrategy(getRoutingStrategy(masterConfig,
                hazelcastTopologyService, clusterDistributionService.getStatisticsService()));
        clusterDistributionService.setTaskTransitionLogKeeper(taskTransitionLogKeeper);

        return clusterDistributionService;
    }

    /**
     * Offer a task for distribution to Agents
     * @param task task
     */
    public void submitTask(Task task) {
        clusterDistributionService.enqueueTask(task);
    }

    /**
     * Gets the routing strategy RoundRobin, Balanced, based on the config from MasterConfig
     *
     * @param hzTopologyService hzTopologyService
     * @param statisticsService statisticsService
     * @return a RoutingStrategy to be used
     */
    private RoutingStrategy getRoutingStrategy(MasterConfig config,
                   HazelcastTopologyService hzTopologyService, IStatisticsService statisticsService) {
        switch (config.getRoutingStrategy()) {
            case ROUND_ROBIN: return new RoundRobinRoutingStrategy(hzTopologyService);
            default:
                return new BalancedWorkloadRoutingStrategy(hzTopologyService, statisticsService);
        }
    }

    private void registerMembershipListener() {
        AgentMembershipSubscriber agentMembershipSubscriber =
                new AgentMembershipSubscriber(clusterDistributionService, hazelcastTopologyService);

        eventBus.register(agentMembershipSubscriber);

        hzInstance.getCluster().addMembershipListener(new ClusterMembershipListener(eventBus));
    }


    private void checkNoOtherMasterClusterAmongMembers() {
        if(hazelcastTopologyService.isMasterAmongClusterMembers()) {
            hzInstance.shutdown();
            throw new RuntimeException("Another Master is already started");
        }
    }

    public void shutdown() {
        clusterMasterService.shutdown();
    }

    public int getActiveWorkersCount() {
        return hazelcastTopologyService.getAgentsCount();
    }

    //Master might be started after some Agents are already running
    private void registerAlreadyPresentAgents() {
        Set<Member> memberSet = hzInstance.getCluster().getMembers();
        for(Member member : memberSet) {
            if(! member.localMember()) {
                hazelcastTopologyService.startJoinProtocolFor(member);
            }
        }
    }

    /**
     * Get Map of tasks uids and their list of transitions for Tasks that have started
     * and are still not finished . Ideal for investigating long running tasks
     *
     * @param startedSecsAgo how many seconds ago the running tasks have started
     * @return Map of tasks uids and their list of transitions for Tasks that have
     */
    public Multimap<String, TaskTransition> getUnfinishedTasksActivityLog(int startedSecsAgo) {
        ImmutableListMultimap<String, TaskTransition> logData = taskTransitionLogKeeper.getDataCopy();
        Date now = new Date();
        long agoMillis = now.getTime() - startedSecsAgo * 1000;

        ArrayListMultimap<String, TaskTransition> filteredData = ArrayListMultimap.create();

        for(String taskId : logData.keySet()) {
            ImmutableList<TaskTransition> entries = logData.get(taskId);
            if(entries.get(0).getEventDate().getTime() < agoMillis) {
                filteredData.putAll(taskId, entries);
            }
        }

        return filteredData;
    }

    private void unassignAnyPreviousTasks() {
        //TODO with HZ3.3 change it to highest value retrieved by Aggregate
        long latestTaskCounter = System.currentTimeMillis();
        clusterDistributionService.setLatestTaskCounter(latestTaskCounter);
        clusterDistributionService.unassignOlderTasks(latestTaskCounter);
    }

    /**
     * Method will trigger an output of debug information for Master and Agents
     */
    public void outputDebugStatisticsForMasterAndAgents() {
        hazelcastTopologyService.sendOutputDebugStatsToMember();
    }

}
