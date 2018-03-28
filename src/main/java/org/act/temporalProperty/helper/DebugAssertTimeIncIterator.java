package org.act.temporalProperty.helper;

import com.google.common.collect.AbstractIterator;
import org.act.temporalProperty.exception.TPSNHException;
import org.act.temporalProperty.impl.InternalKey;
import org.act.temporalProperty.impl.SeekingIterator;
import org.act.temporalProperty.table.TableComparator;
import org.act.temporalProperty.util.Slice;

import java.util.Comparator;
import java.util.Map.Entry;

/**
 * Created by song on 2018-03-28.
 */
public class DebugAssertTimeIncIterator extends AbstractIterator<Entry<Slice,Slice>> implements SeekingIterator<Slice,Slice> {

    private final SeekingIterator<Slice, Slice> in;
    private final Comparator<Slice> cp = TableComparator.instance();
    private Slice preKey = null;


    public DebugAssertTimeIncIterator(SeekingIterator<Slice,Slice> in){
        this.in = in;
    }

    @Override
    protected Entry<Slice, Slice> computeNext() {
        if(in.hasNext()){
            if(preKey == null){
                preKey = in.peek().getKey();
                return in.next();
            }else{
                Slice curT = in.peek().getKey();
                if(cp.compare(curT, preKey)<0){
                    throw new TPSNHException("time dec! "+ new InternalKey(preKey) +" "+ new InternalKey(curT));
                }else{
                    preKey = curT;
                    return in.next();
                }
            }
        }else{
            return endOfData();
        }
    }

    @Override
    public void seekToFirst() {
        in.seekToFirst();
    }

    @Override
    public void seek(Slice targetKey) {
        in.seek(targetKey);
    }
}
