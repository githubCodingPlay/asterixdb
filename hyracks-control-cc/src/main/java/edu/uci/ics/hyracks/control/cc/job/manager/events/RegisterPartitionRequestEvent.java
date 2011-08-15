/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.hyracks.control.cc.job.manager.events;

import edu.uci.ics.hyracks.api.partitions.PartitionId;
import edu.uci.ics.hyracks.api.util.Pair;
import edu.uci.ics.hyracks.control.cc.ClusterControllerService;
import edu.uci.ics.hyracks.control.cc.job.JobRun;
import edu.uci.ics.hyracks.control.cc.jobqueue.AbstractEvent;
import edu.uci.ics.hyracks.control.cc.partitions.PartitionMatchMaker;
import edu.uci.ics.hyracks.control.cc.partitions.PartitionUtils;
import edu.uci.ics.hyracks.control.common.job.PartitionDescriptor;
import edu.uci.ics.hyracks.control.common.job.PartitionRequest;

public class RegisterPartitionRequestEvent extends AbstractEvent {
    private final ClusterControllerService ccs;
    private final PartitionRequest partitionRequest;

    public RegisterPartitionRequestEvent(ClusterControllerService ccs, PartitionRequest partitionRequest) {
        this.ccs = ccs;
        this.partitionRequest = partitionRequest;
    }

    @Override
    public void run() {
        PartitionId pid = partitionRequest.getPartitionId();
        JobRun run = ccs.getRunMap().get(pid.getJobId());
        if (run == null) {
            return;
        }
        PartitionMatchMaker pmm = run.getPartitionMatchMaker();
        Pair<PartitionDescriptor, PartitionRequest> match = pmm.matchPartitionRequest(partitionRequest);
        if (match != null) {
            PartitionUtils.reportPartitionMatch(ccs, pid, match);
        }
    }

    @Override
    public String toString() {
        return "PartitionRequest@" + partitionRequest;
    }
}