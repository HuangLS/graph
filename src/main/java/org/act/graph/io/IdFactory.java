package org.act.graph.io;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.EmptyStackException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Stack;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.act.graph.io.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * ����Id��ֵ����ָ��Ӧ�����ļ�������block��offset
 * 
 *Id�����ļ������ļ��еĹ������£�
 * **********************
 * *-8bytes-higtest id--*
 * *--------------------*
 * *-4bytes-empty id num*
 * *--------------------*
 * *-8bytes-empty id----*
 * *         .          *
 * *         .          *
 * *--------------------*
 *
 * ��ߵ�Id��0��ʼ���䣬����һ�μ�1
 *
 * @author huanghx( huanghx@act.buaa.edu.cn )
 * 
 * 
 */
//TODO ������close��ʱ�����Ϣ�洢���ļ��У���������г�����ͻᶪʧ��Ϣ����
public class IdFactory implements Closeable
{
    
    private Logger logger = LoggerFactory.getLogger( IdFactory.class );
    
    private File file;
    private String factoryname;
    private FileChannel filechannel;
    
    private static final int HEADER_LEN = 12;
    private AtomicLong highestid = new AtomicLong();
    private Stack<Long> reusedIdstack = new Stack<Long>();
    
    private boolean available = false;
    
    public IdFactory( String dictionary , String factoryname ) throws IOException
    {
        this.factoryname = factoryname + ".id";
        this.file = new File( dictionary + Util.SEPERATOR + this.factoryname );
        init();
        initreadfromfile();
        this.available = true;
    }

    
    public void returnId( long id )
    {
        //TODO ������
        if(!available)
            return;
        this.reusedIdstack.push( id );
    }
    
    /**
     * @return -1�����ʼ����û����ɣ������������١�
     */
    public long allocateId()
    {
        if( !available )
            return -1;
        long toret;
        try
        {
            toret = this.reusedIdstack.pop();
        }
        catch( EmptyStackException e )
        {
            toret = this.highestid.getAndIncrement();
        }
        return toret;
    }
    
    
    /**
     * 
     * ֻ����ϵͳ�ر�ʱ������ҪIdFactory��ʱ���ٵ��á�
     * �����close֮���ٵ��������ķ�����������쳣��
     * 
     * @throws IOException
     */
    public void close() throws IOException
    {
        this.available = false;
        try
        {
            flushToFile();
            this.filechannel.force( true );
            this.filechannel.close();
        }
        catch ( IOException e )
        {
            logger.error( this.factoryname + "Id�ļ�close����" );
            throw e;
        }
        this.reusedIdstack = null;
        this.highestid = null;
        this.file = null;
        this.filechannel = null;
    }
    
    private void flushToFile() throws IOException
    {
        //д���id
        this.filechannel.position( 0 );
        ByteBuffer buffer = ByteBuffer.allocate( 8 );
        buffer.putLong( this.highestid.get() );
        buffer.flip();
        while( buffer.hasRemaining() ) this.filechannel.write( buffer );
        
        //д����id����
        int num = this.reusedIdstack.size();
        buffer = ByteBuffer.allocate( 4 );
        buffer.putInt( num );
        buffer.flip();
        while( buffer.hasRemaining() ) this.filechannel.write( buffer );
        
        //д���õ�id
        buffer = ByteBuffer.allocate( 8 );
        List<Long> list = new LinkedList<Long>(this.reusedIdstack);
        for( long l : list )
        {
            buffer.position(0);
            buffer.putLong( l );
            buffer.flip();
            while( buffer.hasRemaining() ) this.filechannel.write( buffer );
        }
        
    }


    private void initreadfromfile() throws IOException
    {
        try
        {
            ByteBuffer buffer = ByteBuffer.allocate( 8 );
            //����ߵ�ֵ
            this.filechannel.position( 0 );
            int read =0;
            while( ( read += this.filechannel.read( buffer ) ) < 8 ){}
            buffer.flip();
            this.highestid.set( buffer.getLong() );
            
            //�����õĸ���
            buffer = ByteBuffer.allocate( 4 );
            read =0;
            while( ( read += this.filechannel.read( buffer ) ) < 4 ){}
            buffer.flip();
            int reuesdIdNum = buffer.getInt();
            
            //�����õ�ֵ
            buffer = ByteBuffer.allocate( 8 );
            while( (reuesdIdNum--) > 0 )
            {
                buffer.flip();
                read = 0;
                while( ( read += this.filechannel.read( buffer ) ) < 8 ){}
                buffer.flip();
                long reusedId = buffer.getLong();
                this.reusedIdstack.push( reusedId );
            }
        }
        catch ( IOException e )
        {
            logger.error( this.factoryname + "Id�ļ���ʼ��ʧ�ܣ�" );
            throw e;
        }
        
    }

    
    private void newfilewrite() throws IOException
    {
        //дHighId
        ByteBuffer buffer = ByteBuffer.allocate( 8 );
        buffer.putLong( 0 );
        buffer.flip();
        this.filechannel.position( 0 );
        while( buffer.hasRemaining() ) this.filechannel.write( buffer );
        //дReusedId����
        buffer = ByteBuffer.allocate( 4 );
        buffer.putInt( 0 );
        buffer.flip();
        while( buffer.hasRemaining() ) this.filechannel.write( buffer );
    }
    
    
    private void init() throws IOException
    {
        if( !this.file.exists() )
        {
            try
            {
                this.file.createNewFile();
                this.filechannel = new RandomAccessFile( this.file, "rw" ).getChannel();
                newfilewrite();
            }
            catch ( IOException e )
            {
                logger.error( this.factoryname + "Id�ļ�����ʧ��" );
                throw e;
            }
        }
        this.filechannel = new RandomAccessFile( this.file, "rw" ).getChannel();
    }
    
}
