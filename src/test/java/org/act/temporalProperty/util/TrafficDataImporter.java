package org.act.temporalProperty.util;

import org.act.temporalProperty.TemporalPropertyStore;
import org.act.temporalProperty.meta.ValueContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import static org.act.temporalProperty.util.StoreBuilder.setIntProperty;

/**
 * Created by song on 2018-01-23.
 */
public class TrafficDataImporter {
    private static Logger log = LoggerFactory.getLogger(StoreBuilder.class);

    private final File dataPath;
    private final Map<String, Long> roadIdMap = new HashMap<>();
    private int minTime;
    private int maxTime;
    private TemporalPropertyStore store;

    public TrafficDataImporter(TemporalPropertyStore store, String dataPath, int inputFileCount) throws IOException {
        this.store = store;
        this.dataPath = new File(dataPath);
        store.createProperty(1, ValueContentType.INT);
//        store.createProperty(2, ValueContentType.INT);
//        store.createProperty(3, ValueContentType.INT);
//        store.createProperty(4, ValueContentType.INT);
        this.inputData(inputFileCount);
    }

    public Map<String, Long> getRoadIdMap(){
        return roadIdMap;
    }

    public int getMinTime(){
        return minTime;
    }

    public int getMaxTime(){
        return maxTime;
    }

    private void inputData(int inputFileCount) throws IOException {
        List<File> dataFileList = new ArrayList<>();
        getFileRecursive(dataPath, dataFileList, 5);
        dataFileList.sort((Comparator.comparing(File::getName)));
        for (int i = 0; i < dataFileList.size() && i<inputFileCount; i++) {
            File file = dataFileList.get(i);
            int time = timeStr2int(file.getName().substring(9, 21)) - 1288800000;
            if(minTime>time) minTime = time;
            if(maxTime<time) maxTime = time;
            try(BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                for (int lineCount = 0; (line = br.readLine()) != null; lineCount++) {
                    if (lineCount == 0) continue;
                    input(time, line);
                }
            }
            if(i%10==0) log.info("input {} files, current {}, time {}", i, file.getName(), time);
        }
        log.info("input files done, {} roads", this.roadIdMap.size());
    }

    private static void getFileRecursive(File dir, List<File> fileList, int level){
        if(dir.isDirectory()){
            for (File file : dir.listFiles()) {
                if(!file.isDirectory() && file.getName().startsWith("TJamData_201") && file.getName().endsWith(".csv")){
                    fileList.add(file);
                }
            }
            if(level>0) {
                for (File file : dir.listFiles()) {
                    if (file.isDirectory()) {
                        getFileRecursive(file, fileList, level - 1);
                    }
                }
            }
        }
    }

    private void input(int time, String line) {
        String[] fields = line.split(",");
//        int index = Integer.valueOf(fields[0]);
        String gridId = fields[1];
        String chainId = fields[2];
//        int ignore = Integer.valueOf(fields[3]);
//        type = Integer.valueOf(fields[4]);
//        length = Integer.valueOf(fields[5]);
        int travelTime = Integer.valueOf(fields[6]);
        int fullStatus = Integer.valueOf(fields[7]);
        int vehicleCount = Integer.valueOf(fields[8]);
        int segmentCount = Integer.valueOf(fields[9]);

        long roadId = getId(gridId, chainId);
//        log.debug("eid({}), time({}), travelTime({})", roadId, time, travelTime);
        setIntProperty(store, time, roadId, 1, travelTime);
//        setIntProperty(store, time, roadId, 2, fullStatus);
//        setIntProperty(store, time, roadId, 3, vehicleCount);
//        setIntProperty(store, time, roadId, 4, segmentCount);
    }

    private long getId(String gridId, String chainId) {
        String strKey = gridId+":"+chainId;
        if(roadIdMap.containsKey(strKey)){
            return roadIdMap.get(strKey);
        }else{
            long nextId = roadIdMap.size();
            roadIdMap.put(strKey, nextId);
            return nextId;
        }
    }

    private static int timeStr2int(String tStr){
        String yearStr = tStr.substring(0,4);
        String monthStr = tStr.substring(4,6);
        String dayStr = tStr.substring(6,8);
        String hourStr = tStr.substring(8,10);
        String minuteStr = tStr.substring(10, 12);
//        System.out.println(yearStr+" "+monthStr+" "+dayStr+" "+hourStr+" "+minuteStr);
        int year = Integer.parseInt(yearStr);
        int month = Integer.parseInt(monthStr)-1;//month count from 0 to 11, no 12
        int day = Integer.parseInt(dayStr);
        int hour = Integer.parseInt(hourStr);
        int minute = Integer.parseInt(minuteStr);
//        System.out.println(year+" "+month+" "+day+" "+hour+" "+minute);
        Calendar ca= Calendar.getInstance();
        ca.set(year, month, day, hour, minute, 0); //seconds set to 0
        long timestamp = ca.getTimeInMillis();
//        System.out.println(timestamp);
        if(timestamp/1000<Integer.MAX_VALUE){
            return (int) (timestamp/1000);
        }else {
            throw new RuntimeException("timestamp larger than Integer.MAX_VALUE, this should not happen");
        }
    }
}
