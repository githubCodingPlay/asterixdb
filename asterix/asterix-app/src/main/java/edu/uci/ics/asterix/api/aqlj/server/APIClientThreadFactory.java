/*
 * Copyright 2009-2011 by The Regents of the University of California
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
package edu.uci.ics.asterix.api.aqlj.server;

import java.io.IOException;
import java.net.Socket;

import edu.uci.ics.hyracks.api.application.ICCApplicationContext;
import edu.uci.ics.hyracks.api.client.HyracksConnection;
import edu.uci.ics.hyracks.api.client.IHyracksClientConnection;

/**
 * This class is a factory for client handler threads of type {@link APIClientThread} and is used in conjunction with {@link ThreadedServer}.
 * 
 * @author zheilbron
 */
public class APIClientThreadFactory implements IClientThreadFactory {
    private final ICCApplicationContext appContext;

    private IHyracksClientConnection hcc;

    public APIClientThreadFactory(ICCApplicationContext appContext) throws Exception {
        this.appContext = appContext;
        hcc = new HyracksConnection("localhost", appContext.getCCContext().getClusterControllerInfo()
                .getClientNetPort());
    }

    @Override
    public Thread createThread(Socket socket) throws IOException {
        return new APIClientThread(hcc, socket, appContext);
    }
}
