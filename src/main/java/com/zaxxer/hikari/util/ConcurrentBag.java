/*
 * Copyright (C) 2013, 2014 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zaxxer.hikari.util;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry;

import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_NOT_IN_USE;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_REMOVED;
import static com.zaxxer.hikari.util.ConcurrentBag.IConcurrentBagEntry.STATE_RESERVED;

/**
 * This is a specialized concurrent bag that achieves superior performance
 * to LinkedBlockingQueue and LinkedTransferQueue for the purposes of a
 * connection pool.  It uses ThreadLocal storage when possible to avoid
 * locks, but resorts to scanning a common collection if there are no
 * available items in the ThreadLocal list.  Not-in-use items in the
 * ThreadLocal lists can be "stolen" when the borrowing thread has none
 * of its own.  It is a "lock-less" implementation using a specialized
 * AbstractQueuedLongSynchronizer to manage cross-thread signaling.
 *
 * Note that items that are "borrowed" from the bag are not actually
 * removed from any collection, so garbage collection will not occur
 * even if the reference is abandoned.  Thus care must be taken to
 * "requite" borrowed objects otherwise a memory leak will result.  Only
 * the "remove" method can completely remove an object from the bag.
 *
 * 通过 LinkedBlockingQueue和LinkedTransferQueue 来构造一个链接池
 * 用ThreadLocal存储来避免锁,但是如果在ThreadLocal 列表里没有可用的元素，就使用通用的集合
 * Not-in-use的元素可以被借用给别的线程，当需要的线程没有可以的元素时
 * 
 * borrowed的元素并不会被清除,必须requite 借用的对象,否则会造成内存泄漏
 * 只有remove方法可以完全移除一个对象
 * @author Brett Wooldridge
 *
 * @param <T> the templated type to store in the bag
 */
public class ConcurrentBag<T extends IConcurrentBagEntry> implements AutoCloseable
{
   private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrentBag.class);

   private final QueuedSequenceSynchronizer synchronizer;
   private final CopyOnWriteArrayList<T> sharedList;
   private final boolean weakThreadLocals;

   private final ThreadLocal<List<Object>> threadList;
   private final IBagStateListener listener;
   private final AtomicInteger waiters;
   private volatile boolean closed;

   public interface IConcurrentBagEntry
   {
      int STATE_NOT_IN_USE = 0;
      int STATE_IN_USE = 1;
      int STATE_REMOVED = -1;
      int STATE_RESERVED = -2;

      boolean compareAndSet(int expectState, int newState);
      void lazySet(int newState);
      int getState();
   }

   public interface IBagStateListener
   {
      Future<Boolean> addBagItem();
   }

   /**
    * Construct a ConcurrentBag with the specified listener.
    *
    * @param listener the IBagStateListener to attach to this bag
    */
   public ConcurrentBag(final IBagStateListener listener)
   {
      this.listener = listener;
      this.weakThreadLocals = useWeakThreadLocals();

      this.waiters = new AtomicInteger();
      this.sharedList = new CopyOnWriteArrayList<>();
      this.synchronizer = new QueuedSequenceSynchronizer();
      if (weakThreadLocals) {
         this.threadList = new ThreadLocal<>();
      }
      else {
         this.threadList = new ThreadLocal<List<Object>>() {
            @Override
            protected List<Object> initialValue()
            {
               return new FastList<>(IConcurrentBagEntry.class, 16);
            }
         };
      }
   }

   /**
    * The method will borrow a BagEntry from the bag, blocking for the
    * specified timeout if none are available.
    *  借用一个BagEntry 如果超时就是不可用
    * @param timeout how long to wait before giving up, in units of unit
    * @param timeUnit a <code>TimeUnit</code> determining how to interpret the timeout parameter
    * @return a borrowed instance from the bag or null if a timeout occurs
    * @throws InterruptedException if interrupted while waiting
    */
   public T borrow(long timeout, final TimeUnit timeUnit) throws InterruptedException
   {
      // Try the thread-local list first
      List<Object> list = threadList.get();
      if (weakThreadLocals && list == null) {
         list = new ArrayList<>(16);
         threadList.set(list);
      }

      for (int i = list.size() - 1; i >= 0; i--) {
         final Object entry = list.remove(i);
         @SuppressWarnings("unchecked")
         final T bagEntry = weakThreadLocals ? ((WeakReference<T>) entry).get() : (T) entry;
         if (bagEntry != null && bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
            return bagEntry;
         }
      }

      // Otherwise, scan the shared list ... for maximum of timeout
      timeout = timeUnit.toNanos(timeout);
      Future<Boolean> addItemFuture = null;
      final long startScan = System.nanoTime();
      final long originTimeout = timeout;
      long startSeq;
      waiters.incrementAndGet();
      try {
         do {
            // scan the shared list
            do {
               startSeq = synchronizer.currentSequence();
               for (T bagEntry : sharedList) {
                  if (bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
                     // if we might have stolen another thread's new connection, restart the add...
                	 // 如果借用了其他现场的新链接，就新添加一个bag元素
                     if (waiters.get() > 1 && addItemFuture == null) {
                        listener.addBagItem();
                     }

                     return bagEntry;
                  }
               }
            } while (startSeq < synchronizer.currentSequence());

            if (addItemFuture == null || addItemFuture.isDone()) {
               addItemFuture = listener.addBagItem();
            }

            timeout = originTimeout - (System.nanoTime() - startScan);
         } while (timeout > 10_000L && synchronizer.waitUntilSequenceExceeded(startSeq, timeout));
      }
      finally {
         waiters.decrementAndGet();
      }

      return null;
   }

   /**
    * This method will return a borrowed object to the bag.  Objects
    * that are borrowed from the bag but never "requited" will result
    * in a memory leak.
    * 
    * 该方法会返回一个被借用的对象给bag
    * 被借用的对象如果不requited 会导致内存泄漏
    * @param bagEntry the value to return to the bag
    * @throws NullPointerException if value is null
    * @throws IllegalStateException if the requited value was not borrowed from the bag
    */
   public void requite(final T bagEntry)
   {
      bagEntry.lazySet(STATE_NOT_IN_USE);

      final List<Object> threadLocalList = threadList.get();
      if (threadLocalList != null) {
         threadLocalList.add(weakThreadLocals ? new WeakReference<>(bagEntry) : bagEntry);
      }

      synchronizer.signal();
   }

   /**
    * Add a new object to the bag for others to borrow.
    * 添加一个对象到bag为其他对象可以借用
    * @param bagEntry an object to add to the bag
    */
   public void add(final T bagEntry)
   {
      if (closed) {
         LOGGER.info("ConcurrentBag has been closed, ignoring add()");
         throw new IllegalStateException("ConcurrentBag has been closed, ignoring add()");
      }

      sharedList.add(bagEntry);
      synchronizer.signal();
   }

   /**
    * Remove a value from the bag.  This method should only be called
    * with objects obtained by <code>borrow(long, TimeUnit)</code> or <code>reserve(T)</code>
    * 
    * 从bag移除一个元素，该方法只能在borrow或reserve调用之后
    * 
    * @param bagEntry the value to remove
    * @return true if the entry was removed, false otherwise
    * @throws IllegalStateException if an attempt is made to remove an object
    *         from the bag that was not borrowed or reserved first
    */
   public boolean remove(final T bagEntry)
   {
      if (!bagEntry.compareAndSet(STATE_IN_USE, STATE_REMOVED) && !bagEntry.compareAndSet(STATE_RESERVED, STATE_REMOVED) && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that was not borrowed or reserved: {}", bagEntry);
         return false;
      }

      final boolean removed = sharedList.remove(bagEntry);
      if (!removed && !closed) {
         LOGGER.warn("Attempt to remove an object from the bag that does not exist: {}", bagEntry);
      }

      // synchronizer.signal();
      return removed;
   }

   /**
    * Close the bag to further adds.
    */
   @Override
   public void close()
   {
      closed = true;
   }

   /**
    * This method provides a "snapshot" in time of the BagEntry
    * items in the bag in the specified state.  It does not "lock"
    * or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in list before performing any action on them.
    * 
    * 该方法提供了一个当前时间bag的指定状态的快照
    * @param state one of the {@link IConcurrentBagEntry} states
    * @return a possibly empty list of objects having the state specified
    */
   public List<T> values(final int state)
   {
      final ArrayList<T> list = new ArrayList<>(sharedList.size());
      for (final T entry : sharedList) {
         if (entry.getState() == state) {
            list.add(entry);
         }
      }

      return list;
   }

   /**
    * This method provides a "snapshot" in time of the bag items.  It
    * does not "lock" or reserve items in any way.  Call <code>reserve(T)</code>
    * on items in the list, or understand the concurrency implications of
    * modifying items, before performing any action on them.
    *
    * @return a possibly empty list of (all) bag items
    */
   @SuppressWarnings("unchecked")
   public List<T> values()
   {
      return (List<T>) sharedList.clone();
   }

   /**
    * The method is used to make an item in the bag "unavailable" for
    * borrowing.  It is primarily used when wanting to operate on items
    * returned by the <code>values(int)</code> method.  Items that are
    * reserved can be removed from the bag via <code>remove(T)</code>
    * without the need to unreserve them.  Items that are not removed
    * from the bag can be make available for borrowing again by calling
    * the <code>unreserve(T)</code> method.
    * 
    * 该方法用于让一个bag不可用于借用
    * @param bagEntry the item to reserve
    * @return true if the item was able to be reserved, false otherwise
    */
   public boolean reserve(final T bagEntry)
   {
      return bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_RESERVED);
   }

   /**
    * This method is used to make an item reserved via <code>reserve(T)</code>
    * available again for borrowing.
    *
    * 用于将一个STATE_RESERVED的元素可以被借用
    * @param bagEntry the item to unreserve
    */
   public void unreserve(final T bagEntry)
   {
      if (bagEntry.compareAndSet(STATE_RESERVED, STATE_NOT_IN_USE)) {
         synchronizer.signal();
      }
      else {
         LOGGER.warn("Attempt to relinquish an object to the bag that was not reserved: {}", bagEntry);
      }
   }

   /**
    * Get the number of threads pending (waiting) for an item from the
    * bag to become available.
    *
    * 获取有多少线程等待元素可用
    * @return the number of threads waiting for items from the bag
    */
   public int getPendingQueue()
   {
      return synchronizer.getQueueLength();
   }

   /**
    * Get a count of the number of items in the specified state at the time of this call.
    *
    * 获取指定状态的元素的数量，当前时间点
    * @param state the state of the items to count
    * @return a count of how many items in the bag are in the specified state
    */
   public int getCount(final int state)
   {
      int count = 0;
      for (final T entry : sharedList) {
         if (entry.getState() == state) {
            count++;
         }
      }
      return count;
   }

   /**
    * Get the total number of items in the bag.
    * 获取总数
    * @return the number of items in the bag
    */
   public int size()
   {
      return sharedList.size();
   }

   public void dumpState()
   {
      for (T bagEntry : sharedList) {
         LOGGER.info(bagEntry.toString());
      }
   }

   /**
    * Determine whether to use WeakReferences based on whether there is a
    * custom ClassLoader implementation sitting between this class and the
    * System ClassLoader.
    * 
    *  判断是否使用WeakReferences 
    * @return true if we should use WeakReferences in our ThreadLocals, false otherwise
    */
   private boolean useWeakThreadLocals()
   {
      try {
         if (System.getProperty("com.zaxxer.hikari.useWeakReferences") != null) {   // undocumented manual override of WeakReference behavior
            return Boolean.getBoolean("com.zaxxer.hikari.useWeakReferences");
         }

         return getClass().getClassLoader() != ClassLoader.getSystemClassLoader();
      }
      catch (SecurityException se) {
         return true;
      }
   }
}
