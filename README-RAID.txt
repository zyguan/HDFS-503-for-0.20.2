# Copyright 2008 The Apache Software Foundation Licensed under the
# Apache License, Version 2.0 (the "License"); you may not use this
# file except in compliance with the License.  You may obtain a copy
# of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless
# required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
# implied.  See the License for the specific language governing
# permissions and limitations under the License.

This package implements a Distributed Raid File System. It is used alongwith
an instance of the Hadoop Distributed File System (HDFS). It can be used to
provide better protection against data corruption. It can also be used to
reduce the total storage requirements of HDFS. 

Distributed Raid File System consists of two main software components. The first component 
is the RaidNode, a daemon that creates parity files from specified HDFS files. 
The second component "raidfs" is a software that is layered over a HDFS client and it 
intercepts all calls that an application makes to the HDFS client. If HDFS encounters
corrupted data while reading a file, the raidfs client detects it; it uses the
relevant parity blocks to recover the corrupted data (if possible) and returns
the data to the application. The application is completely transparent to the
fact that parity data was used to satisfy it's read request.

The primary use of this feature is to save disk space for HDFS files. 
HDFS typically stores data in triplicate.
The Distributed Raid File System can be configured in such a way that a set of
data blocks of a file are combined together to form one or more parity blocks.
This allows one to reduce the replication factor of a HDFS file from 3 to 2
while keeping the failure probabilty relatively same as before. This typically
results in saving 25% to 30% of storage space in a HDFS cluster. 

--------------------------------------------------------------------------------

BUILDING:

In HADOOP_HOME, run ant package to build Hadoop and its contrib packages.

--------------------------------------------------------------------------------

INSTALLING and CONFIGURING:

The entire code is packaged in the form of a single jar file hadoop-*-raid.jar.
To use HDFS Raid, you need to put the above mentioned jar file on
the CLASSPATH. The easiest way is to copy the hadoop-*-raid.jar
from HADOOP_HOME/build/contrib/raid to HADOOP_HOME/lib. Alternatively
you can modify HADOOP_CLASSPATH to include this jar, in conf/hadoop-env.sh.

There is a single configuration file named raid.xml that describes the HDFS 
path(s) that you want to raid. A sample of this file can be found in 
sc/contrib/raid/conf/raid.xml. Please edit the entries in this file to list the 
path(s) that you want to raid. Then, edit the hdfs-site.xml file for
your installation to include a reference to this raid.xml. You can add the
following to your hdfs-site.xml
        <property>
          <name>raid.config.file</name>
          <value>/mnt/hdfs/DFS/conf/raid.xml</value>
          <description>This is needed by the RaidNode </description>
        </property>

Please add an entry to your hdfs-site.xml to enable hdfs clients to use the
parity bits to recover corrupted data.

       <property>
         <name>fs.hdfs.impl</name>
         <value>org.apache.hadoop.raid.DistributedRaidFileSystem</value>
         <description>The FileSystem for hdfs: uris.</description>
       </property>


--------------------------------------------------------------------------------

OPTIONAL CONFIGIURATION:

The following properties can be set in hdfs-site.xml to further tune you configuration:

    Specifies the location where parity files are located. 
        <property>
          <name>hdfs.raid.locations</name>
          <value>hdfs://newdfs.data:8000/raid</value>
          <description>The location for parity files. If this is
          is not defined, then defaults to /raid. 
          </descrition>
        </property>

    Specify the parity stripe length 
        <property>
          <name>hdfs.raid.stripeLength</name>
          <value>10</value>
          <description>The number of blocks in a file to be combined into 
          a single raid parity block. The default value is 5. The lower
          the number the greater is the disk space you will save when you
          enable raid.
          </description>
        </property>

    Specify RaidNode to not use a map-reduce cluster for raiding files in parallel.
        <property>
          <name>fs.raidnode.local</name>
          <value>true</value>
          <description>If you do not want to use your map-reduce cluster to
          raid files in parallel, then specify "true". By default, this
          value is false, which means that the RaidNode uses the default
          map-reduce cluster to generate parity blocks.
          </description>
        </property>


    Specify the periodicy at which the RaidNode re-calculates (if necessary)
    the parity blocks
        <property>
          <name>raid.policy.rescan.interval</name>
          <value>5000</value>
          <description>Specify the periodicity in milliseconds after which
          all source paths are rescanned and parity blocks recomputed if 
          necessary. By default, this value is 1 hour.
          </description>
        </property>

    By default, the DistributedRaidFileSystem assumes that the underlying file
    system is the DistributedFileSystem. If you want to layer the DistributedRaidFileSystem
    over some other file system, then define a property named fs.raid.underlyingfs.impl
    that specifies the name of the underlying class. For example, if you want to layer
    The DistributedRaidFileSystem over an instance of the NewFileSystem, then
        <property>
          <name>fs.raid.underlyingfs.impl</name>
          <value>org.apche.hadoop.new.NewFileSystem</value>
          <description>Specify the filesystem that is layered immediately below the
          DistributedRaidFileSystem. By default, this value is DistributedFileSystem.
          </description>


--------------------------------------------------------------------------------

ADMINISTRATION:

The Distributed Raid File System  provides support for administration at runtime without
any downtime to cluster services.  It is possible to add/delete new paths to be raided without
interrupting any load on the cluster. If you change raid.xml, its contents will be
reload within seconds and the new contents will take effect immediately.

Designate one machine in your cluster to run the RaidNode software. You can run this daemon
on any machine irrespective of whether that machine is running any other hadoop daemon or not. 
You can start the RaidNode by running the following on the selected machine:
nohup $HADOOP_HOME/bin/hadoop org.apache.hadoop.raid.RaidNode >> /xxx/logs/hadoop-root-raidnode-hadoop.xxx.com.log &

Run fsckraid periodically (being developed as part of another JIRA). This valudates parity
blocsk of a file.

--------------------------------------------------------------------------------

IMPLEMENTATION:

The RaidNode periodically scans all the specified paths in the configuration
file. For each path, it recursively scans all files that have more than 2 blocks
and that has not been modified during the last few hours (default is 24 hours). 
It picks the specified number of blocks (as specified by the stripe size),
from the file, generates a parity block by combining them and
stores the results as another HDFS file in the specified destination 
directory. There is a one-to-one mapping between a HDFS
file and its parity file. The RaidNode also periodically finds parity files
that are orphaned and deletes them.

The Distributed Raid FileSystem is layered over a DistributedFileSystem
instance intercepts all calls that go into HDFS. HDFS throws a ChecksumException
or a BlocMissingException when a file read encounters bad data. The layered
Distributed Raid FileSystem catches these exceptions, locates the corresponding
parity file, extract the original data from the parity files and feeds the
extracted data back to the application in a completely transparent way.

The layered Distributed Raid FileSystem does not fix the data-loss that it
encounters while serving data. It merely make the application transparently
use the parity blocks to re-create the original data. A command line tool 
"fsckraid" is currently under development that will fix the corrupted files 
by extracting the data from the associated parity files. An adminstrator 
can run "fsckraid" manually as and when needed.
