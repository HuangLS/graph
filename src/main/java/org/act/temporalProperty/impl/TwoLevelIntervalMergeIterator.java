package org.act.temporalProperty.impl;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.PeekingIterator;
import org.act.temporalProperty.exception.TPSNHException;
import org.act.temporalProperty.impl.MemTable.TimeIntervalValueEntry;
import org.act.temporalProperty.query.TimeIntervalKey;
import org.act.temporalProperty.util.Slice;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Map.Entry;

public class TwoLevelIntervalMergeIterator extends AbstractIterator<Entry<TimeIntervalKey,Slice>> implements IntervalIterator
{
    private final PeekingIterator<Entry<TimeIntervalKey,Slice>> old;
    private final PeekingIterator<Entry<TimeIntervalKey,Slice>> latest;
    private Pair<TimeIntervalKey,TimeIntervalKey> changedNextOldKey;

    public TwoLevelIntervalMergeIterator( PeekingIterator<Entry<TimeIntervalKey,Slice>> low, PeekingIterator<Entry<TimeIntervalKey,Slice>> high )
    {
        this.old = low;
        this.latest = high;
    }

    @Override
    protected Entry<TimeIntervalKey,Slice> computeNext()
    {
        if ( old.hasNext() && latest.hasNext() )
        {
            Entry<TimeIntervalKey,Slice> oldEntry = old.peek();
            Entry<TimeIntervalKey,Slice> newEntry = latest.peek();
            TimeIntervalKey oldKey = getNextOldKey( oldEntry );
            TimeIntervalKey newKey = newEntry.getKey();
            InternalKey oldInternalKey = oldKey.getKey();
            InternalKey newInternalKey = newKey.getKey();

            int r = oldInternalKey.compareTo( newInternalKey );
            if ( oldInternalKey.getId().equals( newInternalKey.getId() ) )
            {
                if ( r < 0 )
                {
                    if ( oldKey.to() < newKey.from() )
                    {
                        old.next();
                        return mayChangedOldEntry( oldKey, oldEntry.getValue() );
                    }
                    else
                    {
                        old.next();
                        return changeEndOldEntry( oldKey, oldEntry.getValue(), newKey.from() - 1 );
                    }
                }
                else
                {
                    changedNextOldKey = null;
                    if ( oldKey.from() > newKey.from() )
                    {
                        return latest.next();
                    }
                    else
                    {
                        pollOldUntil( newInternalKey.getId(), newKey.to() );
                        return latest.next();
                    }
                }
            }
            else
            {
                if ( r < 0 )
                {
                    old.next();
                    return mayChangedOldEntry( oldKey, oldEntry.getValue() );
                }
                else if ( r > 0 )
                {
                    changedNextOldKey = null;
                    return latest.next();
                }
                else
                {
                    throw new TPSNHException( "internalKey equal but id not equal!" );
                }
            }
        }
        else if ( old.hasNext() )
        {
            return old.next();
        }
        else if ( latest.hasNext() )
        {
            return latest.next();
        }
        else
        {
            return endOfData();
        }
    }

    private Entry<TimeIntervalKey,Slice> changeEndOldEntry( TimeIntervalKey oldKey, Slice value, long newEnd )
    {
        return mayChangedOldEntry( oldKey.changeEnd( newEnd ), value );
    }

    private Entry<TimeIntervalKey,Slice> mayChangedOldEntry( TimeIntervalKey oldKey, Slice value )
    {
        TimeIntervalValueEntry tmp = new TimeIntervalValueEntry( oldKey, value );
        changedNextOldKey = null;
        return tmp;
    }

    private TimeIntervalKey getNextOldKey( Entry<TimeIntervalKey,Slice> oldEntry )
    {
        if ( changedNextOldKey == null )
        {
            return oldEntry.getKey();
        }
        else
        {
            if ( oldEntry.getKey() == changedNextOldKey.getLeft() ) // yes! need pointer equal here.
            {
                return changedNextOldKey.getRight();
            }
            else
            {
                return oldEntry.getKey();
            }
        }
    }

    private void pollOldUntil( Slice id, long end )
    {
        while ( old.hasNext() )
        {
            Entry<TimeIntervalKey,Slice> oldEntry = old.peek();
            TimeIntervalKey oldKey = oldEntry.getKey();
            InternalKey oldInternalKey = oldKey.getKey();
            if ( !oldInternalKey.getId().equals( id ) )
            {
                changedNextOldKey = null;
                return;
            }
            if ( oldKey.to() <= end )
            {
                old.next();
            }
            else
            {
                break;
            }
        }
        if ( old.hasNext() ) // id must be equal.
        {
            Entry<TimeIntervalKey,Slice> oldEntry = old.peek();
            TimeIntervalKey oldKey = oldEntry.getKey();
            if ( oldKey.from() <= end )
            {
                changedNextOldKey = Pair.of( oldKey, oldKey.changeStart( end + 1 ) );
            }
        }
    }
}
