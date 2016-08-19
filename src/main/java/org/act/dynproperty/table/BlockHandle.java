
package org.act.dynproperty.table;

import org.act.dynproperty.util.Slice;
import org.act.dynproperty.util.SliceInput;
import org.act.dynproperty.util.SliceOutput;
import org.act.dynproperty.util.Slices;
import org.act.dynproperty.util.VariableLengthQuantity;

/**
 * 代表某个Block在文件中的位置和大小
 *
 */
public class BlockHandle
{
    public static final int MAX_ENCODED_LENGTH = 10 + 10;

    private final long offset;
    private final int dataSize;

    public BlockHandle(long offset, int dataSize)
    {
        this.offset = offset;
        this.dataSize = dataSize;
    }

    public long getOffset()
    {
        return offset;
    }

    public int getDataSize()
    {
        return dataSize;
    }

    public int getFullBlockSize()
    {
        return dataSize + BlockTrailer.ENCODED_LENGTH;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        BlockHandle that = (BlockHandle) o;

        if (dataSize != that.dataSize) {
            return false;
        }
        if (offset != that.offset) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode()
    {
        int result = (int) (offset ^ (offset >>> 32));
        result = 31 * result + dataSize;
        return result;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("BlockHandle");
        sb.append("{offset=").append(offset);
        sb.append(", dataSize=").append(dataSize);
        sb.append('}');
        return sb.toString();
    }

    public static BlockHandle readBlockHandle(SliceInput sliceInput)
    {
        long offset = VariableLengthQuantity.readVariableLengthLong(sliceInput);
        long size = VariableLengthQuantity.readVariableLengthLong(sliceInput);

        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Blocks can not be larger than Integer.MAX_VALUE");
        }

        return new BlockHandle(offset, (int) size);
    }

    public static Slice writeBlockHandle(BlockHandle blockHandle)
    {
        Slice slice = Slices.allocate(MAX_ENCODED_LENGTH);
        SliceOutput sliceOutput = slice.output();
        writeBlockHandleTo(blockHandle, sliceOutput);
        return slice.slice();
    }

    public static void writeBlockHandleTo(BlockHandle blockHandle, SliceOutput sliceOutput)
    {
        VariableLengthQuantity.writeVariableLengthLong(blockHandle.offset, sliceOutput);
        VariableLengthQuantity.writeVariableLengthLong(blockHandle.dataSize, sliceOutput);
    }
}
