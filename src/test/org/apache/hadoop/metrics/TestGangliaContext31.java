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

package org.apache.hadoop.metrics;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.net.DNS;

/**
 * This class creates starts up a Hadoop cluster and configures it to use the
 * GangliaContext31 metrics class. We then verify that it sends out valid
 * Ganglia data to a separate thread spawned to listen on the Ganglia socket.
 */
public class TestGangliaContext31 extends junit.framework.TestCase {

  private static final Log LOG = LogFactory.getLog(TestGangliaContext31.class);

  /**
   * This class is a runnable that will listen for Ganglia connections.
   */
  class GangliaSocketListener implements Runnable {

    private boolean isConfigured = false;
    private boolean hasData = false;
    private byte[] byteData;
    private int port;

    public void run() {
      DatagramSocket s;
      try {
        s = new DatagramSocket();
        setPort(s.getLocalPort());
        setConfigured(true);
      } catch (IOException e) {
        LOG.warn(e);
        synchronized(this) {
          this.notify();
        }
        return;
      }

      byte [] b = new byte[8192];
      DatagramPacket info = new DatagramPacket(b, b.length);

      synchronized(this) {
        this.notify();
      }
      try {
        s.receive(info);
      } catch (IOException e) {
        LOG.warn(e);
        synchronized(this) {
          this.notify();
        }
        return;
      }
      LOG.info("Got a new packet, length " + info.getLength());
      int bytesRead = info.getLength();
      if (bytesRead > 0)
        setHasData(true);

      byteData = new byte[info.getLength()];
      System.arraycopy(info.getData(), 0, byteData, 0, bytesRead);
      synchronized(this) {
        this.notify();
      }
    }

    public void setConfigured(boolean isConfigured) {
      this.isConfigured = isConfigured;
    }

    public boolean getConfigured() {
      return isConfigured;
    }

    public void setHasData(boolean hasData) {
      this.hasData = hasData;
    }

    public boolean getHasData() {
      return hasData;
    }

    public byte[] getBytes() {
      return byteData;
    }

    public void setPort(int port) {
      this.port = port;
    }

    public int getPort() {
      return port;
    }

  }

  public String getHostname(Configuration conf) {
    String hostName;
    if (conf.get("slave.host.name") != null) {
      hostName = conf.get("slave.host.name");
    } else {
      try {
        hostName = DNS.getDefaultHost(
          conf.get("dfs.datanode.dns.interface","default"),
          conf.get("dfs.datanode.dns.nameserver","default"));
      } catch (UnknownHostException uhe) {
        LOG.error(uhe);
      hostName = "UNKNOWN.example.com";
      }
    }
    return hostName;
  }

  public void testGanglia31Metrics() throws IOException {
    Configuration conf = new Configuration();

    String hostName = getHostname(conf);

    GangliaSocketListener listener = new GangliaSocketListener();
    Thread listenerThread = new Thread(listener);
    listenerThread.start();
    try {
      synchronized(listener) {
        listener.wait();
      }
    } catch (InterruptedException e) {
      LOG.warn(e);
    }

    assertTrue("Could not configure the socket listener for Ganglia", listener.getConfigured());

    LOG.info("Listening to port " + listener.getPort());

    ContextFactory contextFactory = ContextFactory.getFactory();
    contextFactory.setAttribute("dfs.class", "org.apache.hadoop.metrics.ganglia.GangliaContext31");
    contextFactory.setAttribute("dfs.period", "1");
    contextFactory.setAttribute("dfs.servers", "localhost:" + listener.getPort());

    MiniDFSCluster cluster = new MiniDFSCluster(conf, 1, true, null);
    try {
      if (!listener.getHasData())
        synchronized(listener) {
          listener.wait(5*1000); // Wait at most 5 seconds for Ganglia data
      }
    } catch (InterruptedException e) {
      LOG.warn(e);
    }
    assertTrue("Did not recieve Ganglia data", listener.getHasData());
    cluster.shutdown();

    byte [] hostNameBytes = hostName.getBytes();

    byte [] xdrBytes = listener.getBytes();

    // Try to make sure that the received bytes from Ganglia has the correct hostname for this host
    boolean hasHostname = false;
    LOG.info("Checking to make sure that the Ganglia data contains host " + hostName);
    for (int i=0; i<xdrBytes.length-hostNameBytes.length; i++) {
      hasHostname = true;
      for (int j=0; j<hostNameBytes.length; j++) {
        if (xdrBytes[i+j] != hostNameBytes[j]) {
          hasHostname=false;
          break;
        }
      }
      if (hasHostname)
        break;
    }
    assertTrue("Did not correctly resolve hostname in Ganglia", hasHostname);

  }

}
