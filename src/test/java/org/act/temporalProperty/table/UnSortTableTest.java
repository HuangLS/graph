package org.act.temporalProperty.table;

import java.io.File;
import java.util.Map.Entry;

import com.google.common.collect.PeekingIterator;
import junit.framework.Assert;

import org.act.temporalProperty.impl.InternalKey;
import org.act.temporalProperty.impl.MemTable;
import org.act.temporalProperty.impl.ValueType;
import org.act.temporalProperty.query.TimeIntervalKey;
import org.act.temporalProperty.util.Slice;
import org.junit.BeforeClass;
import org.junit.Test;

public class UnSortTableTest
{
    private static final String dbDir = "./target/TableUpdateTest";
    private static String fileName = "temp.test";
    private static final int DATA_SIZE = 200000;
    private static UnSortedTable table;
    
    @BeforeClass
    public static void setUp()
    {
        try
        {
            File file = new File( dbDir + fileName );
            if( file.exists() )
                file.delete();
            file.createNewFile();
            table = new UnSortedTable( file );
            for( int i = 0; i<DATA_SIZE; i++ )
            {
                TimeIntervalKey key = new TimeIntervalKey( new InternalKey( i, i, i, ValueType.VALUE ), i + 3 );
                Slice value = new Slice(4);
                value.setInt( 0, i );
                table.add( key, value );
            }
        }
        catch( Throwable t ){}
    }
    
    @Test
    public void test()
    {
        MemTable memtable = new MemTable();
        try
        {
            table.initFromFile( memtable );
            PeekingIterator<Entry<TimeIntervalKey,Slice>> iterator = memtable.intervalEntryIterator();
            for( int i = 0; i<DATA_SIZE; i++ )
            {
                Entry<TimeIntervalKey,Slice> entry = iterator.next();
                Assert.assertEquals( entry.getKey().from(), (long) i );
                Assert.assertEquals( entry.getKey().to(), (long) i + 3 );
                Assert.assertEquals( entry.getKey().getKey().getPropertyId(), (long) i );
                Assert.assertEquals( entry.getKey().getKey().getEntityId(), (long) i );
                Assert.assertEquals( entry.getValue().getInt( 0 ), i );
            }
        }
        catch ( Exception e )
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}











