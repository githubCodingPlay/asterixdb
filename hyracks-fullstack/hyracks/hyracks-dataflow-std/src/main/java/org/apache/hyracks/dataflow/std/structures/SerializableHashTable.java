/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hyracks.dataflow.std.structures;

import java.util.ArrayList;
import java.util.List;

import org.apache.hyracks.api.context.IHyracksFrameMgrContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;

/**
 * An entry in the table is: #elements, #no-empty elements; fIndex, tIndex;
 * fIndex, tIndex; .... <fIndex, tIndex> forms a tuple pointer
 */
public class SerializableHashTable implements ISerializableTable {

    private static final int INT_SIZE = 4;
    private static final int INIT_ENTRY_SIZE = 4;

    private IntSerDeBuffer[] headers;
    private List<IntSerDeBuffer> contents = new ArrayList<>();
    private List<Integer> frameCurrentIndex = new ArrayList<>();
    private final IHyracksFrameMgrContext ctx;
    private final int frameCapacity;
    private int currentLargestFrameIndex = 0;
    private int tupleCount = 0;
    private int headerFrameCount = 0;
    private TuplePointer tempTuplePointer = new TuplePointer();

    public SerializableHashTable(int tableSize, final IHyracksFrameMgrContext ctx) throws HyracksDataException {
        this.ctx = ctx;
        int frameSize = ctx.getInitialFrameSize();

        int residual = tableSize * INT_SIZE * 2 % frameSize == 0 ? 0 : 1;
        int headerSize = tableSize * INT_SIZE * 2 / frameSize + residual;
        headers = new IntSerDeBuffer[headerSize];

        IntSerDeBuffer frame = new IntSerDeBuffer(ctx.allocateFrame().array());
        contents.add(frame);
        frameCurrentIndex.add(0);
        frameCapacity = frame.capacity();
    }

    @Override
    public void insert(int entry, TuplePointer pointer) throws HyracksDataException {
        int hFrameIndex = getHeaderFrameIndex(entry);
        int headerOffset = getHeaderFrameOffset(entry);
        IntSerDeBuffer header = headers[hFrameIndex];
        if (header == null) {
            header = new IntSerDeBuffer(ctx.allocateFrame().array());
            headers[hFrameIndex] = header;
            resetFrame(header);
            headerFrameCount++;
        }
        int frameIndex = header.getInt(headerOffset);
        int offsetIndex = header.getInt(headerOffset + 1);
        if (frameIndex < 0) {
            // insert first tuple into the entry
            insertNewEntry(header, headerOffset, INIT_ENTRY_SIZE, pointer);
        } else {
            // insert non-first tuple into the entry
            insertNonFirstTuple(header, headerOffset, frameIndex, offsetIndex, pointer);
        }
        tupleCount++;
    }

    @Override
    public void delete(int entry) {
        int hFrameIndex = getHeaderFrameIndex(entry);
        int headerOffset = getHeaderFrameOffset(entry);
        IntSerDeBuffer header = headers[hFrameIndex];
        if (header != null) {
            int frameIndex = header.getInt(headerOffset);
            int offsetIndex = header.getInt(headerOffset + 1);
            if (frameIndex >= 0) {
                IntSerDeBuffer frame = contents.get(frameIndex);
                int entryUsedItems = frame.getInt(offsetIndex + 1);
                frame.writeInt(offsetIndex + 1, 0);
                tupleCount -= entryUsedItems;
            }
        }
    }

    @Override
    public boolean getTuplePointer(int entry, int offset, TuplePointer dataPointer) {
        int hFrameIndex = getHeaderFrameIndex(entry);
        int headerOffset = getHeaderFrameOffset(entry);
        IntSerDeBuffer header = headers[hFrameIndex];
        if (header == null) {
            dataPointer.reset(-1, -1);
            return false;
        }
        int frameIndex = header.getInt(headerOffset);
        int offsetIndex = header.getInt(headerOffset + 1);
        if (frameIndex < 0) {
            dataPointer.reset(-1, -1);
            return false;
        }
        IntSerDeBuffer frame = contents.get(frameIndex);
        int entryUsedItems = frame.getInt(offsetIndex + 1);
        if (offset > entryUsedItems - 1) {
            dataPointer.reset(-1, -1);
            return false;
        }
        int startIndex = offsetIndex + 2 + offset * 2;
        while (startIndex >= frameCapacity) {
            ++frameIndex;
            startIndex -= frameCapacity;
        }
        frame = contents.get(frameIndex);
        dataPointer.reset(frame.getInt(startIndex), frame.getInt(startIndex + 1));
        return true;
    }

    @Override
    public void reset() {
        for (IntSerDeBuffer frame : headers)
            if (frame != null)
                resetFrame(frame);

        frameCurrentIndex.clear();
        for (int i = 0; i < contents.size(); i++) {
            frameCurrentIndex.add(0);
        }

        currentLargestFrameIndex = 0;
        tupleCount = 0;
    }

    @Override
    public int getFrameCount() {
        return headerFrameCount + contents.size();
    }

    @Override
    public int getTupleCount() {
        return tupleCount;
    }

    @Override
    public int getTupleCount(int entry) {
        int hFrameIndex = getHeaderFrameIndex(entry);
        int headerOffset = getHeaderFrameOffset(entry);
        IntSerDeBuffer header = headers[hFrameIndex];
        if (header != null) {
            int frameIndex = header.getInt(headerOffset);
            int offsetIndex = header.getInt(headerOffset + 1);
            if (frameIndex >= 0) {
                IntSerDeBuffer frame = contents.get(frameIndex);
                int entryUsedItems = frame.getInt(offsetIndex + 1);
                return entryUsedItems;
            }
        }
        return 0;
    }

    @Override
    public void close() {
        int nFrames = contents.size();
        for (int i = 0; i < headers.length; i++)
            headers[i] = null;
        contents.clear();
        frameCurrentIndex.clear();
        tupleCount = 0;
        currentLargestFrameIndex = 0;
        ctx.deallocateFrames(nFrames);
    }

    private void insertNewEntry(IntSerDeBuffer header, int headerOffset, int entryCapacity, TuplePointer pointer)
            throws HyracksDataException {
        IntSerDeBuffer lastFrame = contents.get(currentLargestFrameIndex);
        int lastIndex = frameCurrentIndex.get(currentLargestFrameIndex);
        int requiredIntCapacity = entryCapacity * 2;
        int startFrameIndex = currentLargestFrameIndex;

        if (lastIndex + requiredIntCapacity >= frameCapacity) {
            IntSerDeBuffer newFrame;
            startFrameIndex++;
            do {
                if (currentLargestFrameIndex >= contents.size() - 1) {
                    newFrame = new IntSerDeBuffer(ctx.allocateFrame().array());
                    currentLargestFrameIndex++;
                    contents.add(newFrame);
                    frameCurrentIndex.add(0);
                } else {
                    currentLargestFrameIndex++;
                    frameCurrentIndex.set(currentLargestFrameIndex, 0);
                }
                requiredIntCapacity -= frameCapacity;
            } while (requiredIntCapacity > 0);
            lastIndex = 0;
            lastFrame = contents.get(startFrameIndex);
        }

        // set header
        header.writeInt(headerOffset, startFrameIndex);
        header.writeInt(headerOffset + 1, lastIndex);

        // set the entry
        lastFrame.writeInt(lastIndex, entryCapacity - 1);
        lastFrame.writeInt(lastIndex + 1, 1);
        lastFrame.writeInt(lastIndex + 2, pointer.getFrameIndex());
        lastFrame.writeInt(lastIndex + 3, pointer.getTupleIndex());
        int newLastIndex = lastIndex + entryCapacity * 2;
        newLastIndex = newLastIndex < frameCapacity ? newLastIndex : frameCapacity - 1;
        frameCurrentIndex.set(startFrameIndex, newLastIndex);

        requiredIntCapacity = entryCapacity * 2 - (frameCapacity - lastIndex);
        while (requiredIntCapacity > 0) {
            startFrameIndex++;
            requiredIntCapacity -= frameCapacity;
            newLastIndex = requiredIntCapacity < 0 ? requiredIntCapacity + frameCapacity : frameCapacity - 1;
            frameCurrentIndex.set(startFrameIndex, newLastIndex);
        }
    }

    private void insertNonFirstTuple(IntSerDeBuffer header, int headerOffset, int frameIndexArg, int offsetIndex,
            TuplePointer pointer) throws HyracksDataException {
        int frameIndex = frameIndexArg;
        IntSerDeBuffer frame = contents.get(frameIndex);
        int entryItems = frame.getInt(offsetIndex);
        int entryUsedItems = frame.getInt(offsetIndex + 1);

        if (entryUsedItems < entryItems) {
            frame.writeInt(offsetIndex + 1, entryUsedItems + 1);
            int startIndex = offsetIndex + 2 + entryUsedItems * 2;
            while (startIndex >= frameCapacity) {
                ++frameIndex;
                startIndex -= frameCapacity;
            }
            frame = contents.get(frameIndex);
            frame.writeInt(startIndex, pointer.getFrameIndex());
            frame.writeInt(startIndex + 1, pointer.getTupleIndex());
        } else {
            int capacity = (entryItems + 1) * 2;
            header.writeInt(headerOffset, -1);
            header.writeInt(headerOffset + 1, -1);
            int fIndex = frame.getInt(offsetIndex + 2);
            int tIndex = frame.getInt(offsetIndex + 3);
            tempTuplePointer.reset(fIndex, tIndex);
            this.insertNewEntry(header, headerOffset, capacity, tempTuplePointer);
            int newFrameIndex = header.getInt(headerOffset);
            int newTupleIndex = header.getInt(headerOffset + 1);

            for (int i = 1; i < entryUsedItems; i++) {
                int startIndex = offsetIndex + 2 + i * 2;
                int startFrameIndex = frameIndex;
                while (startIndex >= frameCapacity) {
                    ++startFrameIndex;
                    startIndex -= frameCapacity;
                }
                frame = contents.get(startFrameIndex);
                fIndex = frame.getInt(startIndex);
                tIndex = frame.getInt(startIndex + 1);
                tempTuplePointer.reset(fIndex, tIndex);
                insertNonFirstTuple(header, headerOffset, newFrameIndex, newTupleIndex, tempTuplePointer);
            }
            insertNonFirstTuple(header, headerOffset, newFrameIndex, newTupleIndex, pointer);
        }
    }

    private void resetFrame(IntSerDeBuffer frame) {
        for (int i = 0; i < frameCapacity; i++)
            frame.writeInt(i, -1);
    }

    private int getHeaderFrameIndex(int entry) {
        int frameIndex = entry * 2 / frameCapacity;
        return frameIndex;
    }

    private int getHeaderFrameOffset(int entry) {
        int offset = entry * 2 % frameCapacity;
        return offset;
    }

    private static class IntSerDeBuffer {

        private byte[] bytes;

        public IntSerDeBuffer(byte[] data) {
            this.bytes = data;
        }

        public int getInt(int pos) {
            int offset = pos * 4;
            return ((bytes[offset] & 0xff) << 24) + ((bytes[offset + 1] & 0xff) << 16)
                    + ((bytes[offset + 2] & 0xff) << 8) + ((bytes[offset + 3] & 0xff) << 0);
        }

        public void writeInt(int pos, int value) {
            int offset = pos * 4;
            bytes[offset++] = (byte) (value >> 24);
            bytes[offset++] = (byte) (value >> 16);
            bytes[offset++] = (byte) (value >> 8);
            bytes[offset++] = (byte) (value);
        }

        public int capacity() {
            return bytes.length / 4;
        }
    }
}
