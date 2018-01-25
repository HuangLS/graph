package org.act.temporalProperty.impl.query.range;

import org.act.temporalProperty.TemporalPropertyStore;
import org.act.temporalProperty.util.Slice;
import org.act.temporalProperty.util.StoreBuilder;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by song on 2018-01-24.
 */
public class FloorQueryTest {
    private static Logger log = LoggerFactory.getLogger(RangeQueryTest.class);
    private static String dbDir = "/tmp/temporal.property.test";
    private TemporalPropertyStore store;


    @Test
    public void test2fail() throws Throwable {
        StoreBuilder stBuilder = new StoreBuilder(dbDir, true);
        store = stBuilder.store();
        for(long entityId=1; entityId<5; entityId++){
            for(int time=0; time<Integer.MAX_VALUE/1000-6; time+=5){
                StoreBuilder.setIntProperty(store, time, entityId, 0, time);
            }
            log.debug("version {} write finish", entityId);
        }
        Slice val = store.getPointValue(1, 0, 4);
        log.debug("{}", val.getInt(0)==4);
    }

    @Test
    public void test2fail0() throws Throwable {
        StoreBuilder stBuilder = new StoreBuilder(dbDir, false);
        store = stBuilder.store();

//        for(int time=10; time<Integer.MAX_VALUE/100-6; time+=5){
//            for(long entityId=1; entityId<5; entityId++){
//                StoreBuilder.setIntProperty(store, time, entityId, 0, time);
//            }
//        }
        Slice val = store.getPointValue(1, 0, 9);
        if(val!=null) log.debug("{}", val.getInt(0));
        store.shutDown();
    }

    @Test
    public void test2fail2() throws Throwable {
        StoreBuilder stBuilder = new StoreBuilder(dbDir, true);
        store = stBuilder.store();
        for(long entityId=1; entityId<5; entityId++){
            for(int proId=0; proId<Integer.MAX_VALUE/1000-6; proId+=5){
                StoreBuilder.setIntProperty(store, 0, entityId, proId, 0);
            }
            for(int proId=0; proId<Integer.MAX_VALUE/1000-6; proId+=3){
                StoreBuilder.setIntProperty(store, 10, entityId, proId, 10);
            }
            log.debug("version {} write finish", entityId);
        }
        Slice val = store.getPointValue(1, 3, 4);
        log.debug("{}", val.getInt(0));
    }
}
