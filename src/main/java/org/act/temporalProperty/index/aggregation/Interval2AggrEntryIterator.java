package org.act.temporalProperty.index.aggregation;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.PeekingIterator;
import org.act.temporalProperty.exception.TPSNHException;
import org.act.temporalProperty.index.EntityTimeIntervalEntry;
import org.act.temporalProperty.index.aggregation.AggregationIndexEntry;
import org.act.temporalProperty.query.aggr.AggregationIndexKey;
import org.act.temporalProperty.util.Slice;
import org.act.temporalProperty.util.TimeIntervalUtil;

import java.util.*;

/**
 * Created by song on 2018-04-06.
 */
public class Interval2AggrEntryIterator extends AbstractIterator<AggregationIndexEntry> implements PeekingIterator<AggregationIndexEntry> {
    private final Iterator<EntityTimeIntervalEntry> tpIter;
    private final TreeMap<Slice, Integer> valueGrouping;
    private final TreeMap<Integer, Integer> intervalStarts;
    private final int intervalBegin;
    private final int intervalFinish;

    private int eStart;
    private int eEnd;
    private EntityTimeIntervalEntry lastEntry;

    /**
     * @param iterator should only contains one property.
     * @param intervalStarts interval start time point TreeSet
     */
    public Interval2AggrEntryIterator(Iterator<EntityTimeIntervalEntry> iterator, TreeMap<Slice, Integer> valueGrouping, TreeMap<Integer, Integer> intervalStarts) {
        this.tpIter = iterator;
        this.valueGrouping = valueGrouping;
        this.intervalStarts = intervalStarts;
        if(intervalStarts.size()<2) throw new TPSNHException("time interval too less!");
        this.intervalBegin = intervalStarts.firstKey();
        this.intervalFinish = intervalStarts.lastKey()-1;
        if(intervalBegin>intervalFinish) throw new TPSNHException("time interval begin > finish!");
    }

    protected AggregationIndexEntry computeNext() {
        if(lastEntry!=null){
            return computeTimeGroup(this.eStart, this.eEnd, lastEntry);
        }else {
            while (tpIter.hasNext()) {
                EntityTimeIntervalEntry cur = tpIter.next();
                int eStart = cur.start();
                int eEnd = cur.end();
                if(eEnd<eStart) throw new TPSNHException("end({}) < start({})", eEnd, eStart);
                if(TimeIntervalUtil.overlap(eStart, eEnd, intervalBegin, intervalFinish)){
                    if(eStart<intervalBegin) eStart=intervalBegin;
                    if(eEnd>intervalFinish) eEnd=intervalFinish;
                    return computeTimeGroup(eStart, eEnd, cur);
                }//else: not in [intervalBegin, intervalFinish], do nothing, continue next loop;
            }
            return endOfData();
        }
    }

    private AggregationIndexEntry computeTimeGroup(int eStart, int eEnd, EntityTimeIntervalEntry entry) {
        int duration;
        int timeGroupId = intervalStarts.floorEntry(eStart).getValue();
        // if eStart and eEnd both in the same time range.
        if(Objects.equals(intervalStarts.floorKey(eStart), intervalStarts.floorKey(eEnd))){
            duration = eEnd - eStart + 1;
            lastEntry = null;
            return outputEntry(entry, timeGroupId, duration);
        }else{
            duration = intervalStarts.higherKey(eStart) - eStart; //no need +1.
            this.eStart = intervalStarts.higherKey(eStart);
            this.eEnd = eEnd;
            lastEntry = entry;
            return outputEntry(entry, timeGroupId, duration);
        }
    }

    private AggregationIndexEntry outputEntry(EntityTimeIntervalEntry entry, int timeGroupId, int duration) {
        Slice val = entry.value();
        Map.Entry<Slice, Integer> valGroup = valueGrouping.floorEntry(val);
        AggregationIndexKey key;
        if(valGroup!=null){
            key = new AggregationIndexKey(entry.entityId(), timeGroupId, valGroup.getValue());
        }else{
            key = new AggregationIndexKey(entry.entityId(), timeGroupId, -1);
        }
        return new AggregationIndexEntry(key, duration);
    }

}
