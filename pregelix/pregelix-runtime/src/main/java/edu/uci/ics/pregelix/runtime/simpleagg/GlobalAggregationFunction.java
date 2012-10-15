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

package edu.uci.ics.pregelix.runtime.simpleagg;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Writable;

import edu.uci.ics.hyracks.api.comm.IFrameTupleAccessor;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.dataflow.common.comm.util.ByteBufferInputStream;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.FrameTupleReference;
import edu.uci.ics.hyracks.dataflow.common.data.accessors.IFrameTupleReference;
import edu.uci.ics.pregelix.api.graph.GlobalAggregator;
import edu.uci.ics.pregelix.api.graph.Vertex;
import edu.uci.ics.pregelix.api.util.BspUtils;
import edu.uci.ics.pregelix.dataflow.base.IConfigurationFactory;
import edu.uci.ics.pregelix.dataflow.std.base.IAggregateFunction;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class GlobalAggregationFunction implements IAggregateFunction {
    private final Configuration conf;
    private final DataOutput output;
    private ByteBufferInputStream valueInputStream = new ByteBufferInputStream();
    private DataInput valueInput = new DataInputStream(valueInputStream);
    private GlobalAggregator globalAggregator;
    private Vertex vertex;
    private Writable aggregateResult;

    public GlobalAggregationFunction(IConfigurationFactory confFactory, DataOutput output, boolean isFinalStage)
            throws HyracksDataException {
        this.conf = confFactory.createConfiguration();
        this.output = output;

        vertex = BspUtils.createVertex(conf);
        aggregateResult = BspUtils.createAggregateValue(conf);
        globalAggregator = BspUtils.createGlobalAggregator(conf);
    }

    @Override
    public void init() throws HyracksDataException {

    }

    @Override
    public void step(IFrameTupleReference tuple) throws HyracksDataException {
        FrameTupleReference ftr = (FrameTupleReference) tuple;
        IFrameTupleAccessor fta = ftr.getFrameTupleAccessor();
        ByteBuffer buffer = fta.getBuffer();
        int tIndex = ftr.getTupleIndex();

        int valueStart = fta.getFieldSlotsLength() + fta.getTupleStartOffset(tIndex)
                + fta.getFieldStartOffset(tIndex, 1);

        valueInputStream.setByteBuffer(buffer, valueStart);
        try {
            vertex.readFields(valueInput);
            globalAggregator.step(vertex);
        } catch (IOException e) {
            throw new HyracksDataException(e);
        }

    }

    @Override
    public void finish() throws HyracksDataException {
        try {
            aggregateResult = globalAggregator.finish();
            aggregateResult.write(output);
        } catch (IOException e) {
            throw new HyracksDataException(e);
        }
    }

}
