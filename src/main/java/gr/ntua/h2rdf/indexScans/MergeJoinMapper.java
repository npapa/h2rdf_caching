package gr.ntua.h2rdf.indexScans;

import gr.ntua.h2rdf.LoadTriples.SortedBytesVLongWritable;
import gr.ntua.h2rdf.dpplanner.CachingExecutor;
import gr.ntua.h2rdf.inputFormat2.MultiTableInputFormat;
import gr.ntua.h2rdf.inputFormat2.MultiTableInputFormatBase;
import gr.ntua.h2rdf.inputFormat2.TableRecordGroupReader;
import gr.ntua.h2rdf.inputFormat2.TableRecordReader;
import gr.ntua.h2rdf.inputFormat2.TableRecordReader2;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableSplit;
import org.apache.hadoop.hbase.util.Base64;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.GzipCodec;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Mapper.Context;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.util.ReflectionUtils;

public class MergeJoinMapper extends Mapper<BytesWritable, BytesWritable, Bindings, BytesWritable> {
	public static double samplingRate = 0.05;
	public static int maxSamplePerVariable = 50000;
	public static int partitionBucketKeys = 10000;
	
	private boolean table;
	private boolean file;
	private Scan scan;
	private byte joinVar;
	private byte pattern;
	private Scan[] scans;

	@Override
	public void run(Context context) throws IOException, InterruptedException {
		int sampledBucketKeys = (int)Math.floor(samplingRate * partitionBucketKeys);
		int countStats=0;
		Map<Byte,Long> stats = new HashMap<Byte, Long>();
		Map<Byte,List<Long>> sampleForPartition = new HashMap<Byte, List<Long>>();
		Random rand = new Random();
		
		TableSplit split = (TableSplit)context.getInputSplit();
		int joinVar = split.getScan().getAttribute("joinVar")[0];
		
		List<TableRecordGroupReader> scanners = new ArrayList<TableRecordGroupReader>();
		MultiTableInputFormat in = new MultiTableInputFormat();
		Scan s = split.getScan();
		
		s.setStartRow(split.getStartRow());
		s.setStopRow(split.getEndRow());
		int numpats = context.getConfiguration().getInt("h2rdf.inputPatterns", 0);
		int groups = context.getConfiguration().getInt("h2rdf.inputGroups", 0);
		System.out.println("h2rdf.inputGroups     "+ groups);
		Map<Integer,TableRecordGroupReader> m = new HashMap<Integer, TableRecordGroupReader>();
		TableRecordGroupReader reader = new TableRecordGroupReader(Bytes.toString(split.getTableName()));
		//m.put(Bytes.toInt(s.getAttribute("group")), reader);
		reader.addScan(s);
		//TableRecordReader2 reader = new TableRecordReader2(s,split.getTableName());
		long startKey=0;
		if(reader.nextKeyValue()){
			scanners.add(reader);
			startKey = reader.getJvar();
		}
		//System.out.println("Start key: "+startKey);
		
		
		for (int i = 0; i < numpats; i++) {

			String s1 = context.getConfiguration().get("h2rdf.externalScans_"+i, null);
			if(s1==null)
				continue;

		    ByteArrayInputStream bis = new ByteArrayInputStream(Base64.decode(s1));
		    DataInputStream dis = new DataInputStream(bis);
		    Scan scan = new Scan();
		    scan.readFields(dis);

			TableRecordGroupReader val = m.get(Bytes.toInt(scan.getAttribute("group")));
			if(val==null){
				val = new TableRecordGroupReader(Bytes.toString(scan.getAttribute(Scan.SCAN_ATTRIBUTES_TABLE_NAME)));//Bytes.toString(split.getTableName()));
				val.addScan(scan);
				m.put(Bytes.toInt(scan.getAttribute("group")), val);
			}
			else{
				val.addScan(scan);
			}

			//TableRecordReader2 trr = new TableRecordReader2(scan, split.getTableName(), startKey);
			//if(trr.nextKeyValue()){
			//	scanners.add(trr);
			//}
		}
		for(Entry<Integer, TableRecordGroupReader> e :m.entrySet()){
			if(e.getValue().nextKeyValue()){
				scanners.add(e.getValue());
			}
		}
		numpats++;
		while(scanners.size()==groups){
			List<TableRecordGroupReader> nextScanners = new ArrayList<TableRecordGroupReader>();
			Iterator<TableRecordGroupReader> it = scanners.iterator();
			TableRecordGroupReader sf = it.next();
			long maxKey=sf.getJvar(), firstKey = sf.getJvar();
			//System.out.println();
			//System.out.print(firstKey+" ");
			int i=1;
			while(it.hasNext()){
				TableRecordGroupReader s1 = it.next();
				long key = s1.getJvar();
				//System.out.print(key+" ");
				if(key> maxKey){
					maxKey = key;
				}
				if(key == firstKey){
					i++;
				}
			}
			//System.out.println("maxKey:"+maxKey);
			it = scanners.iterator();
			if(i == groups){//key passed
				
				Bindings b = new Bindings();

				List<Bindings> lres = new ArrayList<Bindings>();
				int first=0;
				
				while(it.hasNext()){
					TableRecordGroupReader s1 = it.next();
					List<Bindings> bk = s1.getCurrentKey();
					if(s1.nextKeyValue())
						nextScanners.add(s1);
					else{
						//System.out.println("Closing");
						s1.close();
					}
					
					List<Bindings> templ = new ArrayList<Bindings>();
					templ.addAll(bk);
					//System.out.println(bk.map);
					if(first==0){
						first++;
						lres=templ;
						continue;
					}
					lres = Bindings.merge(lres,templ);
					//b.addAll(bk);
				}
				
				for(Bindings b1 : lres){
					//System.out.println("Output: "+b1.map);
					//b1.print(new HTable(HBaseConfiguration.create(), "YAGO_Index"));
					for(Entry<Byte, Set<Long>> e1 : b1.map.entrySet()){
						List<Long> samplel=null;
						if(countStats==0){
							stats.put(e1.getKey(), new Long(e1.getValue().size()));
							samplel = new ArrayList<Long>();
							sampleForPartition.put(e1.getKey(), samplel);
						}
						else{
							Long st = stats.get(e1.getKey());
							stats.put(e1.getKey(), new Long(st+e1.getValue().size()));
							samplel = sampleForPartition.get(e1.getKey());
						}
						//partition sampling
						int s1 = b1.map.get(e1.getKey()).size();
						double branchingfactor=1.0;
						for(Byte k :  b1.map.keySet()){
							if(!k.equals(e1.getKey())){
								branchingfactor = branchingfactor+b1.map.get(k).size();
							}
						}
						double cutoff = samplingRate*branchingfactor;
						if(samplel.size()<maxSamplePerVariable){
							for(Long tl : e1.getValue()){
								if(cutoff>=1){
									for (int j = 0; j < Math.round(cutoff); j++) {
										samplel.add(tl);
										if(samplel.size()>=maxSamplePerVariable){
											break;
										}
									}
								}
								else{
									for (int j = 0; j < branchingfactor; j++) {
										if(rand.nextDouble()<=samplingRate){
											samplel.add(tl);
											if(samplel.size()>=maxSamplePerVariable){
												break;
											}
										}
									}
								}
							}									
						}
						//partition sampling
					}
					countStats++;
					b1.writeOut(context,0, new Bindings());

					//context.write(b1, new BytesWritable(new byte[0]));
				}
				//context.write(b, new BytesWritable());
			}
			else{ //move all scanners to maxKey
				while(it.hasNext()){
					TableRecordGroupReader s1 = it.next();
					if(s1.getJvar() < maxKey){
						if(s1.goTo(maxKey))
							nextScanners.add(s1);
						else
							s1.close();
					}
					else{
						nextScanners.add(s1);
					}
				}
			}
			scanners = nextScanners;
			
		}
		
		for(TableRecordGroupReader r : scanners){
			r.close();
		}
		Counter c = context.getCounter("h2rdf", "sample");
		c.increment(countStats);
		
		for(Entry<Byte, Long> e : stats.entrySet()){
			c = context.getCounter("h2rdf", e.getKey().intValue()+"");
			c.increment(e.getValue());
			//System.out.println(e.getKey().intValue()+" "+e.getValue());
		}
		

		//find partitions
		String tid = context.getTaskAttemptID().getTaskID().toString();
		tid=tid.substring(tid.lastIndexOf('_'));
		FileSystem fs = FileSystem.get(context.getConfiguration());
		String[] str1 = context.getConfiguration().get("mapred.output.dir").split("/");
		System.out.println("resultPartitions/"+str1[str1.length-1]+"/part"+tid);
		Path path = new Path("resultPartitions/"+str1[str1.length-1]+"/part"+tid);
		if(fs.exists(path))
			fs.delete(path, true);
		FSDataOutputStream out = fs.create(path);
		
		for( Entry<Byte, List<Long>>  e : sampleForPartition.entrySet()){
			List<Long> l = e.getValue();
			Collections.sort(l);
			String part = "";
			
			int i = 0;
			while(i<l.size()){
				part+=l.get(i)+"_";
				i+=sampledBucketKeys;
			}
			if(l.size()>0)
				part+=l.get(l.size()-1)+"";
			out.writeUTF(e.getKey().intValue()+"_"+part);
		}
		out.flush();
		out.close();
		
		//context.getOutputCommitter().commitTask(context);
		//cleanup(context);
		/*while(reader.nextKeyValue()){
			//System.out.println(reader.getCurrentKey().map);
			context.write(reader.getCurrentKey(), NullWritable.get());
		}
		reader.close();*/
		
	}

	@Override
	protected void cleanup(Context context)
			throws IOException, InterruptedException {
		super.cleanup(context);
	}

	
	
}
