package com.fortsoft.hztask.master;

import com.fortsoft.hztask.op.AbstractClusterOp;
import com.fortsoft.hztask.op.agent.AnnounceMasterMemberOp;
import com.fortsoft.hztask.op.agent.AskAgentReadyOp;
import com.hazelcast.core.ExecutionCallback;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IExecutorService;
import com.hazelcast.core.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

/**
 * @author Serban Balamaci
 */
public class HazelcastTopologyService {

    private final IExecutorService communicationExecutorService;

    private CopyOnWriteArrayList<Member> agents;

    private HazelcastInstance hzInstance;

    private static final Logger log = LoggerFactory.getLogger(HazelcastTopologyService.class);

    public HazelcastTopologyService(HazelcastInstance hzInstance) {
        this.hzInstance = hzInstance;
        communicationExecutorService = hzInstance.getExecutorService("coms");
        agents = new CopyOnWriteArrayList<>();
    }

    public void callbackWhenAgentReady(Member member, int attempt) {
        try {
            communicationExecutorService.submitToMember(new AskAgentReadyOp(),
                    member, new MemberReadyCallback(member, this, attempt));
        } catch (Exception e) {
            log.error("Error sending AskAgentReadyOp", e);
        }
    }


    public Future sendMessageToMember(Member member, AbstractClusterOp op) {
        return communicationExecutorService.submitToMember(op, member);
    }

    public void removeAgent(Member member) {
        agents.remove(member);
    }

    public CopyOnWriteArrayList<Member> getAgents() {
        return agents;
    }

    public Member getMaster() {
        return hzInstance.getCluster().getLocalMember();
    }

    private class MemberReadyCallback implements ExecutionCallback<Boolean> {

        private Member member;
        private int attempt;
        private HazelcastTopologyService hazelcastTopologyService;

        private MemberReadyCallback(Member member, HazelcastTopologyService hazelcastTopologyService, int attempt) {
            this.member = member;
            this.attempt = attempt;
            this.hazelcastTopologyService = hazelcastTopologyService;
        }

        @Override
        public void onResponse(Boolean response) {
            if(response) {
                log.info("New cluster agent {} is active", member.getUuid());
                sendMessageToMember(member, new AnnounceMasterMemberOp(hazelcastTopologyService.getMaster()));
                hazelcastTopologyService.getAgents().add(member);
            } else {
                TimerTask timerTask = new TimerTask() {
                    @Override
                    public void run() {
                        hazelcastTopologyService.callbackWhenAgentReady(member, attempt ++);
                    }
                };
                new Timer().schedule(timerTask, 5000);
            }
        }

        @Override
        public void onFailure(Throwable t) {
            t.printStackTrace();
        }
    }

}
