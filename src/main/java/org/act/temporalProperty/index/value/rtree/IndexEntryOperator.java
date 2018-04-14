package org.act.temporalProperty.index.value.rtree;

import com.google.common.base.Preconditions;
import org.act.temporalProperty.index.*;
import org.act.temporalProperty.index.value.IndexQueryRegion;
import org.act.temporalProperty.index.value.PropertyValueInterval;
import org.act.temporalProperty.util.Slice;

import java.util.List;

/**
 * Created by song on 2018-01-19.
 *
 * IndexEntry is a byte array (Slice), which contains following fields:
 * entity id : long, 8 bytes
 * start time: int, 4 bytes --- dimIndex 0
 * end time  : int, 4 bytes --- dimIndex 1
 * value1    : ?, ?   bytes --- dimIndex 2 --- if it is a string, then its length is contained in value1
 * ...
 */
public class IndexEntryOperator {

    private static final int defaultIndexFieldCount = 2; //start, end. not include entity id because it is only useful in IndexDataEntry
    private final List<IndexValueType> values;
    private final int dataBlockCapacity;
    private final int indexBlockCapacity;

    public IndexEntryOperator(List<IndexValueType> valueTypeList, int blockSize){
        this.values = valueTypeList;
        int size = estimateIndexEntrySize(valueTypeList);
        this.dataBlockCapacity = blockSize / (size+8);//8 for entity id;
        this.indexBlockCapacity = blockSize / (size+8);
    }

    private int estimateIndexEntrySize(List<IndexValueType> valueTypeList) {
        int size = 0;
        for(IndexValueType type : valueTypeList){
            switch (type){
                case INT: size+=4;break;
                case LONG:size+=8;break;
                case FLOAT:size+=4;break;
                case DOUBLE:size+=8;break;
                case STRING:size+=32;break;
            }
        }
        return size;
    }

    public int dataBlockCapacity() {
        return dataBlockCapacity;
    }

    public int indexBlockCapacity() {
        return indexBlockCapacity;
    }

    public int dimensionCount() {
        return values.size()+ defaultIndexFieldCount;
    }

    private int toValueIndex(int dimIndex){
        Preconditions.checkArgument(defaultIndexFieldCount <= dimIndex && dimIndex < dimensionCount());
        return dimIndex - defaultIndexFieldCount;
    }

    public int compare(IndexEntry entry1, IndexEntry entry2, int dimIndex) {
        switch (dimIndex) {
            case 0:
                return Integer.compare(entry1.getStart(), entry2.getStart());
            case 1:
                return Integer.compare(entry1.getEnd(), entry2.getEnd());
            default:
                int valIndex = toValueIndex(dimIndex);
                return values.get(valIndex).compare(
                        entry1.getValue(valIndex),
                        entry2.getValue(valIndex));
        }
    }

    public int compareRange(RTreeRange bound1, RTreeRange bound2, int dimIndex) {
        if(!bound1.overlap(bound2, dimIndex)){
            return compare(bound1.getMin(), bound2.getMin(), dimIndex);
        }else{
            if(dimIndex==0){
                int min1 = bound1.getMin().getStart();
                int max1 = bound1.getMax().getStart();
                int min2 = bound2.getMin().getStart();
                int max2 = bound2.getMax().getStart();
                return compareRange(min1, max1, min2, max2);
            }else if(dimIndex==1) {
                int min1 = bound1.getMin().getEnd();
                int max1 = bound1.getMax().getEnd();
                int min2 = bound2.getMin().getEnd();
                int max2 = bound2.getMax().getEnd();
                return compareRange(min1, max1, min2, max2);
            }else{
                int valIndex = toValueIndex(dimIndex);
                Slice min1 = bound1.getMin().getValue(valIndex);
                Slice max1 = bound1.getMax().getValue(valIndex);
                Slice min2 = bound2.getMin().getValue(valIndex);
                Slice max2 = bound2.getMax().getValue(valIndex);
                return values.get(valIndex).compareRange(min1, max1, min2, max2);
            }
        }
    }

    private int compareRange(int min1, int max1, int min2, int max2){
        long tmp1 = min1;
        tmp1 += max1;
        long tmp2 = min2;
        tmp2 += max2;
        return Long.compare(tmp1, tmp2);
    }

    public RTreeRange toRTreeRange(IndexQueryRegion regions) {
        List<PropertyValueInterval> vList =  regions.getPropertyValueIntervals();
        Slice[] minVal = new Slice[vList.size()];
        Slice[] maxVal = new Slice[vList.size()];
        for(int i=0; i<vList.size(); i++){
            PropertyValueInterval p = vList.get(i);
            IndexValueType type = p.getType();
            if(this.values.get(i)!= type) throw new RuntimeException("incorrect value order");
            minVal[i] = p.getValueMin();
            maxVal[i] = p.getValueMax();
        }

        IndexEntry min = new IndexEntry(0, regions.getTimeMin(), minVal);
        IndexEntry max = new IndexEntry(regions.getTimeMax(), Integer.MAX_VALUE, maxVal);

        return new RTreeRange(min, max, this);
    }

    public IndexEntry[] calcMinMax(List<IndexEntry> entries) {
        Slice[] minVal = new Slice[values.size()];
        Slice[] maxVal = new Slice[values.size()];
        for(int i=0;i<values.size();i++){
            Slice[] minmax = calcMinMaxValue(entries, i);
            minVal[i] = minmax[0];
            maxVal[i] = minmax[1];
        }
        int[] t = calcMinMaxTime(entries);
        IndexEntry min = new IndexEntry(t[0], t[1], minVal);
        IndexEntry max = new IndexEntry(t[2], t[3], maxVal);
        return new IndexEntry[]{min, max};
    }

    private Slice[] calcMinMaxValue(List<IndexEntry> entries, int valIndex) {
        IndexValueType type = values.get(valIndex);
        Slice min=null, max=null;
        for(int i=0; i<entries.size(); i++){
            IndexEntry entry = entries.get(i);
            if(i==0){
                min=max=entry.getValue(valIndex);
            }else {
                Slice field = entry.getValue(valIndex);
                if(type.compare(field, min)<0) min = field;
                if(type.compare(field, max)>0) max = field;
            }
        }
        return new Slice[]{min, max};
    }

    private int[] calcMinMaxTime(List<IndexEntry> entries) {
        int minStart = 0, maxStart = 0, minEnd = 0, maxEnd = 0;
        for(int i=0; i<entries.size(); i++){
            IndexEntry entry = entries.get(i);
            if(i==0){
                minStart=maxStart=entry.getStart();
                minEnd = maxEnd = entry.getEnd();
            }else {
                int start = entry.getStart();
                int end = entry.getEnd();
                if(start<minStart) minStart = start;
                if(start>maxStart) maxStart = start;
                if(end<minEnd) minEnd = end;
                if(end>maxEnd) maxEnd = end;
            }
        }
        return new int[]{minStart, minEnd, maxStart, maxEnd};
    }

    public String toString(IndexEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("IndexEntity(");
        Long eid = entry.getEntityId();
        if(eid!=null) sb.append("eid=").append(eid).append(",");
        for(int i=0; i<dimensionCount(); i++) {
            if(i==0){
                sb.append("start=").append(entry.getStart()).append(',');
            }else if(i==1){
                sb.append("end=").append(entry.getEnd()).append(',');
            }else{
                int valIndex = toValueIndex(i);
                sb.append("val").append(valIndex);
                sb.append('=').append(values.get(valIndex).toString(entry.getValue(valIndex)));
            }
        }
        sb.append(")");
        return sb.toString();
    }


}
