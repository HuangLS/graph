package org.act.graph.io;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

/**
 *
 *����һ��DataBlock���ڳ�ʼ����ʱ������Block�пյ�record
 *��Block���κ��޸Ķ�ͨ������࣬Ȼ������data����������Ҫд���buffer��
 *���buffer�����˿յ�record������������block�Ĵ�С
 *
 * @author huanghx( huanghx@act.buaa.edu.cn )
 */
public class DataBlock
{
    
    private final static int BLOCK_RECORD_NUM = 50;
    private final static int BLOCK_HEAD_LEN = 4;
    public final static int BLOCK_SIZE = BLOCK_HEAD_LEN + DataRecord.DATA_RECORD_LEN * BLOCK_RECORD_NUM;
    
    private int recordNum;
    private List<DataRecord> records = new LinkedList<DataRecord>();
    
    private boolean inited = false;
    
    public DataBlock( ByteBuffer buffer )
    {
        buffer.flip();
        this.recordNum = buffer.getInt();
        for( int i = 0; i<recordNum; i++ )
        {
            long start = buffer.getLong();
            long end = buffer.getLong();
            byte[] data = new byte[DataRecord.DATA_LENGTH];
            buffer.get( data );
            DataRecord record = new DataRecord( start, end, data );
            records.add( record );
        }
        inited = true;
    }
    
    public DataBlock()
    {
        this.recordNum = 0;
        inited = true;
    }
    
    /**
     * 
     * �Ƿ���������Ҫ�ڵ����������֮ǰ�жϣ�������������Ӧ����
     * 
     * @param record ����ĩβ��record
     * @return ����ɹ�����true�����ʧ�ܷ���false��ʧ�ܵ�ԭ���ǻ�û�г�ʼ����ȫ
     */
    public boolean append( DataRecord record )
    {
        if( !inited )
            return false;
        this.records.add( record );
        this.recordNum++;
        return true;
    }
    
    
    /**
     * @return ����ȫ��block��ByteBuffer����д���ļ�ǰ��flip()
     */
    public ByteBuffer data()
    {
        ByteBuffer toret = ByteBuffer.allocate( BLOCK_SIZE );
        toret.putInt( this.recordNum );
        assert this.records.size() == this.recordNum;
        for( DataRecord record : this.records )
        {
            toret.put( record.getbytes() );
        }
//        for( int i = 0; i<BLOCK_RECORD_NUM-this.recordNum; i++ )
//        {
//            toret.put( new byte[DataRecord.DATA_RECORD_LEN] );
//        }
        return toret;
    }
    
    public List<DataRecord> records()
    {
        return this.records;
    }
    
}






