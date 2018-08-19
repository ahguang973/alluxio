/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.block;

import alluxio.Configuration;
import alluxio.Constants;
import alluxio.MasterStorageTierAssoc;
import alluxio.PropertyKey;
import alluxio.StorageTierAssoc;
import alluxio.clock.SystemClock;
import alluxio.collections.ConcurrentHashSet;
import alluxio.collections.IndexDefinition;
import alluxio.collections.IndexedSet;
import alluxio.exception.BlockInfoException;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.NoWorkerException;
import alluxio.exception.status.UnavailableException;
import alluxio.heartbeat.HeartbeatContext;
import alluxio.heartbeat.HeartbeatExecutor;
import alluxio.heartbeat.HeartbeatThread;
import alluxio.master.AbstractMaster;
import alluxio.master.MasterContext;
import alluxio.master.block.meta.MasterBlockInfo;
import alluxio.master.block.meta.MasterBlockLocation;
import alluxio.master.block.meta.MasterWorkerInfo;
import alluxio.master.journal.JournalContext;
import alluxio.metrics.MetricsSystem;
import alluxio.proto.journal.Block.BlockContainerIdGeneratorEntry;
import alluxio.proto.journal.Block.BlockInfoEntry;
import alluxio.proto.journal.Block.DeleteBlockEntry;
import alluxio.proto.journal.Journal.JournalEntry;
import alluxio.thrift.BlockMasterClientService;
import alluxio.thrift.BlockMasterWorkerService;
import alluxio.thrift.Command;
import alluxio.thrift.CommandType;
import alluxio.util.CommonUtils;
import alluxio.util.IdUtils;
import alluxio.util.executor.ExecutorServiceFactories;
import alluxio.util.executor.ExecutorServiceFactory;
import alluxio.wire.BlockInfo;
import alluxio.wire.BlockLocation;
import alluxio.wire.WorkerInfo;
import alluxio.wire.WorkerNetAddress;

import com.codahale.metrics.Gauge;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.apache.thrift.TProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Function;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;

import alluxio.thrift.PersistFile;

/**
 * This block master manages the metadata for all the blocks and block workers in Alluxio.
 */
@NotThreadSafe // TODO(jiri): make thread-safe (c.f. ALLUXIO-1664)
public final class DefaultBlockMaster extends AbstractMaster implements BlockMaster {
  private static final Logger LOG = LoggerFactory.getLogger(DefaultBlockMaster.class);

  /**
   * The number of container ids to 'reserve' before having to journal container id state. This
   * allows the master to return container ids within the reservation, without having to write to
   * the journal.
   */
  private static final long CONTAINER_ID_RESERVATION_SIZE = 1000;

  // Worker metadata management.
  private static final IndexDefinition<MasterWorkerInfo> ID_INDEX =
      new IndexDefinition<MasterWorkerInfo>(true) {
        @Override
        public Object getFieldValue(MasterWorkerInfo o) {
          return o.getId();
        }
      };

  private static final IndexDefinition<MasterWorkerInfo> ADDRESS_INDEX =
      new IndexDefinition<MasterWorkerInfo>(true) {
        @Override
        public Object getFieldValue(MasterWorkerInfo o) {
          return o.getWorkerAddress();
        }
      };

  /**
   * Concurrency and locking in the BlockMaster
   *
   * The block master uses concurrent data structures to allow non-conflicting concurrent access.
   * This means each piece of metadata should be locked individually. There are two types of
   * metadata in the {@link DefaultBlockMaster}; {@link MasterBlockInfo} and
   * {@link MasterWorkerInfo}.
   * Individual objects must be locked before modifying the object, or reading a modifiable field
   * of an object. This will protect the internal integrity of the metadata object.
   *
   * Lock ordering must be preserved in order to prevent deadlock. If both a worker and block
   * metadata must be locked at the same time, the worker metadata ({@link MasterWorkerInfo})
   * must be locked before the block metadata ({@link MasterBlockInfo}).
   *
   * It should not be the case that multiple worker metadata must be locked at the same time, or
   * multiple block metadata must be locked at the same time. Operations involving different
   * workers or different blocks should be able to be performed independently.
   */

  // Block metadata management.
  /** Blocks on all workers, including active and lost blocks. This state must be journaled. */
  private final ConcurrentHashMap<Long, MasterBlockInfo> mBlocks =
      new ConcurrentHashMap<>(8192, 0.90f, 64);
  /** Keeps track of blocks which are no longer in Alluxio storage. */
  private final ConcurrentHashSet<Long> mLostBlocks = new ConcurrentHashSet<>(64, 0.90f, 64);

  /** This state must be journaled. */
  @GuardedBy("itself")
  private final BlockContainerIdGenerator mBlockContainerIdGenerator =
      new BlockContainerIdGenerator();

  /**
   * Mapping between all possible storage level aliases and their ordinal position. This mapping
   * forms a total ordering on all storage level aliases in the system, and must be consistent
   * across masters.
   */
  private StorageTierAssoc mGlobalStorageTierAssoc;

  /** Keeps track of workers which are in communication with the master. */
  private final IndexedSet<MasterWorkerInfo> mWorkers =
      new IndexedSet<>(ID_INDEX, ADDRESS_INDEX);
  /** Keeps track of workers which are no longer in communication with the master. */
  private final IndexedSet<MasterWorkerInfo> mLostWorkers =
      new IndexedSet<>(ID_INDEX, ADDRESS_INDEX);
  /** qiniu
   * getId() and registerWorker() is not in a same step so some other RPC may see inconsistent stat,
   * e.g, commitBlock() may hit a NullPointerException after getId() and before registerWorker().
   * Add a mTempWorkers to hold the IDS generated by getId() and moved to mWorkers after registered.
   */
  private final IndexedSet<MasterWorkerInfo> mTempWorkers =
      new IndexedSet<>(ID_INDEX, ADDRESS_INDEX);

  /**
   * The service that detects lost worker nodes, and tries to restart the failed workers.
   * We store it here so that it can be accessed from tests.
   */
  @SuppressFBWarnings("URF_UNREAD_FIELD")
  private Future<?> mLostWorkerDetectionService;

  /** The value of the 'next container id' last journaled. */
  @GuardedBy("mBlockContainerIdGenerator")
  private long mJournaledNextContainerId = 0;

  //qiniu worker -> array of files
  final static public int EVICT_EVICT = 0;
  final static public int EVICT_PERSIST = 1;
  final static public int EVICT_FREE = 2;
  static private Map <Long, Map<Long, PersistFile> > mEvictEvict = new ConcurrentHashMap<>();
  static private Map <Long, Map<Long, PersistFile> > mEvictPersist = new ConcurrentHashMap<>();
  static private Map <Long, Map<Long, PersistFile> > mEvictFree = new ConcurrentHashMap<>();

  // need to synchronized
  static public Map<Long, PersistFile> getEvictFileMap(int type, long worker) {
      Map <Long, Map<Long, PersistFile> > m = (EVICT_EVICT == type) ? mEvictEvict : 
                            (( EVICT_PERSIST == type) ? mEvictPersist : mEvictFree);
      Map<Long, PersistFile> l = m.get(worker);
      if (l == null) {
          l = new ConcurrentHashMap<Long, PersistFile>();
          m.put(worker, l);
      }
      return l;
  }

  // add a place-hold PersistFile first, add blocks later
  static public boolean addEvictFile(int type, long worker, PersistFile file) {
      Map<Long, PersistFile> m = getEvictFileMap(type, worker);
      if (m.get(file.getFileId()) != null) {
          LOG.debug("===== EVICT[INFO] file already added:" + file.getFileId());
          return false;
      }

      m.put(file.getFileId(), file);
      return true;
  }

  static public long getEvictFileCnt(int type, long worker) {
      Map <Long, Map<Long, PersistFile> > m = (EVICT_EVICT == type) ? mEvictEvict : 
                            (( EVICT_PERSIST == type) ? mEvictPersist : mEvictFree);
      long count = 0;
      for (Map.Entry<Long, Map<Long, PersistFile>> entry : m.entrySet()) {
          if (worker == IdUtils.INVALID_WORKER_ID || worker == entry.getKey()) {
              count += entry.getValue().size();
          }
      }
      return count;
  }

  /**
   * Creates a new instance of {@link DefaultBlockMaster}.
   *
   * @param masterContext the context for Alluxio master
   */
  DefaultBlockMaster(MasterContext masterContext) {
    this(masterContext, new SystemClock(), ExecutorServiceFactories
        .fixedThreadPoolExecutorServiceFactory(Constants.BLOCK_MASTER_NAME, 2));
  }

  /**
   * Creates a new instance of {@link DefaultBlockMaster}.
   *
   * @param masterContext the context for Alluxio master
   * @param clock the clock to use for determining the time
   * @param executorServiceFactory a factory for creating the executor service to use for running
   *        maintenance threads
   */
  DefaultBlockMaster(MasterContext masterContext, Clock clock,
      ExecutorServiceFactory executorServiceFactory) {
    super(masterContext, clock, executorServiceFactory);
    Metrics.registerGauges(this);
  }

  @Override
  public Map<String, TProcessor> getServices() {
    Map<String, TProcessor> services = new HashMap<>();
    services.put(Constants.BLOCK_MASTER_CLIENT_SERVICE_NAME,
        new BlockMasterClientService.Processor<>(new BlockMasterClientServiceHandler(this)));
    services.put(Constants.BLOCK_MASTER_WORKER_SERVICE_NAME,
        new BlockMasterWorkerService.Processor<>(new BlockMasterWorkerServiceHandler(this)));
    return services;
  }

  @Override
  public String getName() {
    return Constants.BLOCK_MASTER_NAME;
  }

  @Override
  public void processJournalEntry(JournalEntry entry) throws IOException {
    // TODO(gene): A better way to process entries besides a huge switch?
    if (entry.hasBlockContainerIdGenerator()) {
      mJournaledNextContainerId = (entry.getBlockContainerIdGenerator()).getNextContainerId();
      mBlockContainerIdGenerator.setNextContainerId((mJournaledNextContainerId));
    } else if (entry.hasDeleteBlock()) {
      mBlocks.remove(entry.getDeleteBlock().getBlockId());
    } else if (entry.hasBlockInfo()) {
      BlockInfoEntry blockInfoEntry = entry.getBlockInfo();
      if (mBlocks.containsKey(blockInfoEntry.getBlockId())) {
        // Update the existing block info.
        MasterBlockInfo blockInfo = mBlocks.get(blockInfoEntry.getBlockId());
        blockInfo.updateLength(blockInfoEntry.getLength());
      } else {
        mBlocks.put(blockInfoEntry.getBlockId(), new MasterBlockInfo(blockInfoEntry.getBlockId(),
            blockInfoEntry.getLength()));
      }
    } else {
      throw new IOException(ExceptionMessage.UNEXPECTED_JOURNAL_ENTRY.getMessage(entry));
    }
  }

  @Override
  public void resetState() {
    mBlocks.clear();
    mJournaledNextContainerId = 0;
    mBlockContainerIdGenerator.setNextContainerId(0);
  }

  @Override
  public Iterator<JournalEntry> getJournalEntryIterator() {
    final Iterator<MasterBlockInfo> it = mBlocks.values().iterator();
    Iterator<JournalEntry> blockIterator = new Iterator<JournalEntry>() {
      @Override
      public boolean hasNext() {
        return it.hasNext();
      }

      @Override
      public JournalEntry next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        MasterBlockInfo info = it.next();
        BlockInfoEntry blockInfoEntry =
            BlockInfoEntry.newBuilder().setBlockId(info.getBlockId())
                .setLength(info.getLength()).build();
        return JournalEntry.newBuilder().setBlockInfo(blockInfoEntry).build();
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException("BlockMaster#Iterator#remove is not supported.");
      }
    };

    return Iterators
        .concat(CommonUtils.singleElementIterator(getContainerIdJournalEntry()), blockIterator);
  }

  @Override
  public void start(Boolean isLeader) throws IOException {
    super.start(isLeader);
    mGlobalStorageTierAssoc = new MasterStorageTierAssoc();
    if (isLeader) {
      mLostWorkerDetectionService = getExecutorService().submit(new HeartbeatThread(
          HeartbeatContext.MASTER_LOST_WORKER_DETECTION, new LostWorkerDetectionHeartbeatExecutor(),
          (int) Configuration.getMs(PropertyKey.MASTER_HEARTBEAT_INTERVAL_MS)));
    }
  }

  @Override
  public int getWorkerCount() {
    return mWorkers.size();
  }

  @Override
  public List<WorkerInfo> getWorkerInfoList() throws UnavailableException {
    if (mSafeModeManager.isInSafeMode()) {
      throw new UnavailableException(ExceptionMessage.MASTER_IN_SAFEMODE.getMessage());
    }
    List<WorkerInfo> workerInfoList = new ArrayList<>(mWorkers.size());
    for (MasterWorkerInfo worker : mWorkers) {
      synchronized (worker) {
        workerInfoList.add(worker.generateClientWorkerInfo());
      }
    }
    return workerInfoList;
  }

  @Override
  public long getCapacityBytes() {
    long ret = 0;
    for (MasterWorkerInfo worker : mWorkers) {
      synchronized (worker) {
        ret += worker.getCapacityBytes();
      }
    }
    return ret;
  }

  @Override
  public StorageTierAssoc getGlobalStorageTierAssoc() {
    return mGlobalStorageTierAssoc;
  }

  @Override
  public long getUsedBytes() {
    long ret = 0;
    for (MasterWorkerInfo worker : mWorkers) {
      synchronized (worker) {
        ret += worker.getUsedBytes();
      }
    }
    return ret;
  }

  @Override
  public List<WorkerInfo> getLostWorkersInfoList() {
    List<WorkerInfo> ret = new ArrayList<>(mLostWorkers.size());
    for (MasterWorkerInfo worker : mLostWorkers) {
      synchronized (worker) {
        ret.add(worker.generateClientWorkerInfo());
      }
    }
    Collections.sort(ret, new WorkerInfo.LastContactSecComparator());
    return ret;
  }

  @Override
  public void removeBlocks(List<Long> blockIds, boolean delete) throws UnavailableException {
    try (JournalContext journalContext = createJournalContext()) {
      for (long blockId : blockIds) {
        MasterBlockInfo block = mBlocks.get(blockId);
        if (block == null) {
          continue;
        }
        HashSet<Long> workerIds = new HashSet<>();

        synchronized (block) {
          // Technically, 'block' should be confirmed to still be in the data structure. A
          // concurrent removeBlock call can remove it. However, we are intentionally ignoring this
          // race, since deleting the same block again is a noop.
          workerIds.addAll(block.getWorkers());
          // Two cases here:
          // 1) For delete: delete the block metadata.
          // 2) For free: keep the block metadata. mLostBlocks will be changed in
          // processWorkerRemovedBlocks
          if (delete) {
            // Make sure blockId is removed from mLostBlocks when the block metadata is deleted.
            // Otherwise blockId in mLostBlock can be dangling index if the metadata is gone.
            mLostBlocks.remove(blockId);
            if (mBlocks.remove(blockId) != null) {
              JournalEntry entry = JournalEntry.newBuilder()
                  .setDeleteBlock(DeleteBlockEntry.newBuilder().setBlockId(blockId)).build();
              journalContext.append(entry);
            }
          }
        }

        // Outside of locking the block. This does not have to be synchronized with the block
        // metadata, since it is essentially an asynchronous signal to the worker to remove the
        // block.
        for (long workerId : workerIds) {
          MasterWorkerInfo worker = mWorkers.getFirstByField(ID_INDEX, workerId);
          if (worker != null) {
            synchronized (worker) {
              worker.updateToRemovedBlock(true, blockId);
            }
          }
        }
      }
    }
  }

  @Override
  public void validateBlocks(Function<Long, Boolean> validator, boolean repair)
      throws UnavailableException {
    List<Long> invalidBlocks = new ArrayList<>();
    for (long blockId : mBlocks.keySet()) {
      if (!validator.apply(blockId)) {
        invalidBlocks.add(blockId);
      }
    }
    if (repair && !invalidBlocks.isEmpty()) {
      LOG.warn("Deleting {} invalid blocks.", invalidBlocks.size());
      removeBlocks(invalidBlocks, true);
    }
  }

  /**
   * @return a new block container id
   */
  @Override
  public long getNewContainerId() throws UnavailableException {
    synchronized (mBlockContainerIdGenerator) {
      long containerId = mBlockContainerIdGenerator.getNewContainerId();
      if (containerId < mJournaledNextContainerId) {
        // This container id is within the reserved container ids, so it is safe to return the id
        // without having to write anything to the journal.
        return containerId;
      }
      // This container id is not safe with respect to the last journaled container id.
      // Therefore, journal the new state of the container id. This implies that when a master
      // crashes, the container ids within the reservation which have not been used yet will
      // never be used. This is a tradeoff between fully utilizing the container id space, vs.
      // improving master scalability.
      // TODO(gpang): investigate if dynamic reservation sizes could be effective

      // Set the next id to journal with a reservation of container ids, to avoid having to write
      // to the journal for ids within the reservation.
      mJournaledNextContainerId = containerId + CONTAINER_ID_RESERVATION_SIZE;
      try (JournalContext journalContext = createJournalContext()) {
        // This must be flushed while holding the lock on mBlockContainerIdGenerator, in order to
        // prevent subsequent calls to return ids that have not been journaled and flushed.
        journalContext.append(getContainerIdJournalEntry());
      }
      return containerId;
    }
  }

  /**
   * @return a {@link JournalEntry} representing the state of the container id generator
   */
  private JournalEntry getContainerIdJournalEntry() {
    BlockContainerIdGeneratorEntry blockContainerIdGenerator =
        BlockContainerIdGeneratorEntry.newBuilder().setNextContainerId(mJournaledNextContainerId)
            .build();
    return JournalEntry.newBuilder().setBlockContainerIdGenerator(blockContainerIdGenerator)
        .build();
  }

  // TODO(binfan): check the logic is correct or not when commitBlock is a retry
  @Override
  public void commitBlock(long workerId, long usedBytesOnTier, String tierAlias, long blockId,
      long length) throws NoWorkerException, UnavailableException {
    LOG.debug("Commit block from workerId: {}, usedBytesOnTier: {}, blockId: {}, length: {}",
        workerId, usedBytesOnTier, blockId, length);

    MasterWorkerInfo worker = mWorkers.getFirstByField(ID_INDEX, workerId);
    // TODO(peis): Check lost workers as well.
    if (worker == null) {
      throw new NoWorkerException(ExceptionMessage.NO_WORKER_FOUND.getMessage(workerId));
    }

    // Lock the worker metadata first.
    try (JournalContext journalContext = createJournalContext()) {
      synchronized (worker) {
        // Loop until block metadata is successfully locked.
        while (true) {
          boolean newBlock = false;
          MasterBlockInfo block = mBlocks.get(blockId);
          if (block == null) {
            // The block metadata doesn't exist yet.
            block = new MasterBlockInfo(blockId, length);
            newBlock = true;
          }

          // Lock the block metadata.
          synchronized (block) {
            boolean writeJournal = false;
            if (newBlock) {
              if (mBlocks.putIfAbsent(blockId, block) != null) {
                // Another thread already inserted the metadata for this block, so start loop over.
                continue;
              }
              // Successfully added the new block metadata. Append a journal entry for the new
              // metadata.
              writeJournal = true;
            } else if (block.getLength() != length && block.getLength() == Constants.UNKNOWN_SIZE) {
              // The block size was previously unknown. Update the block size with the committed
              // size, and append a journal entry.
              block.updateLength(length);
              writeJournal = true;
            }
            if (writeJournal) {
              BlockInfoEntry blockInfo =
                  BlockInfoEntry.newBuilder().setBlockId(blockId).setLength(length).build();
              journalContext.append(JournalEntry.newBuilder().setBlockInfo(blockInfo).build());
            }
            // At this point, both the worker and the block metadata are locked.

            // Update the block metadata with the new worker location.
            block.addWorker(workerId, tierAlias);
            // This worker has this block, so it is no longer lost.
            mLostBlocks.remove(blockId);

            // Update the worker information for this new block.
            // TODO(binfan): when retry commitBlock on master is expected, make sure metrics are not
            // double counted.
            worker.addBlock(blockId);
            worker.updateUsedBytes(tierAlias, usedBytesOnTier);
            worker.updateLastUpdatedTimeMs();
          }
          break;
        }
      }
    }
  }

  @Override
  public void commitBlockInUFS(long blockId, long length) throws UnavailableException {
    LOG.debug("Commit block in ufs. blockId: {}, length: {}", blockId, length);
    if (mBlocks.get(blockId) != null) {
      // Block metadata already exists, so do not need to create a new one.
      return;
    }

    // The block has not been committed previously, so add the metadata to commit the block.
    MasterBlockInfo block = new MasterBlockInfo(blockId, length);
    try (JournalContext journalContext = createJournalContext()) {
      synchronized (block) {
        if (mBlocks.putIfAbsent(blockId, block) == null) {
          // Successfully added the new block metadata. Append a journal entry for the new metadata.
          BlockInfoEntry blockInfo =
              BlockInfoEntry.newBuilder().setBlockId(blockId).setLength(length).build();
          journalContext.append(JournalEntry.newBuilder().setBlockInfo(blockInfo).build());
        }
      }
    }
  }

  @Override
  public BlockInfo getBlockInfo(long blockId) throws BlockInfoException, UnavailableException {
    MasterBlockInfo block = mBlocks.get(blockId);
    if (block == null) {
      throw new BlockInfoException(ExceptionMessage.BLOCK_META_NOT_FOUND, blockId);
    }
    synchronized (block) {
      return generateBlockInfo(block);
    }
  }

  @Override
  public List<BlockInfo> getBlockInfoList(List<Long> blockIds) throws UnavailableException {
    List<BlockInfo> ret = new ArrayList<>(blockIds.size());
    for (long blockId : blockIds) {
      MasterBlockInfo block = mBlocks.get(blockId);
      if (block == null) {
        continue;
      }
      synchronized (block) {
        ret.add(generateBlockInfo(block));
      }
    }
    return ret;
  }

  @Override
  public Map<String, Long> getTotalBytesOnTiers() {
    Map<String, Long> ret = new HashMap<>();
    for (MasterWorkerInfo worker : mWorkers) {
      synchronized (worker) {
        for (Map.Entry<String, Long> entry : worker.getTotalBytesOnTiers().entrySet()) {
          Long total = ret.get(entry.getKey());
          ret.put(entry.getKey(), (total == null ? 0L : total) + entry.getValue());
        }
      }
    }
    return ret;
  }

  @Override
  public Map<String, Long> getUsedBytesOnTiers() {
    Map<String, Long> ret = new HashMap<>();
    for (MasterWorkerInfo worker : mWorkers) {
      synchronized (worker) {
        for (Map.Entry<String, Long> entry : worker.getUsedBytesOnTiers().entrySet()) {
          Long used = ret.get(entry.getKey());
          ret.put(entry.getKey(), (used == null ? 0L : used) + entry.getValue());
        }
      }
    }
    return ret;
  }

  @Override
  public long getWorkerId(WorkerNetAddress workerNetAddress) {
    // TODO(gpang): Clone WorkerNetAddress in case thrift re-uses the object. Does thrift re-use it?
    MasterWorkerInfo existingWorker = mWorkers.getFirstByField(ADDRESS_INDEX, workerNetAddress);
    if (existingWorker != null) {
      // This worker address is already mapped to a worker id.
      long oldWorkerId = existingWorker.getId();
      LOG.warn("The worker {} already exists as id {}.", workerNetAddress, oldWorkerId);
      return oldWorkerId;
    }

    MasterWorkerInfo tempWorker = mTempWorkers.getFirstByField(ADDRESS_INDEX, workerNetAddress);
    if (tempWorker != null) {
      // this is one of the temp workers
      synchronized (tempWorker) {
        final long tempWorkerId = tempWorker.getId();
        LOG.warn("A temp worker {} has requested its teemp id {}.", workerNetAddress, tempWorkerId);

        // Update the timestamp of the worker before it is considered an active worker.
        tempWorker.updateLastUpdatedTimeMs();
        mWorkers.add(tempWorker);
        mLostWorkers.remove(tempWorker);
        return tempWorkerId;
      }
    }

    MasterWorkerInfo lostWorker = mLostWorkers.getFirstByField(ADDRESS_INDEX, workerNetAddress);
    if (lostWorker != null) {
      // this is one of the lost workers
      synchronized (lostWorker) {
        final long lostWorkerId = lostWorker.getId();
        LOG.warn("A lost worker {} has requested its old id {}.", workerNetAddress, lostWorkerId);

        // Update the timestamp of the worker before it is considered an active worker.
        lostWorker.updateLastUpdatedTimeMs();
        mWorkers.add(lostWorker);
        mLostWorkers.remove(lostWorker);
        return lostWorkerId;
      }
    }

    // Generate a new worker id.
    long workerId = IdUtils.getRandomNonNegativeLong();
    //while (!mWorkers.add(new MasterWorkerInfo(workerId, workerNetAddress))) {
    while (!mTempWorkers.add(new MasterWorkerInfo(workerId, workerNetAddress))) {
      workerId = IdUtils.getRandomNonNegativeLong();
    }

    LOG.info("getWorkerId(): WorkerNetAddress: {} id: {}", workerNetAddress, workerId);
    return workerId;
  }

  @Override
  public void workerRegister(long workerId, List<String> storageTiers,
      Map<String, Long> totalBytesOnTiers, Map<String, Long> usedBytesOnTiers,
      Map<String, List<Long>> currentBlocksOnTiers) throws NoWorkerException {
    MasterWorkerInfo worker = mWorkers.getFirstByField(ID_INDEX, workerId);
    if (worker == null) {
        worker = mTempWorkers.getFirstByField(ID_INDEX, workerId);
        if (worker != null) {
            mWorkers.add(worker);
            mTempWorkers.remove(worker);
            LOG.info("=== register worker id {} from tmp {}", worker.getId(), worker.getWorkerAddress());
        }
    }
    if (worker == null) {
      throw new NoWorkerException(ExceptionMessage.NO_WORKER_FOUND.getMessage(workerId));
    }

    // Gather all blocks on this worker.
    HashSet<Long> blocks = new HashSet<>();
    for (List<Long> blockIds : currentBlocksOnTiers.values()) {
      blocks.addAll(blockIds);
    }

    synchronized (worker) {
      worker.updateLastUpdatedTimeMs();
      // Detect any lost blocks on this worker.
      Set<Long> removedBlocks = worker.register(mGlobalStorageTierAssoc, storageTiers,
          totalBytesOnTiers, usedBytesOnTiers, blocks);
      processWorkerRemovedBlocks(worker, removedBlocks);
      processWorkerAddedBlocks(worker, currentBlocksOnTiers);
      processWorkerOrphanedBlocks(worker);
      LOG.info("registerWorker(): id:{}, adr:{}, cap:{}, avail:{}, total_tier:{}, free_tier:{}, blocks:{}, to_remove:{}", 
        worker.getId(), worker.getWorkerAddress(), worker.getCapacityBytes(), 
        worker.getAvailableBytes(), worker.getTotalBytesOnTiers(), worker.getFreeBytesOnTiers(), 
        worker.getBlocks().size(), worker.getToRemoveBlocks().size());
    }
  }

  @Override
  public Command workerHeartbeat(long workerId, Map<String, Long> usedBytesOnTiers,
      List<Long> removedBlockIds, Map<String, List<Long>> addedBlocksOnTiers) {
    MasterWorkerInfo worker = mWorkers.getFirstByField(ID_INDEX, workerId);
    if (worker == null) {
      LOG.warn("Could not find worker id: {} for heartbeat.", workerId);
      return new Command(CommandType.Register, new ArrayList<Long>());
    }

    synchronized (worker) {
      // Technically, 'worker' should be confirmed to still be in the data structure. Lost worker
      // detection can remove it. However, we are intentionally ignoring this race, since the worker
      // will just re-register regardless.
     
      // qiniu: don't want to change thrift (see its warning), so use '0' to delimit the
      // to_be_remove and already_removed block ids
      Map<Long, PersistFile> evictingFiles = new HashMap<Long, PersistFile>();
      for (int i = 0; i < removedBlockIds.size(); i++) {
          if (removedBlockIds.get(i) == 0) {
              for (int j = 0; j < i; j++) {
                  long blockId = removedBlockIds.get(0);
                  long containerId = BlockId.getContainerId(blockId);
                  long fileId = IdUtils.createFileId(containerId);
                  PersistFile pf = evictingFiles.get(fileId);
                  if (pf == null) {
                      pf = new PersistFile(fileId, new ArrayList<Long>());
                      evictingFiles.put(fileId, pf);
                  }
                  pf.getBlockIds().add(blockId);
                  removedBlockIds.remove(0);
              }
              removedBlockIds.remove(0);
              break;
          }
      }
      /** qiniu
       * we now add to queue for workerId without blocks, but may move to another worker queue later
       * sicne we can't get file info in the block context.
       * we will let the worker with first block to handle persist to avoid duplication.
       */
      for (PersistFile pf: evictingFiles.values()) {
          DefaultBlockMaster.addEvictFile(DefaultBlockMaster.EVICT_EVICT, workerId, pf);
      }

      processWorkerRemovedBlocks(worker, removedBlockIds);
      processWorkerAddedBlocks(worker, addedBlocksOnTiers);

      worker.updateUsedBytes(usedBytesOnTiers);
      worker.updateLastUpdatedTimeMs();

      List<Long> toRemoveBlocks = worker.getToRemoveBlocks();

      // qiniu
      ArrayList<Long> ids = new ArrayList<Long>();
      Map<Long, PersistFile> frees = DefaultBlockMaster.getEvictFileMap(DefaultBlockMaster.EVICT_FREE, workerId);
      Iterator<Map.Entry<Long, PersistFile>> it = frees.entrySet().iterator();
      while (it.hasNext()) {
          PersistFile f = it.next().getValue();
          LOG.debug("===== EVICT[FREE] worker:" + workerId + " file:" + f.getFileId() + " blocks:" + f.getBlockIds());
          ids.addAll(f.getBlockIds());
      }
      frees.clear();

      toRemoveBlocks.addAll(ids);

      if (toRemoveBlocks.isEmpty()) {
        return new Command(CommandType.Nothing, new ArrayList<Long>());
      }
      return new Command(CommandType.Free, toRemoveBlocks);
    }
  }

  /**
   * Updates the worker and block metadata for blocks removed from a worker.
   *
   * @param workerInfo The worker metadata object
   * @param removedBlockIds A list of block ids removed from the worker
   */
  @GuardedBy("workerInfo")
  private void processWorkerRemovedBlocks(MasterWorkerInfo workerInfo,
      Collection<Long> removedBlockIds) {
    for (long removedBlockId : removedBlockIds) {
      MasterBlockInfo block = mBlocks.get(removedBlockId);
      // TODO(calvin): Investigate if this branching logic can be simplified.
      if (block == null) {
        // LOG.warn("Worker {} informs the removed block {}, but block metadata does not exist"
        //    + " on Master!", workerInfo.getId(), removedBlockId);
        // TODO(pfxuan): [ALLUXIO-1804] should find a better way to handle the removed blocks.
        // Ideally, the delete/free I/O flow should never reach this point. Because Master may
        // update the block metadata only after receiving the acknowledgement from Workers.
        workerInfo.removeBlock(removedBlockId);
        // Continue to remove the remaining blocks.
        continue;
      }
      synchronized (block) {
        LOG.info("Block {} is removed on worker {}.", removedBlockId, workerInfo.getId());
        workerInfo.removeBlock(block.getBlockId());
        block.removeWorker(workerInfo.getId());
        if (block.getNumLocations() == 0) {
          mLostBlocks.add(removedBlockId);
        }
      }
    }
  }

  /**
   * Updates the worker and block metadata for blocks added to a worker.
   *
   * @param workerInfo The worker metadata object
   * @param addedBlockIds A mapping from storage tier alias to a list of block ids added
   */
  @GuardedBy("workerInfo")
  private void processWorkerAddedBlocks(MasterWorkerInfo workerInfo,
      Map<String, List<Long>> addedBlockIds) {
    for (Map.Entry<String, List<Long>> entry : addedBlockIds.entrySet()) {
      for (long blockId : entry.getValue()) {
        MasterBlockInfo block = mBlocks.get(blockId);
        if (block != null) {
          synchronized (block) {
            workerInfo.addBlock(blockId);
            block.addWorker(workerInfo.getId(), entry.getKey());
            mLostBlocks.remove(blockId);
          }
        } else {
          LOG.warn("Invalid block: {} from worker {}.", blockId,
              workerInfo.getWorkerAddress().getHost());
        }
      }
    }
  }

  @GuardedBy("workerInfo")
  private void processWorkerOrphanedBlocks(MasterWorkerInfo workerInfo) {
    for (long block : workerInfo.getBlocks()) {
      if (!mBlocks.containsKey(block)) {
        LOG.info("Requesting delete for orphaned block: {} from worker {}.", block,
            workerInfo.getWorkerAddress().getHost());
        workerInfo.updateToRemovedBlock(true, block);
      }
    }
  }

  @Override
  public Set<Long> getLostBlocks() {
    return ImmutableSet.copyOf(mLostBlocks);
  }

  /**
   * Creates a {@link BlockInfo} form a given {@link MasterBlockInfo}, by populating worker
   * locations.
   *
   * @param masterBlockInfo the {@link MasterBlockInfo}
   * @return a {@link BlockInfo} from a {@link MasterBlockInfo}. Populates worker locations
   */
  @GuardedBy("masterBlockInfo")
  private BlockInfo generateBlockInfo(MasterBlockInfo masterBlockInfo) throws UnavailableException {
    if (mSafeModeManager.isInSafeMode()) {
      throw new UnavailableException(ExceptionMessage.MASTER_IN_SAFEMODE.getMessage());
    }
    // "Join" to get all the addresses of the workers.
    List<BlockLocation> locations = new ArrayList<>();
    List<MasterBlockLocation> blockLocations = masterBlockInfo.getBlockLocations();
    // Sort the block locations by their alias ordinal in the master storage tier mapping
    Collections.sort(blockLocations, new Comparator<MasterBlockLocation>() {
      @Override
      public int compare(MasterBlockLocation o1, MasterBlockLocation o2) {
        return mGlobalStorageTierAssoc.getOrdinal(o1.getTierAlias())
            - mGlobalStorageTierAssoc.getOrdinal(o2.getTierAlias());
      }
    });
    for (MasterBlockLocation masterBlockLocation : blockLocations) {
      MasterWorkerInfo workerInfo =
          mWorkers.getFirstByField(ID_INDEX, masterBlockLocation.getWorkerId());
      if (workerInfo != null) {
        // worker metadata is intentionally not locked here because:
        // - it would be an incorrect order (correct order is lock worker first, then block)
        // - only uses getters of final variables
        locations.add(new BlockLocation().setWorkerId(masterBlockLocation.getWorkerId())
            .setWorkerAddress(workerInfo.getWorkerAddress())
            .setTierAlias(masterBlockLocation.getTierAlias()));
      }
    }
    return new BlockInfo().setBlockId(masterBlockInfo.getBlockId())
        .setLength(masterBlockInfo.getLength()).setLocations(locations);
  }

  @Override
  public void reportLostBlocks(List<Long> blockIds) {
    mLostBlocks.addAll(blockIds);
  }

  /**
   * Lost worker periodic check.
   */
  private final class LostWorkerDetectionHeartbeatExecutor implements HeartbeatExecutor {

    /**
     * Constructs a new {@link LostWorkerDetectionHeartbeatExecutor}.
     */
    public LostWorkerDetectionHeartbeatExecutor() {}

    @Override
    public void heartbeat() {
      int masterWorkerTimeoutMs = (int) Configuration.getMs(PropertyKey.MASTER_WORKER_TIMEOUT_MS);
      for (MasterWorkerInfo worker : mWorkers) {
        synchronized (worker) {
          final long lastUpdate = mClock.millis() - worker.getLastUpdatedTimeMs();
          if (lastUpdate > masterWorkerTimeoutMs) {
            LOG.error("The worker {}({}) timed out after {}ms without a heartbeat!", worker.getId(),
                worker.getWorkerAddress(), lastUpdate);
            mLostWorkers.add(worker);
            mWorkers.remove(worker);
            processWorkerRemovedBlocks(worker, worker.getBlocks());
          }
        }
      }
    }

    @Override
    public void close() {
      // Nothing to clean up
    }
  }

  /**
   * Class that contains metrics related to BlockMaster.
   */
  public static final class Metrics {
    public static final String CAPACITY_TOTAL = "CapacityTotal";
    public static final String CAPACITY_USED = "CapacityUsed";
    public static final String CAPACITY_FREE = "CapacityFree";
    public static final String WORKERS = "Workers";

    private static void registerGauges(final BlockMaster master) {
      MetricsSystem.registerGaugeIfAbsent(MetricsSystem.getMasterMetricName(CAPACITY_TOTAL),
          new Gauge<Long>() {
            @Override
            public Long getValue() {
              return master.getCapacityBytes();
            }
          });

      MetricsSystem.registerGaugeIfAbsent(MetricsSystem.getMasterMetricName(CAPACITY_USED),
          new Gauge<Long>() {
            @Override
            public Long getValue() {
              return master.getUsedBytes();
            }
          });

      MetricsSystem.registerGaugeIfAbsent(MetricsSystem.getMasterMetricName(CAPACITY_FREE),
          new Gauge<Long>() {
            @Override
            public Long getValue() {
              return master.getCapacityBytes() - master.getUsedBytes();
            }
          });

      MetricsSystem.registerGaugeIfAbsent(MetricsSystem.getMasterMetricName(WORKERS),
          new Gauge<Integer>() {
            @Override
            public Integer getValue() {
              return master.getWorkerCount();
            }
          });
    }

    private Metrics() {} // prevent instantiation
  }
}
