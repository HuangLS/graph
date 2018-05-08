package org.act.temporalProperty.impl;

import com.google.common.collect.PeekingIterator;
import org.act.temporalProperty.TemporalPropertyStore;
import org.act.temporalProperty.exception.TPSNHException;
import org.act.temporalProperty.helper.EPAppendIterator;
import org.act.temporalProperty.index.IndexStore;
import org.act.temporalProperty.index.IndexUpdater;
import org.act.temporalProperty.meta.PropertyMetaData;
import org.act.temporalProperty.query.TimeIntervalKey;
import org.act.temporalProperty.table.TwoLevelMergeIterator;
import org.act.temporalProperty.table.MergeProcess.MergeTask;
import org.act.temporalProperty.table.Table;
import org.act.temporalProperty.table.TableBuilder;
import org.act.temporalProperty.table.TableComparator;
import org.act.temporalProperty.util.FileUtils;
import org.act.temporalProperty.util.Slice;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

/**
 * Created by song on 2018-03-14.
 */
public class SinglePropertyStore
{
    private final IndexStore index;
    private PropertyMetaData propertyMeta;
    private File proDir;
    private Logger log = LoggerFactory.getLogger( TemporalPropertyStoreImpl.class );
    private TableCache cache;

    /**
     * 实例化方法
     * @param dbDir 存储动态属性数据的目录地址
     */
    public SinglePropertyStore(PropertyMetaData propertyMeta, File dbDir, TableCache cache, IndexStore indexStore ) throws Throwable{
        this.propertyMeta = propertyMeta;
        this.index = indexStore;
        this.proDir = new File(dbDir, propertyMeta.getPropertyId().toString());
        if(!proDir.exists() && !proDir.mkdir()) throw new IOException("create property dir failed: "+proDir.getAbsolutePath());
        this.cache = cache;
        this.loadBuffers();
    }

    private void loadBuffers() throws IOException {
        for(FileBuffer buffer : propertyMeta.getUnstableBuffers().values()){
            File bufferFile = new File(this.proDir, Filename.unbufferFileName(buffer.getNumber()));
            if (bufferFile.exists()) {
                buffer.init(bufferFile);
            }else{
                throw new IOException("buffer file not found: "+bufferFile.getAbsolutePath());
            }
        }
        for(FileBuffer buffer : propertyMeta.getStableBuffers().values()){
            File bufferFile = new File(this.proDir, Filename.stbufferFileName(buffer.getNumber()));
            if (bufferFile.exists()) {
                buffer.init(bufferFile);
            }else{
                throw new IOException("buffer file not found: "+bufferFile.getAbsolutePath());
            }
        }
    }

    /**
     * 进行时间点查询，参考{@link TemporalPropertyStore}中的说明
     */
    public Slice getPointValue(Slice idSlice, int time )
    {
        InternalKey searchKey = new InternalKey(idSlice, time);
        boolean hasStable = propertyMeta.hasStable();
        if(propertyMeta.hasUnstable() && ((hasStable && time>propertyMeta.stMaxTime()) || (!hasStable && time>=0))){
            Slice result = this.unPointValue( searchKey );
            if( null == result || result.length() == 0 ) {
                return null;
            }else {
                return result;
            }
        }else if(hasStable && time <= propertyMeta.stMaxTime()){
            FileMetaData meta = propertyMeta.getStContainsTime(time);
            return this.stPointValue(meta, searchKey);
        }else{
            return null;
        }
    }

    EPAppendIterator getRangeValueIter(Slice idSlice, int startTime, int endTime)
    {
        List<FileMetaData> stList = propertyMeta.overlappedStable(startTime, endTime);
        List<FileMetaData> unList = propertyMeta.unFloorTime(endTime);
        stList.sort(Comparator.comparingInt(FileMetaData::getSmallest));
        unList.sort(Comparator.comparingInt(FileMetaData::getSmallest));

        EPAppendIterator iterator = new EPAppendIterator(idSlice);
        for(FileMetaData meta : stList){
            SearchableIterator fileIterator = this.cache.newIterator(Filename.stPath(proDir, meta.getNumber()));
            FileBuffer buffer = propertyMeta.getStableBuffers( meta.getNumber() );
            if( null != buffer ){
                iterator.append(TwoLevelMergeIterator.merge(buffer.iterator(), fileIterator));
            }else {
                iterator.append(fileIterator);
            }
        }
        for( FileMetaData meta : unList ){
            SearchableIterator fileIterator = this.cache.newIterator(Filename.unPath(proDir, meta.getNumber()));
            FileBuffer buffer = propertyMeta.getUnstableBuffers( meta.getNumber() );
            if( null != buffer ){
                iterator.append(TwoLevelMergeIterator.merge(buffer.iterator(), fileIterator));
            }else {
                iterator.append(fileIterator);
            }
        }
        return iterator;
    }

    private Slice unPointValue(InternalKey searchKey) {
        List<FileMetaData> checkList = propertyMeta.unFloorTime(searchKey.getStartTime());
        checkList.sort(Comparator.comparingInt(FileMetaData::getSmallest));
        for (FileMetaData meta : checkList) {
            SearchableIterator iterator = this.cache.newIterator(Filename.unPath(proDir, meta.getNumber()));
            FileBuffer buffer = propertyMeta.getUnstableBuffers(meta.getNumber());
            if (null != buffer) {
                iterator = TwoLevelMergeIterator.merge(buffer.iterator(), iterator);
            }
            iterator.seek(searchKey);
            Entry<InternalKey, Slice> entry;
            try {
                entry = iterator.next();
            } catch (NoSuchElementException e) {
                continue;
            }
            InternalKey resultKey = entry.getKey();
            if (    resultKey.getId().equals(searchKey.getId()) &&
                    resultKey.getValueType() == ValueType.VALUE &&
                    resultKey.getStartTime() <= searchKey.getStartTime()) {
                return entry.getValue();
            } // else continue
        }
        // search unstable complete but not found. now search latest stable file
        FileMetaData meta = propertyMeta.latestStableMeta();
        if(meta!=null) {
            return stPointValue(meta, searchKey);
        }else {
            return null;
        }
    }

    private Slice stPointValue(FileMetaData meta, InternalKey searchKey){
        SearchableIterator iterator = this.cache.newIterator(Filename.stPath(proDir, meta.getNumber()));
        FileBuffer buffer = propertyMeta.getStableBuffers(meta.getNumber());
        if (null != buffer) {
            iterator = TwoLevelMergeIterator.merge(buffer.iterator(), iterator);
        }
        iterator.seek(searchKey);
        Entry<InternalKey, Slice> entry;
        try {
            entry = iterator.next();
        } catch (NoSuchElementException e) {
            return null;
        }
        InternalKey resultKey = entry.getKey();
        if (    resultKey.getId().equals(searchKey.getId()) &&
                resultKey.getStartTime()<=searchKey.getStartTime() &&
                resultKey.getValueType() == ValueType.VALUE) {
            return entry.getValue();
        }else{
            return null;
        }
    }


    // this method runs in the background thread.
    // insert entry to file buffer, and pack remain entries to a MergeTask
    public MergeTask merge(MemTable memTable) throws IOException {
        PeekingIterator<Entry<TimeIntervalKey,Slice>> iterator = memTable.intervalEntryIterator();
        MemTable toMerge = new MemTable();
        boolean stExist = propertyMeta.hasStable();
        boolean unExist = propertyMeta.hasUnstable();
        while( iterator.hasNext() ){
            Entry<TimeIntervalKey,Slice> entry = iterator.next();
            TimeIntervalKey timeInterval = entry.getKey();
            Slice val = entry.getValue();
            if( !unExist && !stExist ){
                toMerge.addInterval(timeInterval, val);
            }else if( unExist && !stExist){
                int unMaxTime = propertyMeta.unMaxTime();
                if(timeInterval.lessThan( unMaxTime + 1 ) ) {
                    insertUnstableBuffer(timeInterval, val);
                }else if(timeInterval.greaterOrEq( unMaxTime + 1 )){
                    toMerge.addInterval(entry.getKey(), val);
                }else{
                    insertUnstableBuffer( timeInterval.changeEnd( unMaxTime ), val );
                    toMerge.addInterval( timeInterval.changeStart( unMaxTime + 1 ), val );
                }
            }else if( unExist && stExist){
                int stMaxTime = propertyMeta.stMaxTime();
                int unMaxTime = propertyMeta.unMaxTime();
                if( timeInterval.span( stMaxTime, unMaxTime + 1 )){
                    insertStableBuffer( timeInterval.changeEnd( stMaxTime ), val );
                    insertUnstableBuffer( timeInterval.changeStart( stMaxTime + 1 ).changeEnd( unMaxTime ), val );
                    toMerge.addInterval( timeInterval.changeStart( stMaxTime + 1 ), val );
                }else if( timeInterval.lessThan( stMaxTime + 1 )){
                    insertStableBuffer(timeInterval, val );
                }else if(timeInterval.greaterOrEq( unMaxTime + 1 )){
                    toMerge.addInterval( timeInterval, val );
                }else if(timeInterval.span( stMaxTime + 1 )){
                    insertStableBuffer( timeInterval.changeEnd( stMaxTime ), val );
                    insertUnstableBuffer( timeInterval.changeStart( stMaxTime + 1 ), val );
                }else if(timeInterval.span( unMaxTime + 1 )){
                    insertUnstableBuffer( timeInterval.changeEnd( unMaxTime ), val );
                    toMerge.addInterval( timeInterval.changeStart( unMaxTime + 1 ), val );
                }else if(timeInterval.between( stMaxTime + 1, unMaxTime )){
                    insertUnstableBuffer( timeInterval, val );
                }else{
                    throw new TPSNHException( "no such scenery!" );
                }
            }else{ // !unExist && stExist
                int stMaxTime = propertyMeta.stMaxTime();
                if( timeInterval.lessThan( stMaxTime + 1 )){
                    insertStableBuffer(timeInterval, val);
                }else if(timeInterval.greaterOrEq( stMaxTime + 1 )){
                    toMerge.addInterval(timeInterval, val);
                }else{
                    insertStableBuffer( timeInterval.changeEnd( stMaxTime ), val );
                    toMerge.addInterval( timeInterval.changeStart( stMaxTime + 1 ), val );
                }
            }
        }
        if(!toMerge.isEmpty()){
            return new MergeTask( proDir, toMerge, propertyMeta, this.cache, index );
        }else{
            return null;
        }
    }


    /**
     * 对某个已存在的UnStableFile的插入，插入到相应的Buffer中。如果不存在则新建一个Buffer
     * @param key
     * @param value
     */
    private void insertUnstableBuffer( TimeIntervalKey key, Slice value ) throws IOException
    {
        FileMetaData meta = propertyMeta.unFloorTimeOneMeta( Math.toIntExact( key.getStart() ) );
        assert meta!=null : "SNH: meta should not null!";

        FileBuffer buffer = propertyMeta.getUnstableBuffers( meta.getNumber() );
        if( null == buffer ) {
            String fileName = Filename.unbufferFileName(meta.getNumber());
            buffer = new FileBuffer(new File(this.proDir, fileName), meta.getNumber());
            propertyMeta.addUnstableBuffer(meta.getNumber(), buffer);
        }
        buffer.add( key, value );
        if(buffer.size()>1024*1024*10) {
            unBufferToFile( meta, buffer );
        }
    }

    private void insertStableBuffer( TimeIntervalKey key, Slice value ) throws IOException {
        FileMetaData meta = propertyMeta.stFloorTimeOneMeta( Math.toIntExact( key.getStart() ) );
        assert meta!=null : "SNH: meta should not null!";

        FileBuffer buffer = propertyMeta.getStableBuffers( meta.getNumber() );
        if( null == buffer ) {
            String fileName = Filename.stbufferFileName(meta.getNumber());
            buffer = new FileBuffer(new File(this.proDir, fileName), meta.getNumber());
            propertyMeta.addStableBuffer(meta.getNumber(), buffer);
        }
        buffer.add( key, value );
        if(buffer.size()>1024*1024*10) {
            stBufferToFile( meta, buffer );
        }
    }


    private void unBufferToFile(FileMetaData meta, FileBuffer buffer) throws IOException {
        IndexUpdater indexUpdater = index.onBufferDelUpdate( propertyMeta.getPropertyId(), false, meta, buffer.getMemTable());

        String filePath = Filename.unPath(proDir, meta.getNumber());
        String bufferPath = Filename.unbufferFileName(meta.getNumber());
        File tempFile = buffer2file( filePath, bufferPath, buffer, indexUpdater );
        propertyMeta.delUnstableBuffer(meta.getNumber());
        if(!tempFile.renameTo(new File(filePath))) throw new IOException("rename failed!");
    }

    private void stBufferToFile(FileMetaData meta, FileBuffer buffer) throws IOException {
        IndexUpdater indexUpdater = index.onBufferDelUpdate( propertyMeta.getPropertyId(), true, meta, buffer.getMemTable());

        String filePath = Filename.stPath(proDir, meta.getNumber());
        String bufferFileName = Filename.stbufferFileName(meta.getNumber());
        File tempFile = buffer2file(filePath, bufferFileName, buffer, indexUpdater);
        propertyMeta.delStableBuffer(meta.getNumber());
        if(!tempFile.renameTo(new File(filePath))) throw new IOException("rename failed!");
    }

    private File buffer2file( String filePath, String bufferFileName, FileBuffer buffer, IndexUpdater indexUpdater ) throws IOException {
        File tempFile = new File(this.proDir, Filename.tempFileName(6));
        Files.deleteIfExists(tempFile.toPath());
        Files.createFile(tempFile.toPath());

        FileOutputStream stream = new FileOutputStream(tempFile);
        FileChannel channel = stream.getChannel();
        TableBuilder builder = new TableBuilder(new Options(), channel, TableComparator.instance());
        Table table = this.cache.getTable(filePath);
        SearchableIterator iterator = TwoLevelMergeIterator.merge(buffer.iterator(), table.iterator());
        while (iterator.hasNext()) {
            InternalEntry entry = iterator.next();
            builder.add(entry.getKey().encode(), entry.getValue());
            indexUpdater.update( entry );
        }
        builder.finish();
        channel.close();
        stream.close();
        table.close();
        this.cache.evict(filePath);
        indexUpdater.updateMeta();
        indexUpdater.cleanUp();
        File originFile = new File(filePath);
        Files.delete(originFile.toPath());
        buffer.close();
        Files.delete(new File(this.proDir, bufferFileName).toPath());
        return tempFile;
    }

    // boolean stand for: isStable.
    List<Triple<Boolean, FileMetaData, SearchableIterator>> buildIndexIterator( int startTime, int endTime ) {
        List<FileMetaData> stList = propertyMeta.overlappedStable(startTime, endTime);
        List<FileMetaData> unList = propertyMeta.unFloorTime(endTime);
        stList.sort(Comparator.comparingInt(FileMetaData::getSmallest));
        unList.sort(Comparator.comparingInt(FileMetaData::getSmallest));

        List<Triple<Boolean, FileMetaData, SearchableIterator>> results = new ArrayList<>();
        for(FileMetaData meta : stList){
            SearchableIterator fileIterator = this.cache.newIterator(Filename.stPath(proDir, meta.getNumber()));
            results.add(Triple.of( true, meta, fileIterator));
        }
        for( FileMetaData meta : unList ){
            SearchableIterator fileIterator = this.cache.newIterator(Filename.unPath(proDir, meta.getNumber()));
            results.add(Triple.of( false, meta, fileIterator));
        }
        return results;
    }

    public void destroy() throws IOException {
        for(FileMetaData f : propertyMeta.getUnStableFiles().values()) {
            String path = Filename.unPath(proDir, f.getNumber());
            cache.evict(path);
        }
        for(FileMetaData f : propertyMeta.getStableFiles().values()) {
            String path = Filename.stPath(proDir, f.getNumber());
            cache.evict(path);
        }
        for(FileBuffer buffer : propertyMeta.getUnstableBuffers().values()){
            buffer.close();
        }
        for(FileBuffer buffer : propertyMeta.getStableBuffers().values()){
            buffer.close();
        }
        FileUtils.deleteRecursively(proDir);
    }


//    @Override
//    public boolean delete( Slice id )
//    {
//        // TODO Auto-generated method stub
//        return false;
//    }
//




}