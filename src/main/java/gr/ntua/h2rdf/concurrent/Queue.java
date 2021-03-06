/*******************************************************************************
 * Copyright (c) 2012 Nikos Papailiou. 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Nikos Papailiou - initial API and implementation
 ******************************************************************************/
package gr.ntua.h2rdf.concurrent;

import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.StringTokenizer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;

import gr.ntua.h2rdf.partialJoin.JoinPlaner;
import gr.ntua.h2rdf.queryProcessing.QueryPlanner;

import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.sparql.algebra.*;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class Queue extends SyncPrimitive {

    int first;
	String separator = "$query$" ;
    /**
     * Constructor of producer-consumer queue
     *
     * @param address
     * @param name
     */
    Queue(String address, String input, String output) {
        super(address);
        first=0;
        this.input = input;
        this.output = output;
        // Create ZK node name
        if (zk != null) {
            try {
                Stat s = zk.exists(input, false);
                if (s == null) {
                    zk.create(input, new byte[0], Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT);
                }
                s = zk.exists(output, false);
                if (s == null) {
                    zk.create(output, new byte[0], Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT);
                }
                s = zk.exists("/sync", false);
                if (s == null) {
                    zk.create("/sync", new byte[0], Ids.OPEN_ACL_UNSAFE,
                            CreateMode.PERSISTENT);
                }
            } catch (KeeperException e) {
                System.out
                        .println("Keeper exception when instantiating queue: "
                                + e.toString());
            } catch (InterruptedException e) {
                System.out.println("Interrupted exception");
            }
        }
    }

    /**
     * Add element to the queue.
     *
     * @param i
     * @return
     */

    boolean produce(String query, Watcher w) throws KeeperException, InterruptedException{
    	
        byte[] value;
        value = Bytes.toBytes(query);
        String f =zk.create(input + "/element", value, Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT_SEQUENTIAL);
        String filename = "/out/"+f.split("/")[2];
		//System.out.println(filename);
        //zk.exists(filename, w);
        return true;
    }


    /**
     * Remove first element from the queue.
     * @param id 
     *
     * @return
     * @throws KeeperException
     * @throws InterruptedException
     */
    int consume(int id) throws KeeperException, InterruptedException{
        Stat stat = null;
        // Get the first element available
        while (true) {
            synchronized (mutex) {
                /*List<String> sync = zk.getChildren("/sync", true);
                if(sync.size()==0){
                    System.out.println("Going to wait:");
                    mutex.wait();
                    //Thread.sleep(500);
                }
                else{*/
                    List<String> list = zk.getChildren(input, true);
                    System.out.println(first+" "+list.size());
                    if(list.size()==0){
                        System.out.println("Going to wait:");
                        mutex.wait();
                        //Thread.sleep(500);
                    }
                    else{
                    	/*Integer min = new Integer(list.get(0).substring(7));

                        String minval=list.get(0).substring(7);
                        for(String s : list){
                            Integer tempValue = new Integer(s.substring(7));
                            //System.out.println("Temporary value: " + tempValue);
                            if(tempValue < min){
                            	min = tempValue;
                            	minval = s.substring(7);
                            }
                        }*/
                    	Random g2 = new Random();
                		int i = g2.nextInt(list.size());
                		String minval=list.get(i).substring(7);
                        String filename=input+"/element" + minval;
                        System.out.println("Temporary file: " + filename);
                        byte[] b = zk.getData(filename,false, stat);
                        zk.delete(filename, 0);
                        if(b[0]!=(byte)0){
                        	byte type = b[0];
                        	byte[] input = new byte[b.length-1];
                        	
                        	for (int j = 0; j < input.length; j++) {
								input[j] = b[j+1];
							}
                        	System.out.println("Api request type: "+type);
    	                    //byte[] outfile = Bytes.toBytes("Hello from server!!");
                        	
                        	ClassLoader parentClassLoader = MyClassLoader.class.getClassLoader();
                        	MyClassLoader classLoader = new MyClassLoader(parentClassLoader);
                        	classLoader.clearAssertionStatus();
                        	String classNameToBeLoaded = "gr.ntua.h2rdf.apiCalls.ApiCalls";
                        	try {
                        		Class myClass = classLoader.loadClass(classNameToBeLoaded);
								
								Object whatInstance = myClass.newInstance();
								Method myMethod = myClass.getMethod("process",
					                    new Class[] { byte.class, byte[].class });
								byte[] outfile = (byte[]) myMethod.invoke(whatInstance,
					                    new Object[] { type, input });
								//byte[] outfile = ExampleApiCall.process(b);
								if(outfile!=null){
									zk.create(output + "/element" + minval, outfile,Ids.OPEN_ACL_UNSAFE,
	                                    CreateMode.PERSISTENT);
								}
	    	                    
							} catch (ClassNotFoundException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
        	                    byte[] outfile = Bytes.toBytes(e.getMessage());
        	                    zk.create(output + "/element" + minval, outfile,Ids.OPEN_ACL_UNSAFE,
                                        CreateMode.PERSISTENT);
							} catch (InstantiationException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
        	                    byte[] outfile = Bytes.toBytes(e.getMessage());
        	                    zk.create(output + "/element" + minval, outfile,Ids.OPEN_ACL_UNSAFE,
                                        CreateMode.PERSISTENT);
							} catch (IllegalAccessException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
        	                    byte[] outfile = Bytes.toBytes(e.getMessage());
        	                    zk.create(output + "/element" + minval, outfile,Ids.OPEN_ACL_UNSAFE,
                                        CreateMode.PERSISTENT);
							} catch (NoSuchMethodException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
        	                    byte[] outfile = Bytes.toBytes(e.getMessage());
        	                    zk.create(output + "/element" + minval, outfile,Ids.OPEN_ACL_UNSAFE,
                                        CreateMode.PERSISTENT);
							} catch (SecurityException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
        	                    byte[] outfile = Bytes.toBytes(e.getMessage());
        	                    zk.create(output + "/element" + minval, outfile,Ids.OPEN_ACL_UNSAFE,
                                        CreateMode.PERSISTENT);
							} catch (IllegalArgumentException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
        	                    byte[] outfile = Bytes.toBytes(e.getMessage());
        	                    zk.create(output + "/element" + minval, outfile,Ids.OPEN_ACL_UNSAFE,
                                        CreateMode.PERSISTENT);
							} catch (InvocationTargetException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
								System.out.println(e.getCause().toString());
        	                    byte[] outfile = Bytes.toBytes(e.getMessage());
        	                    zk.create(output + "/element" + minval, outfile,Ids.OPEN_ACL_UNSAFE,
                                        CreateMode.PERSISTENT);
							}
                        	
    	                    
    	                    return 1;
                        }
                        if(b.length>=2){
    	                    try {
    	                    	long startTimeReal = new Date().getTime();
    	                    	byte[] input = new byte[b.length-1];
    	                    	for (int j = 0; j < input.length; j++) {
    	                    		input[j]=b[j+1];
								}
    	                    	b=input;
	    	                    String data = Bytes.toString(b);
	    	                    String params = data.substring(0,data.indexOf(separator));
	    	                    StringTokenizer tok = new StringTokenizer(params);
	    	                    String table=tok.nextToken("|");
	    	                    //String algo=tok.nextToken("|");
	    	                    //String pool=tok.nextToken("|");
	    	                    //System.out.println("t "+table);
	    	                    String q = data.substring(data.indexOf(separator)+separator.length());
	    	                    //System.out.println(q);
        	                    Query query = QueryFactory.create(q) ;
        	                    


        	        	        // h2rdf new
        	            		Configuration hconf = HBaseConfiguration.create();
        	            		hconf.set("hbase.rpc.timeout", "3600000");
        	            		hconf.set("zookeeper.session.timeout", "3600000");
        	        			QueryPlanner.connectTable(table, hconf);
        	        	        Op opQuery = Algebra.compile(query) ;
        	        	        QueryPlanner planner = new QueryPlanner(query, table, "10");
        	        	        planner.executeQuery();

        	    	            long stopTime = new Date().getTime();
        	    	            System.out.println("Real time in ms: "+ (stopTime-startTimeReal));
        	                   /* // Generate algebra
        	                    Op opQuery = Algebra.compile(query) ;
        	                    System.out.println(opQuery) ;
        	                    //op = Algebra.optimize(op) ;
        	                    //System.out.println(op) ;
        	                    
        	                    MyOpVisitor v = new MyOpVisitor(minval, query);
        	                    //v.setQuery(query);
        	                    JoinPlaner.setTable(table, "4", "pool45");
        	                    JoinPlaner.setQuery(query);
        	                    OpWalker.walk(opQuery, v);
        	                    v.execute();*/
        	        	        
        	                    //byte[] outfile = Bytes.toBytes(JoinPlaner.getOutputFile());
        	                    byte[] outfile = null;
        	                    zk.create(output + "/element" + minval, outfile,Ids.OPEN_ACL_UNSAFE,
                                        CreateMode.PERSISTENT);
							} catch (Exception e) {

								e.printStackTrace();
        	                    byte[] outfile = Bytes.toBytes(e.getMessage());
        	                    zk.create(output + "/element" + minval, outfile,Ids.OPEN_ACL_UNSAFE,
                                        CreateMode.PERSISTENT);
								
								
							}
    	                    return 1;
                        }
                        else{
                        	return 0;
                        }
                    }
                	
            		
                //}
            }
        }
    }
}
