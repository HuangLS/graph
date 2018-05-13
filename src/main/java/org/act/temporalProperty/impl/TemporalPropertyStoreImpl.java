package org.act.temporalProperty.impl;

import com.google.common.base.Preconditions;
import com.google.common.collect.PeekingIterator;
import org.act.temporalProperty.TemporalPropertyStore;
import org.act.temporalProperty.exception.TPSException;
import org.act.temporalProperty.exception.TPSRuntimeException;
import org.act.temporalProperty.exception.ValueUnknownException;
import org.act.temporalProperty.helper.StoreInitial;
import org.act.temporalProperty.helper.EPEntryIterator;
import org.act.temporalProperty.helper.EPMergeIterator;
import org.act.temporalProperty.index.*;
import org.act.temporalProperty.index.value.IndexMetaData;
import org.act.temporalProperty.index.value.IndexQueryRegion;
import org.act.temporalProperty.index.value.rtree.IndexEntry;
import org.act.temporalProperty.meta.PropertyMetaData;
import org.act.temporalProperty.meta.SystemMeta;
import org.act.temporalProperty.meta.SystemMetaController;
import org.act.temporalProperty.meta.ValueContentType;
import org.act.temporalProperty.query.TemporalValue;
import org.act.temporalProperty.query.TimeIntervalKey;
import org.act.temporalProperty.query.aggr.AggregationIndexQueryResult;
import org.act.temporalProperty.query.aggr.ValueGroupingMap;
import org.act.temporalProperty.query.range.InternalEntryRangeQueryCallBack;
import org.act.temporalProperty.table.TwoLevelMergeIterator;
import org.act.temporalProperty.table.MergeProcess;
import org.act.temporalProperty.table.TableComparator;
import org.act.temporalProperty.util.Slice;
import org.apache.commons.lang3.tuple.Triple;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.*;

/**
 * TemporalPropertyStore的实现类
 */
public class TemporalPropertyStoreImpl implements TemporalPropertyStore
{
    private SystemMeta meta;
    private MergeProcess mergeProcess;
    private File dbDir;
    private TableCache cache;
    private MemTable memTable;
    private MemTable stableMemTable; // a full memtable, which only used for query and (to be) merged, never write.
    private IndexStore index;

    private boolean forbiddenWrite = false;
    private FileReader lockFile; // keeps opened while system is running to prevent delete of the storage dir;

    /**
     * @param dbDir 存储动态属性数据的目录地址
     */
    public TemporalPropertyStoreImpl( File dbDir ) throws Throwable
    {
        this.dbDir = dbDir;
        this.init();
        this.cache = new TableCache( 25, TableComparator.instance(), false );
        this.index = new IndexStore( new File( dbDir, "index" ), this, meta.getIndexes(), meta.indexNextId(), meta.indexNextFileId() );
        this.meta.initStore( dbDir, cache, index);
        this.mergeProcess = new MergeProcess( dbDir.getAbsolutePath(), meta, index );
        this.mergeProcess.start();
    }

    /**
     * 系统启动时调用，主要作用是将上次系统关闭时写入磁盘的数据读入内存
     */
    private void init() throws Throwable
    {
        StoreInitial starter = new StoreInitial( dbDir );
        lockFile = starter.init();
        this.meta = starter.getMetaInfo();
        this.memTable = starter.getMemTable();
    }

    /**
     * 退出系统时调用，主要作用是将内存中的数据写入磁盘。
     */
    public void shutDown() throws IOException, InterruptedException
    {
        this.meta.lock.shutdown();
        this.mergeProcess.shutdown();
        this.cache.close();
        this.meta.lock.shutdownLockExclusive();// no need to unlock, for state would lose when closed.
        this.flushMemTable2Disk();
        this.closeAllBuffer();
        this.flushMetaInfo2Disk();
        this.lockFile.close();
        Files.delete( new File( dbDir, Filename.lockFileName() ).toPath() );
    }

    @Override
    public Slice getPointValue( long entityId, int proId, int time )
    {
        Slice idSlice = toSlice( proId, entityId );
        InternalKey searchKey = new InternalKey( idSlice, time );
        this.meta.lock.lockShared();
        try
        {
            try
            {
                return memTable.get( searchKey );
            }
            catch ( ValueUnknownException e )
            {
                if ( this.stableMemTable != null )
                {
                    try
                    {
                        return stableMemTable.get( searchKey );
                    }
                    catch ( ValueUnknownException e1 )
                    {
                        return meta.getStore( proId ).getPointValue( idSlice, time );
                    }
                }
                else
                {
                    return meta.getStore( proId ).getPointValue( idSlice, time );
                }
            }
        }
        finally
        {
            this.meta.lock.unlockShared();
        }
    }

    @Override
    public Object getRangeValue( long id, int proId, int startTime, int endTime, InternalEntryRangeQueryCallBack callback )
    {
        return getRangeValue( id, proId, startTime, endTime, callback, null );
    }

    public Object getRangeValue( long entityId, int proId, int start, int end, InternalEntryRangeQueryCallBack callback, MemTable cache )
    {
        Preconditions.checkArgument( start <= end );
        Preconditions.checkArgument( entityId >= 0 && proId >= 0 );
        Preconditions.checkArgument( callback != null );
        meta.lock.lockShared();
        try
        {
            PropertyMetaData pMeta = meta.getProperties().get( proId );
            callback.setValueType( pMeta.getType() );

            Slice idSlice = toSlice( proId, entityId );
            SearchableIterator memIter = new EPEntryIterator( idSlice, memTable.iterator() );
            if ( this.stableMemTable != null )
            {
                memIter = new EPMergeIterator( idSlice, stableMemTable.iterator(), memIter );
            }
            SearchableIterator diskIter = meta.getStore( proId ).getRangeValueIter( idSlice, start, end );
            SearchableIterator mergedIterator = new EPMergeIterator( idSlice, diskIter, memIter );

            if(cache!=null)
            {
                mergedIterator = new EPMergeIterator( idSlice, mergedIterator, cache.iterator() );
            }

            mergedIterator = new UnknownToInvalidIterator( mergedIterator );

            InternalKey searchKey = new InternalKey( idSlice, start );
            mergedIterator.seek( searchKey );
            boolean firstLoop = true;
            while ( mergedIterator.hasNext() )
            {
                InternalEntry entry = mergedIterator.next();
                InternalKey key = entry.getKey();
                int time = key.getStartTime();
                if ( firstLoop )
                {
                    firstLoop = false;
                    if ( time < start )
                    { callback.onNewEntry( new InternalEntry( new InternalKey( key.getId(), start, key.getValueType() ), entry.getValue() ) ); }
                    else
                    {
                        callback.onNewEntry( entry );
                    }
                }
                else
                {
                    assert time > start;
                    if ( time <= end )
                    {
                        callback.onNewEntry( entry );
                    }
                    else
                    {
                        break;
                    }
                }
            }
            return callback.onReturn();
        }
        finally
        {
            meta.lock.unlockShared();
        }
    }

    public ValueContentType getPropertyValueType( int propertyId )
    {
        PropertyMetaData pMeta = meta.getProperties().get( propertyId );
        if ( pMeta != null )
        {
            return pMeta.getType();
        }
        else
        {
            return null;
        }
    }

    @Override
    public boolean createProperty( int propertyId, ValueContentType type )
    {
        meta.lock.lockExclusive();
        try
        {
            SinglePropertyStore prop = meta.proStores().get( propertyId );
            if ( prop == null )
            {
                try
                {
                    PropertyMetaData pMeta = new PropertyMetaData( propertyId, type );
                    meta.addStore( propertyId, new SinglePropertyStore( pMeta, dbDir, cache, index ) );
                    meta.addProperty( pMeta );
                    return true;
                }
                catch ( Throwable ignore )
                {
                    return false;
                }
            }
            else
            {
                PropertyMetaData pMeta = meta.getProperties().get( propertyId );
                if ( pMeta != null && pMeta.getType() == type )
                {
                    // already exist, maybe in recovery. so just delete all property then create again.
                    deleteProperty( propertyId );
                    createProperty( propertyId, type );
                    return true;
                }
                else
                {
                    throw new TPSRuntimeException( "create temporal property failed, exist property with same id but diff type!" );
                }
            }
        }
        finally
        {
            meta.lock.unlockExclusive();
        }
    }

    @Override
    public boolean setProperty( TimeIntervalKey key, Slice value )
    {
        if ( !meta.getProperties().containsKey( key.getKey().getPropertyId() ) )
        {
            createProperty( key.getKey().getPropertyId(), key.getKey().getValueType().toValueContentType() );
//            throw new TPSNHException( "no such property id: " + key.getKey().getPropertyId() + ". should create first!" );
        }

        meta.lock.lockExclusive();
        try
        {
            if ( forbiddenWrite )
            {
                meta.lock.waitSubmitMemTable();
            }

            this.memTable.addInterval( key, value );
            if ( this.memTable.approximateMemUsage() >= 4 * 1024 * 1024 )
            {
                forbiddenWrite = true;
                this.mergeProcess.add( this.memTable ); // may await at current line.
                this.stableMemTable = this.memTable;
                this.memTable = new MemTable();
                forbiddenWrite = false;
            }
            meta.lock.memTableSubmitted();
        }
        catch ( InterruptedException e )
        {
            e.printStackTrace();
            return false;
        }
        finally
        {
            meta.lock.unlockExclusive();
        }
        return true;
    }

    @Override
    public boolean deleteProperty( int propertyId )
    {
        meta.lock.lockExclusive();
        try
        {
            meta.getProperties().remove( propertyId );
            meta.getStore( propertyId ).destroy();
            Set<IndexMetaData> indexSet = meta.getIndexes();
            for ( IndexMetaData iMeta : indexSet )
            {
                Set<Integer> pids = new HashSet<>( iMeta.getPropertyIdList() );
                if ( pids.contains( propertyId ) )
                {
                    indexSet.remove( iMeta );
                }
            }
            index.deleteIndex( propertyId );
            return true;
        }
        catch ( IOException e )
        {
            e.printStackTrace();
            return false;
        }
        finally
        {
            meta.lock.unlockExclusive();
        }
    }

    @Override
    public boolean deleteEntityProperty( Slice id )
    {
        //TODO deleteEntityProperty
        return false;
    }

    @Override
    public long createAggrDurationIndex( int propertyId, int start, int end, ValueGroupingMap valueGrouping, int every, int timeUnit )
    {
        meta.lock.lockExclusive();
        try
        {
            PropertyMetaData pMeta = meta.getProperties().get( propertyId );
            long indexId = index.createAggrDurationIndex( pMeta, start, end, valueGrouping, every, timeUnit );
            mergeProcess.createNewIndex();
            return indexId;
        }
        catch ( IOException e )
        {
            e.printStackTrace();
            throw new TPSRuntimeException( "error when create index.", e );
        }
        finally
        {
            meta.lock.unlockExclusive();
        }
    }

    @Override
    public long createAggrMinMaxIndex( int propertyId, int start, int end, int every, int timeUnit, IndexType type )
    {
        meta.lock.lockExclusive();
        try
        {
            PropertyMetaData pMeta = meta.getProperties().get( propertyId );
            long indexId = index.createAggrMinMaxIndex( pMeta, start, end, every, timeUnit, type );
            mergeProcess.createNewIndex();
            return indexId;
        }
        catch ( IOException e )
        {
            e.printStackTrace();
            throw new TPSRuntimeException( "error when create index.", e );
        }
        finally
        {
            meta.lock.unlockExclusive();
        }
    }

    @Override
    public Object aggregate( long entityId, int proId, int startTime, int endTime, InternalEntryRangeQueryCallBack callback )
    {
        return getRangeValue( entityId, proId, startTime, endTime, callback );
    }

    @Override
    public AggregationIndexQueryResult getByIndex( long indexId, long entityId, int proId, int startTime, int endTime )
    {
        return getByIndex( indexId, entityId, proId, startTime, endTime, null );
    }

    @Override
    public AggregationIndexQueryResult getByIndex( long indexId, long entityId, int proId, int startTime, int endTime, MemTable cache )
    {
        meta.lock.lockShared();
        try
        {
            PropertyMetaData pMeta = meta.getProperties().get( proId );
            return index.queryAggrIndex( entityId, pMeta, startTime, endTime, indexId, cache);
        }
        catch ( IOException e )
        {
            e.printStackTrace();
            throw new TPSRuntimeException( "error when aggr with index.", e );
        }
        finally
        {
            meta.lock.unlockShared();
        }
    }

    @Override
    public long createValueIndex( int start, int end, List<Integer> proIds )
    {
        meta.lock.lockExclusive();
        try
        {
            List<IndexValueType> types = new ArrayList<>();
            for ( Integer pid : proIds )
            {
                PropertyMetaData pMeta = meta.getProperties().get( pid );
                checkNotNull( pMeta, "storage not contains property id " + pid );
                types.add( IndexValueType.convertFrom( pMeta.getType() ) );
            }
            return createValueIndex( start, end, proIds, types );
        }
        finally
        {
            meta.lock.unlockExclusive();
        }
    }

    private long createValueIndex( int start, int end, List<Integer> proIds, List<IndexValueType> types )
    {
        checkArgument( !proIds.isEmpty(), "should have at least one proId" );
        meta.lock.lockExclusive();
        try
        {
            long indexId = index.createValueIndex( start, end, proIds, types );
            mergeProcess.createNewIndex();
            return indexId;
        }
        catch ( IOException e )
        {
            e.printStackTrace();
            return -1;
        }
        finally
        {
            meta.lock.unlockExclusive();
        }
    }

    @Override
    public List<Long> getEntities( IndexQueryRegion condition )
    {
        return getEntities( condition, new MemTable() );
    }

    @Override
    public List<Long> getEntities( IndexQueryRegion condition, MemTable cache )
    {
        List<IndexEntry> result = getEntries( condition, cache );
        Set<Long> set = new HashSet<>();
        for ( IndexEntry entry : result )
        {
            set.add( entry.getEntityId() );
        }
        return new ArrayList<>( set );
    }

    @Override
    public List<IndexEntry> getEntries( IndexQueryRegion condition )
    {
        return getEntries( condition, new MemTable() );
    }

    @Override
    public List<IndexEntry> getEntries( IndexQueryRegion condition, MemTable cache )
    {
        meta.lock.lockShared();
        try
        {
            return index.queryValueIndex( condition, cache );
        }
        catch ( IOException e )
        {
            e.printStackTrace();
            throw new RuntimeException( e );
        }
        finally
        {
            meta.lock.unlockShared();
        }
    }

    @Override
    public List<IndexMetaData> listIndex()
    {
        return index.list();
    }

    private SearchableIterator getMemTableIter( int start, int end )
    {
        if ( this.stableMemTable != null )
        {
            return TwoLevelMergeIterator.merge( memTable.iterator(), stableMemTable.iterator() );
        }
        else
        {
            return memTable.iterator();
        }
    }

    public List<Triple<Boolean, FileMetaData, SearchableIterator>> buildIndexIterator( int start, int end, List<Integer> proIds )
    {
        if ( proIds.size() == 1 )
        {
            return meta.getStore( proIds.get( 0 ) ).buildIndexIterator( start, end );
        }
        else
        {
            List<Triple<Boolean, FileMetaData, SearchableIterator>> merged = new ArrayList<>();
            for ( Integer pid : proIds )
            {
                merged.addAll( meta.getStore( pid ).buildIndexIterator( start, end ) );
            }
            return merged;
        }
    }

    private static Slice toSlice( int proId, long id )
    {
        Slice result = new Slice( 12 );
        result.setLong( 0, id );
        result.setInt( 8, proId );
        return result;
    }

    /**
     * 在系统关闭时，将MemTable中的数据写入磁盘
     *
     * @Author Sjh
     * this is called when we need to manually start a merge process which force all data in memory to unstable file
     * on disk. we than create a new empty MemTable.
     * note that this method blocks current thread until all operation done.
     */
    @Override
    public void flushMemTable2Disk()
    {
        try
        {
            buffer2disk();
            File tempFile = new File( this.dbDir + "/" + Filename.tempFileName( 0 ) );
            if ( !tempFile.exists() )
            {
                Files.createFile( tempFile.toPath() );
            }
            LogWriter writer = Logs.createMetaWriter( tempFile );
            PeekingIterator<Map.Entry<TimeIntervalKey,Slice>> iterator;
            if ( memTable.isEmpty() && stableMemTable != null )
            {
                iterator = this.stableMemTable.intervalEntryIterator();
            }
            else
            {
                iterator = this.memTable.intervalEntryIterator();
            }
            while ( iterator.hasNext() )
            {
                Map.Entry<TimeIntervalKey,Slice> entry = iterator.next();
                writer.addRecord(MemTable.encode(entry.getKey(), entry.getValue()), false);
            }
            writer.close();
        }
        catch ( IOException e )
        {
            e.printStackTrace();
            throw new TPSRuntimeException( "memTable flush failed", e );
        }
    }

    @Override
    public void flushMetaInfo2Disk()
    {
        try
        {
            SystemMetaController.forceToDisk( this.dbDir, this.meta );
        }
        catch ( IOException e )
        {
            e.printStackTrace();
            throw new TPSRuntimeException( "meta flush to disk failed", e );
        }
    }

    private void buffer2disk() throws IOException
    {
        for ( PropertyMetaData p : this.meta.getProperties().values() )
        {
            for ( FileBuffer buffer : p.getUnstableBuffers().values() )
            {
                buffer.force();
            }
            for ( FileBuffer buffer : p.getStableBuffers().values() )
            {
                buffer.force();
            }
        }
    }

    private void closeAllBuffer() throws IOException
    {
        for ( PropertyMetaData p : this.meta.getProperties().values() )
        {
            for ( FileBuffer buffer : p.getUnstableBuffers().values() )
            {
                buffer.close();
            }
            for ( FileBuffer buffer : p.getStableBuffers().values() )
            {
                buffer.close();
            }
        }
    }

    public boolean cacheOverlap( int proId, long entityId, int startTime, int endTime, MemTable cache )
    {
        Slice id = toSlice( proId, entityId );
        if ( cache!=null && cache.overlap( id, startTime, endTime ) )
        {
            return true;
        }
        if ( this.memTable.overlap( id, startTime, endTime ) )
        {
            return true;
        }
        if ( this.stableMemTable != null && this.stableMemTable.overlap( id, startTime, endTime ) )
        {
            return true;
        }

        PropertyMetaData p = this.meta.getProperties().get( proId );
        for ( FileBuffer buffer : p.overlappedBuffers( startTime, endTime ) )
        {
            if ( buffer.getMemTable().overlap( id, startTime, endTime ) )
            {
                return true;
            }
        }
        return false;
    }

    public boolean cacheOverlap( int proId, int startTime, int endTime, MemTable cache )
    {
        if ( cache.overlap( proId, startTime, endTime ) )
        {
            return true;
        }
        if ( this.memTable.overlap( proId, startTime, endTime ) )
        {
            return true;
        }
        if ( this.stableMemTable != null && this.stableMemTable.overlap( proId, startTime, endTime ) )
        {
            return true;
        }

        PropertyMetaData p = this.meta.getProperties().get( proId );
        for ( FileBuffer buffer : p.overlappedBuffers( startTime, endTime ) )
        {
            if ( buffer.getMemTable().overlap( proId, startTime, endTime ) )
            {
                return true;
            }
        }
        return false;
    }

    public TemporalValue<Boolean> coverTime( Set<Integer> proIdSet, int timeMin, int timeMax, MemTable cache )
    {
        TemporalValue<Boolean> tMap = new TemporalValue<>();
        for ( Integer proId : proIdSet )
        {
            PropertyMetaData p = this.meta.getProperties().get( proId );
            for ( FileBuffer buffer : p.overlappedBuffers( timeMin, timeMax ) )
            {
                buffer.getMemTable().coverTime( tMap, proIdSet, timeMin, timeMax );
            }
        }
        if ( this.stableMemTable != null )
        {
            stableMemTable.coverTime( tMap, proIdSet, timeMin, timeMax );
        }
        this.memTable.coverTime( tMap, proIdSet, timeMin, timeMax );
        cache.coverTime( tMap, proIdSet, timeMin, timeMax );
        return tMap;
    }
}
