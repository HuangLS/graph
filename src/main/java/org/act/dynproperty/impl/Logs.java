/*
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.act.dynproperty.impl;

import java.io.File;
import java.io.IOException;

import org.act.dynproperty.util.PureJavaCrc32C;
import org.act.dynproperty.util.Slice;

public final class Logs
{
    private Logs()
    {
    }

    private static LogWriter instence;
    
    public static synchronized LogWriter createLogWriter( String dbDir )
            throws IOException
    {
        if( null == instence )
        {
            String fileName = dbDir + "/" + Filename.logFileName( 0 );
            File file = new File(fileName);
            if (false) {
                instence =  new MMapLogWriter(file, 0);
            }
            else {
                instence = new FileChannelLogWriter(file, 0);
            }
        }
        return instence;
    }

    public static int getChunkChecksum(int chunkTypeId, Slice slice)
    {
        return getChunkChecksum(chunkTypeId, slice.getRawArray(), slice.getRawOffset(), slice.length());
    }

    public static int getChunkChecksum(int chunkTypeId, byte[] buffer, int offset, int length)
    {
        // Compute the crc of the record type and the payload.
        PureJavaCrc32C crc32C = new PureJavaCrc32C();
        crc32C.update(chunkTypeId);
        crc32C.update(buffer, offset, length);
        return crc32C.getMaskedValue();
    }
}