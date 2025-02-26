/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.raid;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.raid.protocol.PolicyInfo;
import org.apache.hadoop.raid.protocol.PolicyList;
import org.apache.hadoop.raid.protocol.RaidProtocol;
import org.apache.hadoop.util.Daemon;
import org.apache.hadoop.util.StringUtils;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * A {@link RaidNode} that implements 
 */
public class RaidNode implements RaidProtocol {

  static{
    Configuration.addDefaultResource("hdfs-default.xml");
    Configuration.addDefaultResource("hdfs-site.xml");
    Configuration.addDefaultResource("mapred-default.xml");
    Configuration.addDefaultResource("mapred-site.xml");
  }

  public static final Log LOG = LogFactory.getLog(RaidNode.class.getName());
  public static final long SLEEP_TIME = 10000L; // 10 seconds
  public static final int DEFAULT_PORT = 60000;
  public static final int DEFAULT_STRIPE_LENGTH = 5; // default value of stripe length
  public static final String DEFAULT_RAID_LOCATION = "/raid";

  /** RPC server */
  private Server server;
  /** RPC server address */
  private InetSocketAddress serverAddress = null;
  /** only used for testing purposes  */
  private boolean stopRequested = false;

  /** Configuration Manager */
  private ConfigManager configMgr;

  /** hadoop configuration */
  private Configuration conf;

  protected boolean initialized;  // Are we initialized?
  protected volatile boolean running; // Are we running?

  /** Deamon thread to trigger policies */
  Daemon triggerThread = null;

  /** Deamon thread to delete obsolete parity files */
  Daemon purgeThread = null;

  /** Do do distributed raiding */
  boolean isRaidLocal = false;
  
  // statistics about RAW hdfs blocks. This counts all replicas of a block.
  public static class Statistics {
    long numProcessedBlocks; // total blocks encountered in namespace
    long processedSize;   // disk space occupied by all blocks
    long remainingSize;      // total disk space post RAID
    
    long numMetaBlocks;      // total blocks in metafile
    long metaSize;           // total disk space for meta files

    public void clear() {
    	numProcessedBlocks = 0;
    	processedSize = 0;
    	remainingSize = 0;
    	numMetaBlocks = 0;
    	metaSize = 0;
    }
    public String toString() {
      long save = processedSize - (remainingSize + metaSize);
      long savep = 0;
      if (processedSize > 0) {
        savep = (save * 100)/processedSize;
      }
      String msg = " numProcessedBlocks = " + numProcessedBlocks +
                   " processedSize = " + processedSize +
                   " postRaidSize = " + remainingSize +
                   " numMetaBlocks = " + numMetaBlocks +
                   " metaSize = " + metaSize +
                   " %save in raw disk space = " + savep;
      return msg;
    }
  }

  // Startup options
  static public enum StartupOption{
    TEST ("-test"),
    REGULAR ("-regular");

    private String name = null;
    private StartupOption(String arg) {this.name = arg;}
    public String getName() {return name;}
  }
  
  /**
   * Start RaidNode.
   * <p>
   * The raid-node can be started with one of the following startup options:
   * <ul> 
   * <li>{@link StartupOption#REGULAR REGULAR} - normal raid node startup</li>
   * </ul>
   * The option is passed via configuration field: 
   * <tt>fs.raidnode.startup</tt>
   * 
   * The conf will be modified to reflect the actual ports on which 
   * the RaidNode is up and running if the user passes the port as
   * <code>zero</code> in the conf.
   * 
   * @param conf  confirguration
   * @throws IOException
   */

  RaidNode(Configuration conf) throws IOException {
    try {
      initialize(conf);
    } catch (IOException e) {
      this.stop();
      throw e;
    } catch (Exception e) {
      this.stop();
      throw new IOException(e);
    }
  }

  public long getProtocolVersion(String protocol,
                                 long clientVersion) throws IOException {
    if (protocol.equals(RaidProtocol.class.getName())) {
      return RaidProtocol.versionID;
    } else {
      throw new IOException("Unknown protocol to name node: " + protocol);
    }
  }

  /**
   * Wait for service to finish.
   * (Normally, it runs forever.)
   */
  public void join() {
    try {
      if (server != null) server.join();
      if (triggerThread != null) triggerThread.join();
      if (purgeThread != null) purgeThread.join();
    } catch (InterruptedException ie) {
      // do nothing
    }
  }
  
  /**
   * Stop all RaidNode threads and wait for all to finish.
   */
  public void stop() {
    if (stopRequested) {
      return;
    }
    stopRequested = true;
    running = false;
    if (server != null) server.stop();
    if (triggerThread != null) triggerThread.interrupt();
    if (purgeThread != null) purgeThread.interrupt();
  }

  private static InetSocketAddress getAddress(String address) {
    return NetUtils.createSocketAddr(address);
  }

  public static InetSocketAddress getAddress(Configuration conf) {
    String nodeport = conf.get("raid.server.address");
    if (nodeport == null) {
      nodeport = "localhost:" + DEFAULT_PORT;
    }
    return getAddress(nodeport);
  }


  private void initialize(Configuration conf) 
    throws IOException, SAXException, InterruptedException, RaidConfigurationException,
           ClassNotFoundException, ParserConfigurationException {
    this.conf = conf;
    InetSocketAddress socAddr = RaidNode.getAddress(conf);
    int handlerCount = conf.getInt("fs.raidnode.handler.count", 10);

    isRaidLocal = conf.getBoolean("fs.raidnode.local", false);
    // read in the configuration
    configMgr = new ConfigManager(conf);

    // create rpc server 
    this.server = RPC.getServer(this, socAddr.getHostName(), socAddr.getPort(),
                                handlerCount, false, conf);

    // The rpc-server port can be ephemeral... ensure we have the correct info
    this.serverAddress = this.server.getListenerAddress();
    LOG.info("RaidNode up at: " + this.serverAddress);

    initialized = true;
    running = true;
    this.server.start(); // start RPC server

    // start the deamon thread to fire polcies appropriately
    this.triggerThread = new Daemon(new TriggerMonitor());
    this.triggerThread.start();

    // start the thread that deletes obsolete parity files
    this.purgeThread = new Daemon(new PurgeMonitor());
    this.purgeThread.start();
  }

  /**
   * Implement RaidProtocol methods
   */

  /** {@inheritDoc} */
  public PolicyList[] getAllPolicies() throws IOException {
    Collection<PolicyList> list = configMgr.getAllPolicies();
    return list.toArray(new PolicyList[list.size()]);
  }

  /** {@inheritDoc} */
  public ReturnStatus recoverFile(String inStr, long corruptOffset) throws IOException {

    LOG.info("Recover File for " + inStr + " for corrupt offset " + corruptOffset);
    Path inputPath = new Path(inStr);
    Path srcPath = inputPath.makeQualified(inputPath.getFileSystem(conf));
    PolicyInfo info = findMatchingPolicy(srcPath);
    if (info != null) {

      // find stripe length from config
      int stripeLength = getStripeLength(conf, info);

      // create destination path prefix
      String destPrefix = getDestinationPath(conf, info);
      Path destPath = new Path(destPrefix.trim());
      FileSystem fs = FileSystem.get(destPath.toUri(), conf);
      destPath = destPath.makeQualified(fs);

      Path recoveredPath = unRaid(conf, srcPath, destPath, stripeLength, corruptOffset);
      Path corruptedPath = new Path(srcPath + "." + System.currentTimeMillis());
      FileSystem srcFs = srcPath.getFileSystem(conf);
      srcFs.rename(srcPath, corruptedPath);
      srcFs.rename(recoveredPath, srcPath);
    }
    return ReturnStatus.SUCCESS;
  }

  /**
   * Periodically checks to see which policies should be fired.
   */
  class TriggerMonitor implements Runnable {
    /**
     */
    public void run() {
      while (running) {
        try {
          doProcess();
        } catch (Exception e) {
          LOG.error(StringUtils.stringifyException(e));
        } finally {
          LOG.info("Trigger thread continuing to run...");
        }
      }
    }


    /**
     * Keep processing policies.
     * If the config file has changed, then reload config file and start afresh.
     */
    private void doProcess() throws IOException, InterruptedException {
      PolicyList.CompareByPath lexi = new PolicyList.CompareByPath();

      long prevExec = 0;
      DistRaid dr = null;
      while (running) {

        boolean reload = configMgr.reloadConfigsIfNecessary();
        while(!reload && now() < prevExec + configMgr.getPeriodicity()){
          Thread.sleep(SLEEP_TIME);
          reload = configMgr.reloadConfigsIfNecessary();
        }

        prevExec = now();
        
        // activate all categories
        Collection<PolicyList> all = configMgr.getAllPolicies();
        
        // sort all policies by reverse lexicographical order. This is needed
        // to make the nearest policy take precedence.
        PolicyList[] sorted = all.toArray(new PolicyList[all.size()]);
        Arrays.sort(sorted, lexi);

        if (!isRaidLocal) {
          dr = new DistRaid(conf);
        }
        // paths we have processed so far
        List<String> processed = new LinkedList<String>();
        
        for (PolicyList category : sorted) {
          for (PolicyInfo info: category.getAll()) {

            long modTimePeriod = 0;
            short srcReplication = 0;
            String str = info.getProperty("modTimePeriod");
            if (str != null) {
               modTimePeriod = Long.parseLong(info.getProperty("modTimePeriod")); 
            }
            str = info.getProperty("srcReplication");
            if (str != null) {
               srcReplication = Short.parseShort(info.getProperty("srcReplication")); 
            }

            LOG.info("Triggering Policy Filter " + info.getName() +
                     " " + info.getSrcPath());
            List<FileStatus> filteredPaths = null;
            try { 
              filteredPaths = selectFiles(conf, info.getSrcPath(), 
                                          getDestinationPath(conf, info),
                                          modTimePeriod,
                                          srcReplication,
                                          prevExec);
            } catch (Exception e) {
              LOG.info("Exception while invoking filter on policy " + info.getName() +
                       " srcPath " + info.getSrcPath() + 
                       " exception " + StringUtils.stringifyException(e));
              continue;
            }

            if (filteredPaths == null || filteredPaths.size() == 0) {
              LOG.info("No filtered paths for policy " + info.getName());
               continue;
            }

            // If any of the filtered path has already been accepted 
            // by a previous policy, then skip it.
            for (Iterator<FileStatus> iter = filteredPaths.iterator(); iter.hasNext();) {
              String fs = iter.next().getPath().toString() + "/";
              for (String p : processed) {
                if (p.startsWith(fs)) {
                  iter.remove();
                  break;
                }
              }
            }

            // Apply the action on accepted paths
            LOG.info("Triggering Policy Action " + info.getName());
            try {
            	if (isRaidLocal){
            	  doRaid(conf, info, filteredPaths);
            	}
            	else{
            	  //add paths for distributed raiding
            	  dr.addRaidPaths(info, filteredPaths);
            	}
            } catch (Exception e) {
              LOG.warn("Exception while invoking action on policy " + info.getName() +
                       " srcPath " + info.getSrcPath() + 
                       " exception " + StringUtils.stringifyException(e));
              continue;
            }

            // add these paths to processed paths
            for (Iterator<FileStatus> iter = filteredPaths.iterator(); iter.hasNext();) {
              String p = iter.next().getPath().toString() + "/";
              processed.add(p);
            }
          }
        }
        processed.clear(); // free up memory references before yielding

        //do the distributed raiding
        if (!isRaidLocal) {
          dr.doDistRaid();
        } 
      }
    }
  }

  /**
   * Returns the policy that matches the specified path.
   * The method below finds the first policy that matches an input path. Since different 
   * policies with different purposes and destinations might be associated with the same input
   * path, we should be skeptical about the places using the method and we should try to change
   * the code to avoid it.
   */
  private PolicyInfo findMatchingPolicy(Path inpath) throws IOException {
    PolicyList.CompareByPath lexi = new PolicyList.CompareByPath();
    Collection<PolicyList> all = configMgr.getAllPolicies();
        
    // sort all policies by reverse lexicographical order. This is needed
    // to make the nearest policy take precedence.
    PolicyList[] sorted = all.toArray(new PolicyList[all.size()]);
    Arrays.sort(sorted, lexi);

    // loop through all categories of policies.
    for (PolicyList category : sorted) {
      PolicyInfo first = category.getAll().iterator().next();
      if (first != null) {
        Path[] srcPaths = first.getSrcPathExpanded(); // input src paths unglobbed
        if (srcPaths == null) {
          continue;
        }

        for (Path src: srcPaths) {
          if (inpath.toString().startsWith(src.toString())) {
            // if the srcpath is a prefix of the specified path
            // we have a match! 
            return first;
          }
        }
      }
    }
    return null; // no matching policies
  }

 /**
  * Returns a list of pathnames that needs raiding.
  */
  private List<FileStatus> selectFiles(Configuration conf, Path p, String destPrefix,
                                       long modTimePeriod, short srcReplication, long now) throws IOException {

    List<FileStatus> returnSet = new LinkedList<FileStatus>();

    // expand destination prefix path
    Path destp = new Path(destPrefix.trim());
    FileSystem fs = FileSystem.get(destp.toUri(), conf);
    destp = destp.makeQualified(fs);

    fs = p.getFileSystem(conf);
    FileStatus[] gpaths = fs.globStatus(p);
    if (gpaths != null){
      for (FileStatus onepath: gpaths) {
        recurse(fs, conf, destp, onepath, returnSet, modTimePeriod, srcReplication, now);
      }
    }
    return returnSet;
  }

  /**
   * Pick files that need to be RAIDed.
   */
  private void recurse(FileSystem srcFs,
                       Configuration conf,
                       Path destPathPrefix,
                       FileStatus src,
                       List<FileStatus> accept,
                       long modTimePeriod, 
                       short srcReplication, 
                       long now) throws IOException {
    Path path = src.getPath();
    FileStatus[] files = null;
    try {
      files = srcFs.listStatus(path);
    } catch (java.io.FileNotFoundException e) {
      // ignore error because the file could have been deleted by an user
      LOG.info("FileNotFound " + path + " " + StringUtils.stringifyException(e));
    } catch (IOException e) {
      throw e;
    }

    // If the modTime of the raid file is later than the modtime of the
    // src file and the src file has not been modified
    // recently, then that file is a candidate for RAID.

    if (!src.isDir()) {      // a file

      // if the source file has fewer than or equal to 2 blocks, then no need to RAID
      long blockSize = src.getBlockSize();
      if (2 * blockSize >= src.getLen()) {
        return;
      }

      // If the replication factor of the source file is already below what is
      // expected by RAID, then ignore it.
      if (src.getReplication() < srcReplication) {
        return;
      }

      // check if destination path already exists. If it does and it's modification time
      // does not match the modTime of the source file, then recalculate RAID
      boolean add = false;
      try {
        Path outpath =  new Path(destPathPrefix, makeRelative(path));
        FileSystem outFs = outpath.getFileSystem(conf);
        FileStatus ostat = outFs.getFileStatus(outpath);
        if (ostat.getModificationTime() != src.getModificationTime() &&
            src.getModificationTime() + modTimePeriod < now) {
          add = true;
         }
      } catch (java.io.FileNotFoundException e) {
        add = true; // destination file does not exist
      }

      if (add) {
        accept.add(src);
      }
      return;

    } else if (files != null) {
      for (FileStatus one:files) {
        recurse(srcFs, conf, destPathPrefix, one, accept, modTimePeriod, srcReplication, now);
      }
    }
  }


  /**
   * RAID a list of files.
   */
  void doRaid(Configuration conf, PolicyInfo info, List<FileStatus> paths)
      throws IOException {
    int targetRepl = Integer.parseInt(info.getProperty("targetReplication"));
    int metaRepl = Integer.parseInt(info.getProperty("metaReplication"));
    int stripeLength = getStripeLength(conf, info);
    String destPrefix = getDestinationPath(conf, info);
    String simulate = info.getProperty("simulate");
    boolean doSimulate = simulate == null ? false : Boolean
        .parseBoolean(simulate);

    Statistics statistics = new Statistics();
    int count = 0;

    Path p = new Path(destPrefix.trim());
    FileSystem fs = FileSystem.get(p.toUri(), conf);
    p = p.makeQualified(fs);

    for (FileStatus s : paths) {
      doRaid(conf, s, p, statistics, null, doSimulate, targetRepl, metaRepl,
          stripeLength);
      if ((count+1) % 1000 == 0) {
        LOG.info("RAID statistics " + statistics.toString());
      }
      count++;
    }
    LOG.info("RAID statistics " + statistics.toString());
  }

  
  /**
   * RAID an individual file
   */

  static public void doRaid(Configuration conf, PolicyInfo info,
      FileStatus src, Statistics statistics, Reporter reporter) throws IOException {
    int targetRepl = Integer.parseInt(info.getProperty("targetReplication"));
    int metaRepl = Integer.parseInt(info.getProperty("metaReplication"));
    int stripeLength = getStripeLength(conf, info);
    String destPrefix = getDestinationPath(conf, info);
    String simulate = info.getProperty("simulate");
    boolean doSimulate = simulate == null ? false : Boolean
        .parseBoolean(simulate);

    int count = 0;

    Path p = new Path(destPrefix.trim());
    FileSystem fs = FileSystem.get(p.toUri(), conf);
    p = p.makeQualified(fs);

    doRaid(conf, src, p, statistics, reporter, doSimulate, targetRepl, metaRepl,
        stripeLength);
  }
  
  
  /**
   * RAID an individual file
   */
  static private void doRaid(Configuration conf, FileStatus stat, Path destPath,
                      Statistics statistics, Reporter reporter, boolean doSimulate,
                      int targetRepl, int metaRepl, int stripeLength) 
    throws IOException {
    Path p = stat.getPath();
    FileSystem srcFs = p.getFileSystem(conf);

    // extract block locations from File system
    BlockLocation[] locations = srcFs.getFileBlockLocations(stat, 0, stat.getLen());
    
    // if the file has fewer than 2 blocks, then nothing to do
    if (locations.length <= 2) {
      return;
    }

    // add up the raw disk space occupied by this file
    long diskSpace = 0;
    for (BlockLocation l: locations) {
      diskSpace += (l.getLength() * stat.getReplication());
    }
    statistics.numProcessedBlocks += locations.length;
    statistics.processedSize += diskSpace;

    // generate parity file
    generateParityFile(conf, stat, reporter, srcFs, destPath, locations, metaRepl, stripeLength);

    // reduce the replication factor of the source file
    if (!doSimulate) {
      if (srcFs.setReplication(p, (short)targetRepl) == false) {
        LOG.info("Error in reducing relication factor of file " + p + " to " + targetRepl);
        statistics.remainingSize += diskSpace;  // no change in disk space usage
        return;
      }
    }

    diskSpace = 0;
    for (BlockLocation l: locations) {
      diskSpace += (l.getLength() * targetRepl);
    }
    statistics.remainingSize += diskSpace;

    // the metafile will have this many number of blocks
    int numMeta = locations.length / stripeLength;
    if (locations.length % stripeLength != 0) {
      numMeta++;
    }

    // we create numMeta for every file. This metablock has metaRepl # replicas.
    // the last block of the metafile might not be completely filled up, but we
    // ignore that for now.
    statistics.numMetaBlocks += (numMeta * metaRepl);
    statistics.metaSize += (numMeta * metaRepl * stat.getBlockSize());
  }

  /**
   * Create the parity file.
   */
  static private void generateParityFile(Configuration conf, FileStatus stat,
                                  Reporter reporter,
                                  FileSystem inFs,
                                  Path destPathPrefix, BlockLocation[] locations,
                                  int metaRepl, int stripeLength) throws IOException {

    // two buffers for generating parity
    int bufSize = 5 * 1024 * 1024; // 5 MB
    byte[] bufs = new byte[bufSize];
    byte[] xor = new byte[bufSize];
    byte zero = 0;

    Path inpath = stat.getPath();
    long blockSize = stat.getBlockSize();
    long fileSize = stat.getLen();

    // create output tmp path
    Path outpath =  new Path(destPathPrefix, makeRelative(inpath));
    Path tmppath =  new Path(outpath + ".tmp");
    FileSystem outFs = outpath.getFileSystem(conf);

    // if the parity file is already upto-date, then nothing to do
    try {
      FileStatus stmp = outFs.getFileStatus(outpath);
      if (stmp.getModificationTime() >= stat.getModificationTime()) {
        LOG.info("Parity file for " + inpath + "(" + locations.length + ") is " + outpath +
                 " already upto-date. Nothing more to do.");
        return;
      }
    } catch (IOException e) {
      // ignore errors because the raid file might not exist yet.
      LOG.info("Parity file for " + inpath + "(" + locations.length + ") does not exist.");
    } 

    LOG.info("Parity file for " + inpath + "(" + locations.length + ") is " + outpath);
    FSDataOutputStream out = outFs.create(tmppath, 
                                          true, 
                                          conf.getInt("io.file.buffer.size", 64 * 1024), 
                                          (short)metaRepl, 
                                          blockSize);

    try {

      // loop once for every stripe length
      for (int startBlock = 0; startBlock < locations.length;) {

        // report progress to Map-reduce framework
        if (reporter != null) {
          reporter.progress();
        }
        int blocksLeft = locations.length - startBlock;
        int stripe = Math.min(stripeLength, blocksLeft);
        LOG.info(" startBlock " + startBlock + " stripe " + stripe);

        // open a new file descriptor for each block in this stripe.
        // make each fd point to the beginning of each block in this stripe.
        FSDataInputStream[] ins = new FSDataInputStream[stripe];
        for (int i = 0; i < stripe; i++) {
          ins[i] = inFs.open(inpath, bufSize);
          ins[i].seek(blockSize * (startBlock + i));
        }

        generateParity(ins,out,blockSize,bufs,xor);
        
        // close input file handles
        for (int i = 0; i < ins.length; i++) {
          ins[i].close();
        }

        // increment startBlock to point to the first block to be processed
        // in the next iteration
        startBlock += stripe;
      }
      out.close();
      out = null;

      // remove out-of-date parity file
      if (outFs.isFile(outpath) && !outFs.delete(outpath, false)) {
        String msg = "Unable to remove out-of-date parity file " + outpath;
        throw new IOException (msg);
      }
      // rename tmppath to the real parity filename
      if (!outFs.rename(tmppath, outpath)) {
        String msg = "Unable to rename tmp file " + tmppath + " to " + outpath;
        throw new IOException (msg);
      }
    } finally {
      // remove the tmp file if it still exists
      outFs.delete(tmppath, false);  
    }

    // set the modification time of the RAID file. This is done so that the modTime of the
    // RAID file reflects that contents of the source file that it has RAIDed. This should
    // also work for files that are being appended to. This is necessary because the time on
    // on the destination namenode may not be synchronised with the timestamp of the 
    // source namenode.
    outFs.setTimes(outpath, stat.getModificationTime(), -1);

    FileStatus outstat = outFs.getFileStatus(outpath);
    LOG.info("Source file " + inpath + " of size " + fileSize +
             " Parity file " + outpath + " of size " + outstat.getLen() +
             " src mtime " + stat.getModificationTime()  +
             " parity mtime " + outstat.getModificationTime());
  }

  private static int readInputUntilEnd(FSDataInputStream ins, byte[] bufs, int toRead) 
      throws IOException {

    int tread = 0;
    
    while (tread < toRead) {
      int read = ins.read(bufs, tread, toRead - tread);
      if (read == -1) {
        return tread;
      } else {
        tread += read;
      }
    }
    
    return tread;
  }
  
  private static void generateParity(FSDataInputStream[] ins, FSDataOutputStream fout, 
      long parityBlockSize, byte[] bufs, byte[] xor) throws IOException {
    
    int bufSize;
    if ((bufs == null) || (bufs.length == 0)){
      bufSize = 5 * 1024 * 1024; // 5 MB
      bufs = new byte[bufSize];
    } else {
      bufSize = bufs.length;
    }
    if ((xor == null) || (xor.length != bufs.length)){
      xor = new byte[bufSize];
    }

    int xorlen = 0;
      
    // this loop processes all good blocks in selected stripe
    long remaining = parityBlockSize;
    
    while (remaining > 0) {
      int toRead = (int)Math.min(remaining, bufSize);

      if (ins.length > 0) {
        xorlen = readInputUntilEnd(ins[0], xor, toRead);
      }

      // read all remaining blocks and xor them into the buffer
      for (int i = 1; i < ins.length; i++) {
        
        int actualRead = readInputUntilEnd(ins[i], bufs, toRead);
        
        int j;
        int xorlimit = (int) Math.min(xorlen,actualRead);
        for (j = 0; j < xorlimit; j++) {
          xor[j] ^= bufs[j];
        }
        if ( actualRead > xorlen ){
          for (; j < actualRead; j++) {
            xor[j] = bufs[j];
          }
          xorlen = actualRead;
        }
        
      }

      if (xorlen < toRead) {
        Arrays.fill(bufs, xorlen, toRead, (byte) 0);
      }
      
      // write this to the tmp file
      fout.write(xor, 0, toRead);
      remaining -= toRead;
    }
  
  }
  
  /**
   * Extract a good block from the parity block. This assumes that the corruption
   * is in the main file and the parity file is always good.
   */
  public static Path unRaid(Configuration conf, Path srcPath, Path destPathPrefix, 
                            int stripeLength, long corruptOffset) throws IOException {

    // extract block locations, size etc from source file
    Random rand = new Random();
    FileSystem srcFs = srcPath.getFileSystem(conf);
    FileStatus srcStat = srcFs.getFileStatus(srcPath);
    BlockLocation[] locations = srcFs.getFileBlockLocations(srcStat, 0, srcStat.getLen());
    long blockSize = srcStat.getBlockSize();
    long fileSize = srcStat.getLen();
    int totalBlocks = locations.length;

    // find the stripe number where the corrupted offset lies
    long snum = corruptOffset / (stripeLength * blockSize);
    long startOffset = snum * stripeLength * blockSize;
    long corruptBlockInStripe = (corruptOffset - startOffset)/blockSize;
    long corruptBlockSize = Math.min(blockSize, fileSize - startOffset);

    LOG.info("Start offset of relevent stripe = " + startOffset +
             " corruptBlockInStripe " + corruptBlockInStripe);

    // open file descriptors to read all good blocks of the file
    FSDataInputStream[] instmp = new FSDataInputStream[stripeLength];
    int  numLength = 0;
    for (int i = 0; i < stripeLength; i++) {
      if (i == corruptBlockInStripe) {
        continue;  // do not open corrupt block
      }
      if (startOffset + i * blockSize >= fileSize) {
        LOG.info("Stop offset of relevent stripe = " + 
                  startOffset + i * blockSize);
        break;
      }
      instmp[numLength] = srcFs.open(srcPath);
      instmp[numLength].seek(startOffset + i * blockSize);
      numLength++;
    }

    // create array of inputstream, allocate one extra slot for 
    // parity file. numLength could be smaller than stripeLength
    // if we are processing the last partial stripe on a file.
    numLength += 1;
    FSDataInputStream[] ins = new FSDataInputStream[numLength];
    for (int i = 0; i < numLength-1; i++) {
      ins[i] = instmp[i];
    }
    LOG.info("Decompose a total of " + numLength + " blocks.");

    // open and seek to the appropriate offset in parity file.
    Path parityFile =  new Path(destPathPrefix, makeRelative(srcPath));
    FileSystem parityFs = parityFile.getFileSystem(conf);
    LOG.info("Parity file for " + srcPath + " is " + parityFile);
    ins[numLength-1] = parityFs.open(parityFile);
    ins[numLength-1].seek(snum * blockSize);
    LOG.info("Parity file " + parityFile +
             " seeking to relevent block at offset " + 
             ins[numLength-1].getPos());

    // create a temporary filename in the source filesystem
    // do not overwrite an existing tmp file. Make it fail for now.
    // We need to generate a unique name for this tmp file later on.
    Path tmpFile = null;
    FSDataOutputStream fout = null;
    int retry = 5;
    try {
      tmpFile = new Path("/tmp/raid-recovery/" + rand.nextInt());
      fout = parityFs.create(tmpFile, false);
    } catch (IOException e) {
      if (retry-- <= 0) {
        LOG.info("Unable to create temporary file " + tmpFile +
                 " Aborting....");
        throw e; 
      }
      LOG.info("Unable to create temporary file " + tmpFile +
               "Retrying....");
    }
    LOG.info("Created recovered block file " + tmpFile);

    // buffers for generating parity bits
    int bufSize = 5 * 1024 * 1024; // 5 MB
    byte[] bufs = new byte[bufSize];
    byte[] xor = new byte[bufSize];
   
    generateParity(ins,fout,corruptBlockSize,bufs,xor);
    
    // close all files
    fout.close();
    for (int i = 0; i < ins.length; i++) {
      ins[i].close();
    }

    // Now, reopen the source file and the recovered block file
    // and copy all relevant data to new file
    Path recoveredPath =  new Path(srcPath + ".recovered");
    LOG.info("Creating recovered file " + recoveredPath);

    FSDataInputStream sin = srcFs.open(srcPath);
    FSDataOutputStream out = srcFs.create(recoveredPath, false,
                                          conf.getInt("io.file.buffer.size", 64 * 1024),
                                          srcStat.getReplication(),
                                          srcStat.getBlockSize());

    FSDataInputStream bin = parityFs.open(tmpFile);
    long recoveredSize = 0;

    // copy all the good blocks (upto the corruption)
    // from source file to output file
    long remaining = corruptOffset / blockSize * blockSize;
    while (remaining > 0) {
      int toRead = (int)Math.min(remaining, bufSize);
      sin.readFully(bufs, 0, toRead);
      out.write(bufs, 0, toRead);
      remaining -= toRead;
      recoveredSize += toRead;
    }
    LOG.info("Copied upto " + recoveredSize + " from src file. ");

    // copy recovered block to output file
    remaining = corruptBlockSize;
    while (recoveredSize < fileSize &&
           remaining > 0) {
      int toRead = (int)Math.min(remaining, bufSize);
      bin.readFully(bufs, 0, toRead);
      out.write(bufs, 0, toRead);
      remaining -= toRead;
      recoveredSize += toRead;
    }
    LOG.info("Copied upto " + recoveredSize + " from recovered-block file. ");

    // skip bad block in src file
    if (recoveredSize < fileSize) {
      sin.seek(sin.getPos() + corruptBlockSize); 
    }

    // copy remaining good data from src file to output file
    while (recoveredSize < fileSize) {
      int toRead = (int)Math.min(fileSize - recoveredSize, bufSize);
      sin.readFully(bufs, 0, toRead);
      out.write(bufs, 0, toRead);
      recoveredSize += toRead;
    }
    out.close();
    LOG.info("Completed writing " + recoveredSize + " bytes into " +
             recoveredPath);
              
    sin.close();
    bin.close();

    // delete the temporary block file that was created.
    parityFs.delete(tmpFile, false);
    LOG.info("Deleted temporary file " + tmpFile);

    // copy the meta information from source path to the newly created
    // recovered path
    copyMetaInformation(parityFs, srcStat, recoveredPath);

    return recoveredPath.makeQualified(srcFs);
  }

  /**
   * Periodically delete orphaned parity files.
   */
  class PurgeMonitor implements Runnable {
    /**
     */
    public void run() {
      while (running) {
        try {
          doPurge();
        } catch (Exception e) {
          LOG.error(StringUtils.stringifyException(e));
        } finally {
          LOG.info("Purge parity files thread continuing to run...");
        }
      }
    }

    /**
     * Delete orphaned files. The reason this is done by a separate thread 
     * is to not burden the TriggerMonitor with scanning the 
     * destination directories.
     */
    private void doPurge() throws IOException, InterruptedException {
      PolicyList.CompareByPath lexi = new PolicyList.CompareByPath();

      long prevExec = 0;
      while (running) {

        // The config may be reloaded by the TriggerMonitor. 
        // This thread uses whatever config is currently active.
        while(now() < prevExec + configMgr.getPeriodicity()){
          Thread.sleep(SLEEP_TIME);
        }

        prevExec = now();
        
        // fetch all categories
        Collection<PolicyList> all = configMgr.getAllPolicies();
        
        // sort all policies by reverse lexicographical order. This is 
        // needed to make the nearest policy take precedence.
        PolicyList[] sorted = all.toArray(new PolicyList[all.size()]);
        Arrays.sort(sorted, lexi);

        // paths we have processed so far
        Set<Path> processed = new HashSet<Path>();
        
        for (PolicyList category : sorted) {
          for (PolicyInfo info: category.getAll()) {

            try {
              // expand destination prefix path
              String destinationPrefix = getDestinationPath(conf, info);
              Path destp = new Path(destinationPrefix.trim());
              FileSystem destFs = FileSystem.get(destp.toUri(), conf);
              destp = destp.makeQualified(destFs);
              destinationPrefix = destp.toUri().getPath(); // strip host:port
              destp = new Path(destp, makeRelative(info.getSrcPath()));

              // if this destination path has already been processed as part
              // of another policy, then nothing more to do
              if (processed.contains(destp)) {
                LOG.info("Obsolete parity files for policy " + 
                        info.getName() + " has already been procesed.");
                continue;
              }
              LOG.info("Purging obsolete parity files for policy " + 
                        info.getName() + " " + destp);

              FileSystem srcFs = info.getSrcPath().getFileSystem(conf);
              FileStatus stat = destFs.getFileStatus(destp);
              if (stat != null) {
                recursePurge(srcFs, destFs, destinationPrefix, stat);
              }

              // this destination path has already been processed
              processed.add(destp);

            } catch (Exception e) {
              LOG.warn("Ignoring Exception while processing policy " + 
                       info.getName() + " " + 
                       StringUtils.stringifyException(e));
            }
          }
        }
      }
    }

    /**
     * The destPrefix is the absolute pathname of the destinationPath
     * specified in the policy (without the host:port)
     */ 
    private void recursePurge(FileSystem srcFs, FileSystem destFs,
                              String destPrefix, FileStatus dest) 
      throws IOException {

      Path destPath = dest.getPath(); // pathname, no host:port
      String destStr = destPath.toUri().getPath();
      LOG.debug("Checking " + destPath + " prefix " + destPrefix);

      // Verify the destPrefix is a prefix of the destPath
      if (!destStr.startsWith(destPrefix)) {
        LOG.error("Destination path " + destStr + " should have " + 
                  destPrefix + " as its prefix.");
        return;
      }
      String src = destStr.replaceFirst(destPrefix, "");
      
      // if the source path does not exist, then delete the 
      // destination path as well
      Path srcPath = new Path(src);
      if (!srcFs.exists(srcPath)) {
        boolean done = destFs.delete(destPath, false);
        if (done) {
          LOG.info("Purged path " + destPath );
        } else {
          LOG.info("Unable to purge path " + destPath );
        }
        return;
      }
      if (!dest.isDir()) {
        return;
      }
      FileStatus[] files = null;
      files = destFs.listStatus(destPath);
      if (files != null) {
        for (FileStatus one:files) {
          recursePurge(srcFs, destFs, destPrefix, one);
        }
      }
    } 
  }

  /**
   * If the config file has an entry for hdfs.raid.locations, then that overrides
   * destination path specified in the raid policy file
   */
  static private String getDestinationPath(Configuration conf, PolicyInfo info) {
    String locs = conf.get("hdfs.raid.locations"); 
    if (locs != null) {
      return locs;
    }
    locs = info.getDestinationPath();
    if (locs == null) {
      return DEFAULT_RAID_LOCATION;
    }
    return locs;
  }

  /**
   * If the config file has an entry for hdfs.raid.stripeLength, then use that
   * specified in the raid policy file
   */
  static private int getStripeLength(Configuration conf, PolicyInfo info)
    throws IOException {
    int len = conf.getInt("hdfs.raid.stripeLength", 0); 
    if (len != 0) {
      return len;
    }
    String str = info.getProperty("stripeLength");
    if (str == null) {
      String msg = "hdfs.raid.stripeLength is not defined." +
                   " Using a default " + DEFAULT_STRIPE_LENGTH;
      LOG.info(msg);
      return DEFAULT_STRIPE_LENGTH;
    }
    return Integer.parseInt(str);
  }

  /**
   * Copy the file owner, modtime, etc from srcPath to the recovered Path.
   * It is possiible that we might have to retrieve file persmissions,
   * quotas, etc too in future.
   */
  static private void copyMetaInformation(FileSystem fs, FileStatus stat, 
                                          Path recoveredPath) 
    throws IOException {
    fs.setOwner(recoveredPath, stat.getOwner(), stat.getGroup());
    fs.setPermission(recoveredPath, stat.getPermission());
    fs.setTimes(recoveredPath, stat.getModificationTime(), stat.getAccessTime());
  }

  /**
   * Returns current time.
   */
  static long now() {
    return System.currentTimeMillis();
  }

  /**                       
   * Make an absolute path relative by stripping the leading /
   */   
  static private Path makeRelative(Path path) {
    if (!path.isAbsolute()) {
      return path;
    }          
    String p = path.toUri().getPath();
    String relative = p.substring(1, p.length());
    return new Path(relative);
  } 

  private static void printUsage() {
    System.err.println("Usage: java RaidNode ");
  }

  private static StartupOption parseArguments(String args[]) {
    int argsLen = (args == null) ? 0 : args.length;
    StartupOption startOpt = StartupOption.REGULAR;
    for(int i=0; i < argsLen; i++) {
      String cmd = args[i]; // We have to parse command line args in future.
    }
    return startOpt;
  }


  /**
   * Convert command line options to configuration parameters
   */
  private static void setStartupOption(Configuration conf, StartupOption opt) {
    conf.set("fs.raidnode.startup", opt.toString());
  }

  /**
   * Create an instance of the RaidNode 
   */
  public static RaidNode createRaidNode(String argv[],
                                        Configuration conf) throws IOException {
    if (conf == null) {
      conf = new Configuration();
    }
    StartupOption startOpt = parseArguments(argv);
    if (startOpt == null) {
      printUsage();
      return null;
    }
    setStartupOption(conf, startOpt);
    RaidNode node = new RaidNode(conf);
    return node;
  }


  /**
   */
  public static void main(String argv[]) throws Exception {
    try {
      StringUtils.startupShutdownMessage(RaidNode.class, argv, LOG);
      RaidNode raid = createRaidNode(argv, null);
      if (raid != null) {
        raid.join();
      }
    } catch (Throwable e) {
      LOG.error(StringUtils.stringifyException(e));
      System.exit(-1);
    }
  }

  

}
