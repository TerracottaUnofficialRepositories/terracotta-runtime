/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.terracotta.toolkit.object.serialization;

import org.terracotta.toolkit.internal.concurrent.locks.ToolkitLockTypeInternal;

import com.tc.net.GroupID;
import com.tc.object.LiteralValues;
import com.tc.object.SerializationUtil;
import com.tc.object.TCObject;
import com.tc.object.bytecode.Manageable;
import com.tc.platform.PlatformService;
import com.terracotta.toolkit.concurrent.locks.ToolkitLockingApi;
import com.terracotta.toolkit.rejoin.PlatformServiceProvider;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SerializerMapImpl<K, V> implements SerializerMap<K, V>, Manageable {

  private final Map<K, V>       localMap = new HashMap<K, V>();
  private volatile Object       localResolveLock;
  private volatile TCObject     tcObject;
  private volatile GroupID      gid;
  private volatile String       lockID;
  private final PlatformService platformService;

  public SerializerMapImpl() {
    platformService = PlatformServiceProvider.getPlatformService();
  }

  @Override
  public void __tc_managed(TCObject t) {
    this.tcObject = t;
    this.gid = new GroupID(t.getObjectID().getGroupID());
    this.localResolveLock = tcObject.getResolveLock();
  }

  @Override
  public TCObject __tc_managed() {
    return tcObject;
  }

  @Override
  public boolean __tc_isManaged() {
    return tcObject != null;
  }

  private void writeLock() {
    lock(ToolkitLockTypeInternal.WRITE);
  }

  private void writeUnlock() {
    unlock(ToolkitLockTypeInternal.WRITE);
  }

  private void readLock() {
    lock(ToolkitLockTypeInternal.READ);
  }

  private void readUnlock() {
    unlock(ToolkitLockTypeInternal.READ);
  }

  private void lock(ToolkitLockTypeInternal lockLevel) {
    ToolkitLockingApi.lock(getLockID(), lockLevel, platformService);
  }

  private void unlock(ToolkitLockTypeInternal lockLevel) {
    ToolkitLockingApi.unlock(getLockID(), lockLevel, platformService);
  }

  private String getLockID() {
    if (lockID != null) { return lockID; }

    lockID = "__tc_serializer_map_" + tcObject.getObjectID().toLong();
    return lockID;
  }

  @Override
  public V put(K key, V value) {
    writeLock();
    try {
      V val = createSCOIfNeeded(value);
      synchronized (localResolveLock) {
        V ret = internalput(key, val);
        tcObject.logicalInvoke(SerializationUtil.PUT, SerializationUtil.PUT_SIGNATURE, new Object[] { key, val });
        return ret;
      }
    } finally {
      writeUnlock();
    }
  }

  @Override
  public V get(K key) {
    readLock();
    try {
      synchronized (localResolveLock) {
        return localMap.get(key);
      }
    } finally {
      readUnlock();
    }
  }

  @Override
  public V localGet(K key) {
    synchronized (localResolveLock) {
      return localMap.get(key);
    }
  }

  private V createSCOIfNeeded(V value) {
    if (LiteralValues.isLiteralInstance(value)) { return value; }
    SerializedClusterObject sco = new SerializedClusterObjectImpl(null, (byte[]) value);
    platformService.lookupOrCreate(sco, gid);
    return (V) sco;
  }

  protected V internalput(K key, V value) {
    if (LiteralValues.isLiteralInstance(value)) { return localMap.put(key, value); }
    return localMap.put(key, (V) ((SerializedClusterObject) value).getBytes());
  }

  protected Map<K, V> internalGetMap() {
    return Collections.unmodifiableMap(localMap);
  }

}
