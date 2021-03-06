/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
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

package com.orientechnologies.orient.core.storage.impl.local.paginated.wal;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import com.orientechnologies.common.directmemory.ODirectMemory;
import com.orientechnologies.common.directmemory.ODirectMemoryFactory;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.core.storage.impl.local.paginated.OLocalPaginatedStorage;

/**
 * @author Andrey Lomakin
 * @since 25.04.13
 */
public class OWriteAheadLog {
  private static final long            ONE_KB               = 1024L;
  private OLogSequenceNumber           lastCheckpoint;

  private final Object                 syncObject           = new Object();

  private final List<LogSegment>       logSegments          = new ArrayList<LogSegment>();

  private boolean                      useFirstMasterRecord = true;

  private final int                    maxRecordsCacheSize;
  private final int                    commitDelay;

  private final long                   maxSegmentSize;
  private final long                   maxLogSize;

  private long                         logSize;

  private final File                   walLocation;

  private File                         masterRecordFile;
  private final RandomAccessFile       masterRecordLSNHolder;
  private OLogSequenceNumber           firstMasterRecord;
  private OLogSequenceNumber           secondMasterRecord;

  private volatile OLogSequenceNumber  flushedLsn;
  private final OLocalPaginatedStorage paginatedStorage;

  private boolean                      closed;

  private static String calculateWalPath(OLocalPaginatedStorage storage) {
    String walPath = OGlobalConfiguration.WAL_LOCATION.getValueAsString();
    if (walPath == null)
      walPath = storage.getStoragePath();

    return walPath;
  }

  public OWriteAheadLog(OLocalPaginatedStorage storage) throws IOException {
    this(OGlobalConfiguration.WAL_CACHE_SIZE.getValueAsInteger(), OGlobalConfiguration.WAL_COMMIT_TIMEOUT.getValueAsInteger(),
        OGlobalConfiguration.WAL_MAX_SEGMENT_SIZE.getValueAsInteger() * ONE_KB * ONE_KB, OGlobalConfiguration.WAL_MAX_SIZE
            .getValueAsInteger() * ONE_KB * ONE_KB, storage);
  }

  public OWriteAheadLog(int maxRecordsCacheSize, int commitDelay, long maxSegmentSize, long maxLogSize,
      OLocalPaginatedStorage storage) throws IOException {
    this.maxRecordsCacheSize = maxRecordsCacheSize;
    this.commitDelay = commitDelay;
    this.maxSegmentSize = maxSegmentSize;
    this.maxLogSize = maxLogSize;
    this.paginatedStorage = storage;

    try {
      this.walLocation = new File(calculateWalPath(paginatedStorage));

      File[] walFiles = this.walLocation.listFiles(new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          return validateName(name);
        }
      });

      if (walFiles.length == 0) {
        LogSegment logSegment = new LogSegment(new File(this.walLocation, getSegmentName(0)), maxRecordsCacheSize);
        logSegment.init();
        logSegment.startFlush();
        logSegments.add(logSegment);

        logSize = 0;

        flushedLsn = null;
      } else {

        for (File walFile : walFiles) {
          LogSegment logSegment = new LogSegment(walFile, maxRecordsCacheSize);
          logSegment.init();

          logSegments.add(logSegment);
          logSize += logSegment.filledUpTo();
        }

        Collections.sort(logSegments);

        logSegments.get(logSegments.size() - 1).startFlush();
        flushedLsn = readFlushedLSN();
      }

      masterRecordFile = new File(walLocation, paginatedStorage.getName() + ".wmr");
      masterRecordLSNHolder = new RandomAccessFile(masterRecordFile, "rwd");

      if (masterRecordLSNHolder.length() > 0) {
        firstMasterRecord = readMasterRecord(paginatedStorage.getName(), 0);
        secondMasterRecord = readMasterRecord(paginatedStorage.getName(), 1);

        if (firstMasterRecord == null) {
          useFirstMasterRecord = true;
          lastCheckpoint = secondMasterRecord;
        } else if (secondMasterRecord == null) {
          useFirstMasterRecord = false;
          lastCheckpoint = firstMasterRecord;
        } else {
          if (firstMasterRecord.compareTo(secondMasterRecord) >= 0) {
            lastCheckpoint = firstMasterRecord;
            useFirstMasterRecord = false;
          } else {
            lastCheckpoint = secondMasterRecord;
            useFirstMasterRecord = true;
          }
        }
      }

      fixMasterRecords();

    } catch (FileNotFoundException e) {
      // never happened
      OLogManager.instance().error(this, "Error during file initialization for storage %s", e, paginatedStorage.getName());
      throw new IllegalStateException("Error during file initialization for storage " + paginatedStorage.getName(), e);
    }
  }

  public File getWalLocation() {
    return walLocation;
  }

  public OLogSequenceNumber begin() throws IOException {
    synchronized (syncObject) {
      checkForClose();

      LogSegment first = logSegments.get(0);
      if (first.filledUpTo() == 0)
        return null;

      return first.begin();
    }
  }

  public void flush() {
    synchronized (syncObject) {
      checkForClose();

      LogSegment last = logSegments.get(logSegments.size() - 1);
      last.flush();
    }
  }

  private void fixMasterRecords() throws IOException {
    if (firstMasterRecord != null) {
      int index = firstMasterRecord.getSegment() - logSegments.get(0).getOrder();
      if (logSegments.size() <= index || index < 0) {
        firstMasterRecord = null;
      } else {
        LogSegment firstMasterRecordSegment = logSegments.get(index);
        if (firstMasterRecordSegment.filledUpTo() <= firstMasterRecord.getPosition())
          firstMasterRecord = null;
      }
    }

    if (secondMasterRecord != null) {
      int index = secondMasterRecord.getSegment() - logSegments.get(0).getOrder();
      if (logSegments.size() <= index || index < 0) {
        secondMasterRecord = null;
      } else {
        LogSegment secondMasterRecordSegment = logSegments.get(index);
        if (secondMasterRecordSegment.filledUpTo() <= secondMasterRecord.getPosition())
          secondMasterRecord = null;
      }
    }

    if (firstMasterRecord != null && secondMasterRecord != null)
      return;

    if (firstMasterRecord == null && secondMasterRecord == null) {
      masterRecordLSNHolder.setLength(0);
      masterRecordLSNHolder.getFD().sync();
      lastCheckpoint = null;
    } else {
      if (secondMasterRecord == null)
        secondMasterRecord = firstMasterRecord;
      else
        firstMasterRecord = secondMasterRecord;

      lastCheckpoint = firstMasterRecord;

      writeMasterRecord(0, firstMasterRecord);
      writeMasterRecord(1, secondMasterRecord);
    }
  }

  private OLogSequenceNumber readMasterRecord(String storageName, int index) throws IOException {
    CRC32 crc32 = new CRC32();
    try {
      masterRecordLSNHolder.seek(index * (2 * OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE));

      int firstCRC = masterRecordLSNHolder.readInt();
      int segment = masterRecordLSNHolder.readInt();
      long position = masterRecordLSNHolder.readLong();

      byte[] serializedLSN = new byte[OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE];
      OIntegerSerializer.INSTANCE.serialize(segment, serializedLSN, 0);
      OLongSerializer.INSTANCE.serialize(position, serializedLSN, OIntegerSerializer.INT_SIZE);
      crc32.update(serializedLSN);

      if (firstCRC != ((int) crc32.getValue())) {
        OLogManager.instance().error(this, "Can not restore %d WAL master record for storage %s crc check is failed", index,
            storageName);
        return null;
      }

      return new OLogSequenceNumber(segment, position);
    } catch (EOFException eofException) {
      OLogManager.instance().warn(this, "Can not restore %d WAL master record for storage %s", index, storageName);
      return null;
    }
  }

  private void writeMasterRecord(int index, OLogSequenceNumber masterRecord) throws IOException {
    masterRecordLSNHolder.seek(index * (2 * OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE));
    CRC32 crc32 = new CRC32();

    byte[] serializedLSN = new byte[OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE];
    OIntegerSerializer.INSTANCE.serialize(masterRecord.getSegment(), serializedLSN, 0);
    OLongSerializer.INSTANCE.serialize(masterRecord.getPosition(), serializedLSN, OIntegerSerializer.INT_SIZE);
    crc32.update(serializedLSN);

    masterRecordLSNHolder.writeInt((int) crc32.getValue());
    masterRecordLSNHolder.writeInt(masterRecord.getSegment());
    masterRecordLSNHolder.writeLong(masterRecord.getPosition());
  }

  private String getSegmentName(int order) {
    return paginatedStorage.getName() + "." + order + ".wal";
  }

  public OLogSequenceNumber logFuzzyCheckPointStart() throws IOException {
    synchronized (syncObject) {
      checkForClose();

      OFuzzyCheckpointStartRecord record = new OFuzzyCheckpointStartRecord(lastCheckpoint);
      log(record);
      return record.getLsn();
    }
  }

  public OLogSequenceNumber logFuzzyCheckPointEnd() throws IOException {
    synchronized (syncObject) {
      checkForClose();

      OFuzzyCheckpointEndRecord record = new OFuzzyCheckpointEndRecord();
      log(record);
      return record.getLsn();
    }
  }

  public OLogSequenceNumber log(OWALRecord record) throws IOException {
    synchronized (syncObject) {
      checkForClose();

      final byte[] serializedForm = OWALRecordsFactory.INSTANCE.toStream(record);

      LogSegment last = logSegments.get(logSegments.size() - 1);
      long lastSize = last.filledUpTo();

      final OLogSequenceNumber lsn = last.logRecord(serializedForm);
      record.setLsn(lsn);

      if (record.isUpdateMasterRecord()) {
        lastCheckpoint = lsn;
        if (useFirstMasterRecord) {
          firstMasterRecord = lsn;
          writeMasterRecord(0, firstMasterRecord);
          useFirstMasterRecord = false;
        } else {
          secondMasterRecord = lsn;
          writeMasterRecord(1, secondMasterRecord);
          useFirstMasterRecord = true;
        }
      }

      final long sizeDiff = last.filledUpTo() - lastSize;
      logSize += sizeDiff;

      if (logSize >= maxLogSize) {
        LogSegment first = logSegments.get(0);
        first.stopFlush(false);

        logSize -= first.filledUpTo();

        if (!first.delete(false))
          OLogManager.instance().error(this, "Log segment %s can not be removed from WAL", first.getPath());

        logSegments.remove(0);

        fixMasterRecords();

        paginatedStorage.scheduleCheckpoint();
      }

      if (last.filledUpTo() >= maxSegmentSize) {
        last.stopFlush(true);

        last = new LogSegment(new File(walLocation, getSegmentName(last.getOrder() + 1)), maxRecordsCacheSize);
        last.init();
        last.startFlush();

        logSegments.add(last);
      }

      return lsn;
    }
  }

  public long size() {
    synchronized (syncObject) {
      return logSize;
    }
  }

  public void close() throws IOException {
    close(true);
  }

  public void close(boolean flush) throws IOException {
    synchronized (syncObject) {
      if (closed)
        return;

      closed = true;

      for (LogSegment logSegment : logSegments)
        logSegment.close(flush);

      masterRecordLSNHolder.close();
    }

  }

  private void checkForClose() {
    if (closed)
      throw new OStorageException("WAL log " + walLocation + " has been closed");
  }

  public void delete() throws IOException {
    delete(false);
  }

  public void delete(boolean flush) throws IOException {
    synchronized (syncObject) {
      close(flush);

      for (LogSegment logSegment : logSegments)
        if (!logSegment.delete(false))
          OLogManager.instance().error(this, "Can not delete WAL segment %s for storage %s", logSegment.getPath(),
              paginatedStorage.getName());

      if (!masterRecordFile.delete())
        OLogManager.instance().error(this, "Can not delete WAL state file for %s storage", paginatedStorage.getName());
    }
  }

  public void logDirtyPages(Set<ODirtyPage> dirtyPages) throws IOException {
    synchronized (syncObject) {
      checkForClose();

      log(new ODirtyPagesRecord(dirtyPages));
    }
  }

  public OLogSequenceNumber getLastCheckpoint() {
    synchronized (syncObject) {
      checkForClose();

      return lastCheckpoint;
    }
  }

  public OWALRecord read(OLogSequenceNumber lsn) throws IOException {
    synchronized (syncObject) {
      checkForClose();

      int segment = lsn.getSegment();
      int index = segment - logSegments.get(0).getOrder();

      if (index < 0 || index >= logSegments.size())
        return null;

      LogSegment logSegment = logSegments.get(index);
      byte[] recordEntry = logSegment.readRecord(lsn);
      if (recordEntry == null)
        return null;

      final OWALRecord record = OWALRecordsFactory.INSTANCE.fromStream(recordEntry);
      record.setLsn(lsn);

      return record;
    }
  }

  public OLogSequenceNumber next(OLogSequenceNumber lsn) throws IOException {
    synchronized (syncObject) {
      checkForClose();

      int order = lsn.getSegment();
      int index = order - logSegments.get(0).getOrder();

      if (index < 0 || index >= logSegments.size())
        return null;

      LogSegment logSegment = logSegments.get(index);
      OLogSequenceNumber nextLSN = logSegment.getNextLSN(lsn);

      if (nextLSN == null) {
        index++;
        if (index >= logSegments.size())
          return null;

        LogSegment nextSegment = logSegments.get(index);
        if (nextSegment.filledUpTo() == 0)
          return null;

        nextLSN = nextSegment.begin();
      }

      return nextLSN;
    }
  }

  public OLogSequenceNumber getFlushedLSN() {
    synchronized (syncObject) {
      checkForClose();

      return flushedLsn;
    }
  }

  private OLogSequenceNumber readFlushedLSN() throws IOException {
    int segment = logSegments.size() - 1;
    while (segment >= 0) {
      LogSegment logSegment = logSegments.get(segment);
      OLogSequenceNumber flushedLSN = logSegment.readFlushedLSN();
      if (flushedLSN == null)
        segment--;
      else
        return flushedLSN;
    }

    return null;
  }

  public static boolean validateName(String name) {
    if (!name.toLowerCase().endsWith(".wal"))
      return false;

    int walOrderStartIndex = name.indexOf('.');

    if (walOrderStartIndex == name.length() - 4)
      return false;

    int walOrderEndIndex = name.indexOf('.', walOrderStartIndex + 1);
    String walOrder = name.substring(walOrderStartIndex + 1, walOrderEndIndex);
    try {
      Integer.parseInt(walOrder);
    } catch (NumberFormatException e) {
      return false;
    }

    return true;
  }

  public OLogSequenceNumber logCheckpointStart() throws IOException {
    return log(new OCheckpointStartRecord(lastCheckpoint));
  }

  public void logCheckpointEnd() throws IOException {
    synchronized (syncObject) {
      checkForClose();

      log(new OCheckpointEndRecord());
    }
  }

  private final class LogSegment implements Comparable<LogSegment> {
    private final RandomAccessFile                rndFile;

    private final File                            file;

    private long                                  filledUpTo;

    private final int                             order;
    private final int                             maxRecordsCacheSize;
    private boolean                               closed;

    private OWALPage                              currentPage;
    private final ConcurrentLinkedQueue<OWALPage> pages          = new ConcurrentLinkedQueue<OWALPage>();

    private ODirectMemory                         directMemory   = ODirectMemoryFactory.INSTANCE.directMemory();

    private long                                  nextPositionToFlush;

    private final ScheduledExecutorService        commitExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                                                                   @Override
                                                                   public Thread newThread(Runnable r) {
                                                                     Thread thread = new Thread(r);
                                                                     thread.setDaemon(true);
                                                                     thread.setName("WAL Flush Task");
                                                                     return thread;
                                                                   }
                                                                 });

    private LogSegment(File file, int maxRecordsCacheSize) throws IOException {
      this.file = file;
      this.maxRecordsCacheSize = maxRecordsCacheSize;

      order = extractOrder(file.getName());
      closed = false;
      rndFile = new RandomAccessFile(file, "rw");
    }

    public void startFlush() {
      if (commitDelay > 0)
        commitExecutor.scheduleAtFixedRate(new FlushTask(), commitDelay, commitDelay, TimeUnit.MILLISECONDS);
    }

    public void stopFlush(boolean flush) {
      if (flush)
        flush();

      if (!commitExecutor.isShutdown()) {
        commitExecutor.shutdown();
        try {
          if (!commitExecutor.awaitTermination(10000, TimeUnit.MILLISECONDS))
            throw new OStorageException("WAL flush task for " + getPath() + " segment can not be stopped.");

        } catch (InterruptedException e) {
          OLogManager.instance().error(this, "Can not shutdown background WAL commit thread.");
        }
      }
    }

    public int getOrder() {
      return order;
    }

    public boolean init() throws IOException {
      boolean error = selfCheck();

      initPageCache();

      return error;
    }

    private void initPageCache() throws IOException {
      synchronized (rndFile) {
        long pagesCount = rndFile.length() / OWALPage.PAGE_SIZE;
        if (pagesCount == 0)
          return;

        rndFile.seek((pagesCount - 1) * OWALPage.PAGE_SIZE);
        byte[] content = new byte[OWALPage.PAGE_SIZE];
        rndFile.readFully(content);

        long pointer = directMemory.allocate(content);
        currentPage = new OWALPage(pointer, false);
        filledUpTo = (pagesCount - 1) * OWALPage.PAGE_SIZE + currentPage.getFilledUpTo();
        nextPositionToFlush = (pagesCount - 1) * OWALPage.PAGE_SIZE;

        pages.add(currentPage);
      }
    }

    private int extractOrder(String name) {
      int walOrderStartIndex = name.indexOf('.') + 1;

      int walOrderEndIndex = name.indexOf('.', walOrderStartIndex);
      String walOrder = name.substring(walOrderStartIndex, walOrderEndIndex);
      try {
        return Integer.parseInt(walOrder);
      } catch (NumberFormatException e) {
        // never happen
        throw new IllegalStateException(e);
      }
    }

    @Override
    public int compareTo(LogSegment other) {
      final int otherOrder = other.order;

      if (order > otherOrder)
        return 1;
      else if (order < otherOrder)
        return -1;

      return 0;
    }

    public long filledUpTo() throws IOException {
      return filledUpTo;
    }

    public OLogSequenceNumber begin() throws IOException {
      if (!pages.isEmpty())
        return new OLogSequenceNumber(order, OWALPage.RECORDS_OFFSET);

      if (rndFile.length() > 0)
        return new OLogSequenceNumber(order, OWALPage.RECORDS_OFFSET);

      return null;
    }

    private int findLastRecord(OWALPage page) {
      int prevOffset = OWALPage.RECORDS_OFFSET;
      int pageOffset = OWALPage.RECORDS_OFFSET;
      int maxOffset = page.getFilledUpTo();

      while (pageOffset < maxOffset) {
        prevOffset = pageOffset;
        pageOffset += page.getSerializedRecordSize(pageOffset);
      }

      if (page.recordTail(prevOffset))
        return -1;

      return prevOffset;
    }

    public boolean delete(boolean flush) throws IOException {
      close(flush);
      return file.delete();
    }

    public String getPath() {
      return file.getAbsolutePath();
    }

    public OLogSequenceNumber logRecord(byte[] record) throws IOException {
      int pageOffset = (int) (filledUpTo % OWALPage.PAGE_SIZE);
      long pageIndex = filledUpTo / OWALPage.PAGE_SIZE;

      if (pageOffset == 0 && pageIndex > 0)
        pageIndex--;

      int pos = 0;
      boolean firstRecord = true;

      OLogSequenceNumber lsn = null;

      while (pos < record.length) {
        if (currentPage == null) {
          long pointer = directMemory.allocate(OWALPage.PAGE_SIZE);
          currentPage = new OWALPage(pointer, true);
          pages.add(currentPage);
          filledUpTo += OWALPage.RECORDS_OFFSET;
        }

        int freeSpace = currentPage.getFreeSpace();
        if (freeSpace < OWALPage.MIN_RECORD_SIZE) {
          filledUpTo += freeSpace + OWALPage.RECORDS_OFFSET;
          long pointer = directMemory.allocate(OWALPage.PAGE_SIZE);
          currentPage = new OWALPage(pointer, true);
          pages.add(currentPage);
          pageIndex++;

          freeSpace = currentPage.getFreeSpace();
        }

        final OWALPage walPage = currentPage;
        synchronized (walPage) {
          final int entrySize = OWALPage.calculateSerializedSize(record.length - pos);
          int addedChunkOffset;
          if (entrySize <= freeSpace) {
            if (pos == 0)
              addedChunkOffset = walPage.appendRecord(record, false, !firstRecord);
            else
              addedChunkOffset = walPage.appendRecord(Arrays.copyOfRange(record, pos, record.length), false, !firstRecord);

            pos = record.length;
          } else {
            int chunkSize = OWALPage.calculateRecordSize(freeSpace);
            if (chunkSize > record.length - pos)
              chunkSize = record.length - pos;

            addedChunkOffset = walPage.appendRecord(Arrays.copyOfRange(record, pos, pos + chunkSize), true, !firstRecord);
            pos += chunkSize;
          }

          if (firstRecord) {
            lsn = new OLogSequenceNumber(order, pageIndex * OWALPage.PAGE_SIZE + addedChunkOffset);
          }

          int spaceDiff = freeSpace - walPage.getFreeSpace();
          filledUpTo += spaceDiff;

          firstRecord = false;
        }
      }

      if (pages.size() > maxRecordsCacheSize) {
        OLogManager.instance().info(this, "Max cache limit is reached (%d vs. %d), sync flush is performed.", maxRecordsCacheSize,
            pages.size());
        flush();
      }

      return lsn;
    }

    public byte[] readRecord(OLogSequenceNumber lsn) throws IOException {
      assert lsn.getSegment() == order;

      if (lsn.getPosition() >= filledUpTo)
        return null;

      if (flushedLsn == null || flushedLsn.compareTo(lsn) < 0)
        flush();

      byte[] record = null;
      long pageIndex = lsn.getPosition() / OWALPage.PAGE_SIZE;
      int pageOffset = (int) (lsn.getPosition() % OWALPage.PAGE_SIZE);

      long pageCount = (filledUpTo + OWALPage.PAGE_SIZE - 1) / OWALPage.PAGE_SIZE;

      while (pageIndex < pageCount) {
        synchronized (rndFile) {
          byte[] pageContent = new byte[OWALPage.PAGE_SIZE];
          rndFile.seek(pageIndex * OWALPage.PAGE_SIZE);
          rndFile.readFully(pageContent);

          long pointer = directMemory.allocate(pageContent);
          try {
            OWALPage page = new OWALPage(pointer, false);

            byte[] content = page.getRecord(pageOffset);
            if (record == null)
              record = content;
            else {
              byte[] oldRecord = record;

              record = new byte[record.length + content.length];
              System.arraycopy(oldRecord, 0, record, 0, oldRecord.length);
              System.arraycopy(content, 0, record, oldRecord.length, record.length - oldRecord.length);
            }

            if (page.mergeWithNextPage(pageOffset)) {
              pageOffset = OWALPage.RECORDS_OFFSET;
              pageIndex++;
            } else
              break;
          } finally {
            directMemory.free(pointer);
          }
        }
      }

      return record;
    }

    public OLogSequenceNumber getNextLSN(OLogSequenceNumber lsn) throws IOException {
      final byte[] record = readRecord(lsn);
      if (record == null)
        return null;

      long pos = lsn.getPosition();
      long pageIndex = pos / OWALPage.PAGE_SIZE;
      int pageOffset = (int) (pos - pageIndex * OWALPage.PAGE_SIZE);

      int restOfRecord = record.length;
      while (restOfRecord > 0) {
        int entrySize = OWALPage.calculateSerializedSize(restOfRecord);
        if (entrySize + pageOffset < OWALPage.PAGE_SIZE) {
          if (entrySize + pageOffset <= OWALPage.PAGE_SIZE - OWALPage.MIN_RECORD_SIZE)
            pos += entrySize;
          else
            pos += OWALPage.PAGE_SIZE - pageOffset + OWALPage.RECORDS_OFFSET;
          break;
        } else if (entrySize + pageOffset == OWALPage.PAGE_SIZE) {
          pos += entrySize + OWALPage.RECORDS_OFFSET;
          break;
        } else {
          int chunkSize = OWALPage.calculateRecordSize(OWALPage.PAGE_SIZE - pageOffset);
          restOfRecord -= chunkSize;

          pos += OWALPage.PAGE_SIZE - pageOffset + OWALPage.RECORDS_OFFSET;
          pageOffset = OWALPage.RECORDS_OFFSET;
        }
      }

      if (pos >= filledUpTo)
        return null;

      return new OLogSequenceNumber(order, pos);
    }

    public void close(boolean flush) throws IOException {
      if (!closed) {
        stopFlush(flush);

        rndFile.close();

        closed = true;

        if (!pages.isEmpty()) {
          for (OWALPage page : pages)
            directMemory.free(page.getPagePointer());
        }

        currentPage = null;
      }
    }

    private boolean selfCheck() throws IOException {
      if (!pages.isEmpty())
        throw new IllegalStateException("WAL cache is not empty, we can not verify WAL after it was started to be used");
      synchronized (rndFile) {
        long pagesCount = rndFile.length() / OWALPage.PAGE_SIZE;

        boolean errorsWereFound = false;

        if (rndFile.length() % OWALPage.PAGE_SIZE > 0) {
          OLogManager.instance().error(this, "Last WAL page was written partially, auto fix.");

          rndFile.setLength(OWALPage.PAGE_SIZE * pagesCount);

          errorsWereFound = true;
        }

        long currentPage = pagesCount - 1;
        CRC32 crc32 = new CRC32();
        while (currentPage >= 0) {
          crc32.reset();

          byte[] content = new byte[OWALPage.PAGE_SIZE];
          rndFile.seek(currentPage * OWALPage.PAGE_SIZE);
          rndFile.readFully(content);

          int pageCRC = OIntegerSerializer.INSTANCE.deserializeNative(content, 0);

          crc32.update(content, OIntegerSerializer.INT_SIZE, OWALPage.PAGE_SIZE - OIntegerSerializer.INT_SIZE);
          int calculatedCRC = (int) crc32.getValue();

          if (pageCRC != calculatedCRC) {
            OLogManager.instance().error(this, "%d WAL page has been broken and will be truncated.", currentPage);

            currentPage--;
            pagesCount = currentPage + 1;
            rndFile.setLength(pagesCount * OWALPage.PAGE_SIZE);

            errorsWereFound = true;
          } else {
            if (!errorsWereFound)
              break;

            long pointer = directMemory.allocate(content);
            try {
              OWALPage page = new OWALPage(pointer, false);
              int pageOffset = findLastRecord(page);
              if (pageOffset >= 0) {
                if (page.mergeWithNextPage(pageOffset)) {
                  page.truncateTill(pageOffset);
                  rndFile.seek(currentPage * OWALPage.PAGE_SIZE);
                  content = directMemory.get(pointer, OWALPage.PAGE_SIZE);
                  rndFile.write(content);

                  if (page.isEmpty()) {
                    OLogManager.instance().error(this, "%d WAL page has been broken and will be truncated.", currentPage);
                    currentPage--;

                    pagesCount = currentPage + 1;
                    rndFile.setLength(pagesCount * OWALPage.PAGE_SIZE);
                  } else
                    break;
                } else
                  break;
              } else {
                OLogManager.instance().error(this, "%d WAL page has been broken and will be truncated.", currentPage);
                currentPage--;
                pagesCount = currentPage + 1;
                rndFile.setLength(pagesCount * OWALPage.PAGE_SIZE);
              }
            } finally {
              directMemory.free(pointer);
            }
          }
        }

        rndFile.getFD().sync();

        return errorsWereFound;
      }
    }

    public OLogSequenceNumber readFlushedLSN() throws IOException {
      long pages = rndFile.length() / OWALPage.PAGE_SIZE;
      if (pages == 0)
        return null;

      long pageIndex = pages - 1;

      while (true) {
        synchronized (syncObject) {
          rndFile.seek(pageIndex * OWALPage.PAGE_SIZE);

          byte[] pageContent = new byte[OWALPage.PAGE_SIZE];
          rndFile.readFully(pageContent);

          long pointer = directMemory.allocate(pageContent);
          try {

            OWALPage page = new OWALPage(pointer, false);
            int pageOffset = findLastRecord(page);

            if (pageOffset < 0) {
              pageIndex--;
              if (pageIndex < 0)
                return null;

              continue;
            }

            return new OLogSequenceNumber(order, pageIndex * OWALPage.PAGE_SIZE + pageOffset);
          } finally {
            directMemory.free(pointer);
          }
        }
      }
    }

    public void flush() {
      if (!commitExecutor.isShutdown()) {
        try {
          commitExecutor.submit(new FlushTask()).get();
        } catch (InterruptedException e) {
          Thread.interrupted();
          throw new OStorageException("Thread was interrupted during flush", e);
        } catch (ExecutionException e) {
          throw new OStorageException("Error during WAL segment " + getPath() + " flush.");
        }
      } else {
        new FlushTask().run();
      }

    }

    private final class FlushTask implements Runnable {

      private FlushTask() {
      }

      @Override
      public void run() {
        try {
          commit();
        } catch (Throwable e) {
          OLogManager.instance().error(this, "Error during WAL background flush", e);
        }
      }

      private void commit() throws IOException {
        if (pages.isEmpty())
          return;

        final int maxSize = pages.size();

        ArrayList<byte[]> pagesToFlush = new ArrayList<byte[]>(maxSize);

        long filePointer = nextPositionToFlush;
        int lastRecordOffset = -1;
        long lastPageIndex = -1;

        int flushedPages = 0;

        Iterator<OWALPage> pageIterator = pages.iterator();
        while (flushedPages < maxSize) {
          final OWALPage page = pageIterator.next();
          synchronized (page) {
            pagesToFlush.add(directMemory.get(page.getPagePointer(), OWALPage.PAGE_SIZE));
            int recordOffset = findLastRecord(page);
            if (recordOffset >= 0) {
              lastRecordOffset = recordOffset;
              lastPageIndex = flushedPages;
            }
          }

          flushedPages++;
        }

        synchronized (rndFile) {
          rndFile.seek(filePointer);
          for (byte[] pageContent : pagesToFlush) {
            flushPage(pageContent);
            filePointer += OWALPage.PAGE_SIZE;
          }

          rndFile.getFD().sync();
        }

        long oldPositionToFlush = nextPositionToFlush;
        nextPositionToFlush = filePointer - OWALPage.PAGE_SIZE;

        if (lastRecordOffset >= 0)
          flushedLsn = new OLogSequenceNumber(order, oldPositionToFlush + lastPageIndex * OWALPage.PAGE_SIZE + lastRecordOffset);

        for (int i = 0; i < flushedPages - 1; i++) {
          OWALPage page = pages.poll();
          directMemory.free(page.getPagePointer());
        }

        assert !pages.isEmpty();
      }

      private void flushPage(byte[] content) throws IOException {
        CRC32 crc32 = new CRC32();
        crc32.update(content, OIntegerSerializer.INT_SIZE, OWALPage.PAGE_SIZE - OIntegerSerializer.INT_SIZE);
        OIntegerSerializer.INSTANCE.serializeNative((int) crc32.getValue(), content, 0);

        rndFile.write(content);
      }
    }
  }

}
