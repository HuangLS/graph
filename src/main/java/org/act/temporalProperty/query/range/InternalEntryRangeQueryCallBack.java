package org.act.temporalProperty.query.range;

import org.act.temporalProperty.impl.InternalEntry;

/**
 * 在时间段查询中使用的回调函数
 *
 */
public interface InternalEntryRangeQueryCallBack
{
    /**
     * This method is called before the range query to tell you the type of values
     * @param valueType
     */
    void setValueType(String valueType);
    void onNewEntry(InternalEntry entry);
    Object onReturn();
}
