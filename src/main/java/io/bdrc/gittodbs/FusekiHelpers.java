package io.bdrc.gittodbs;

import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetAccessor;
import org.apache.jena.query.DatasetAccessorFactory;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionFactory;

import io.bdrc.gittodbs.TransferHelpers.DocType;

public class FusekiHelpers {
    
    public static String FusekiUrl = "http://localhost:13180/fuseki/bdrcrw/data";
    public static DatasetAccessor fu = null;
    public static String FusekiSparqlEndpoint = null;
    public static RDFConnection fuConn;
    public static int initialLoadBulkSize = 1000; // the number of triples above which a dataset load is triggered
    
    public static void init(String fusekiHost, String fusekiPort, String fusekiEndpoint) throws MalformedURLException {
        String baseUrl = "http://" + fusekiHost + ":" +  fusekiPort + "/fuseki/";
        FusekiUrl = baseUrl+fusekiEndpoint+"/data";
        FusekiSparqlEndpoint = baseUrl+fusekiEndpoint+"/query";
        TransferHelpers.logger.info("connecting to fuseki on "+FusekiUrl);
        fu = DatasetAccessorFactory.createHTTP(FusekiUrl);
        fuConn = RDFConnectionFactory.connect(FusekiSparqlEndpoint, FusekiSparqlEndpoint, FusekiUrl);
    }
    
    public static ResultSet selectSparql(String query) {
        QueryExecution qe = QueryExecutionFactory.sparqlService(FusekiSparqlEndpoint, query);
        return qe.execSelect();
    }
    
    public static synchronized String getLastRevision(DocType type) {
        Model m = getSyncModel();
        String typeStr = TransferHelpers.typeToStr.get(type);
        typeStr = typeStr.substring(0, 1).toUpperCase() + typeStr.substring(1);
        Resource res = m.getResource(TransferHelpers.ADMIN_PREFIX+"GitSyncInfo"+typeStr);
        Property p = m.getProperty(TransferHelpers.ADMIN_PREFIX+"hasLastRevision");
        Statement s = m.getProperty(res, p);
        if (s == null) return null;
        return s.getString();
    }
    
    private static volatile Model syncModel = ModelFactory.createDefaultModel();
    private static volatile boolean syncModelInitialized = false;
    
    public static synchronized final Model getSyncModel() {
        if (syncModelInitialized)
            return syncModel;
        initSyncModel();
        return syncModel;
    }
    
    public static synchronized final void initSyncModel() {
        syncModelInitialized = true;
        Model distantSyncModel = fu.getModel(TransferHelpers.ADMIN_PREFIX+"system");
        if (distantSyncModel != null) {
            syncModel.add(distantSyncModel);
        }
    }
    
    public static synchronized void setLastRevision(String revision, DocType type) {
        final Model m = getSyncModel();
        String typeStr = TransferHelpers.typeToStr.get(type);
        typeStr = typeStr.substring(0, 1).toUpperCase() + typeStr.substring(1);
        Resource res = m.getResource(TransferHelpers.ADMIN_PREFIX+"GitSyncInfo"+typeStr);
        Property p = m.getProperty(TransferHelpers.ADMIN_PREFIX+"hasLastRevision");
        Literal l = m.createLiteral(revision);
        Statement s = m.getProperty(res, p);
        if (s == null) {
            m.add(res, p, l);
        } else {
            s.changeObject(l);
        }
        try {
            transferModel(TransferHelpers.ADMIN_PREFIX+"system", m, false);
        } catch (TimeoutException e) {
            TransferHelpers.logger.warn("Timeout sending commit to fuseki (not fatal): "+revision, e);
        }
    }
    
    private static Model callFuseki(final String operation, final String graphName, final Model m) throws TimeoutException {
        System.out.println("callFuseki");
        Model res = null;
        final Callable<Model> task = new Callable<Model>() {
           public Model call() throws InterruptedException {
              switch (operation) {
              case "putModel":
                  //fu.putModel(graphName, m);
                  fuConn.put(graphName, m);
                  //fu.putModel(m);
                  return null;
              case "deleteModel":
                  fu.deleteModel(graphName);
                  return null;
              default:
                  return fu.getModel(graphName);
              }
           }
        };
        Future<Model> future = TransferHelpers.executor.submit(task);
        try {
           res = future.get(TransferHelpers.TRANSFER_TO, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            TransferHelpers.logger.error("interrupted during "+operation+" of "+graphName, e);
        } catch (ExecutionException e) {
            TransferHelpers.logger.error("execution error during "+operation+" of "+graphName+", this shouldn't happen, quitting...", e);
           System.exit(1);
        } finally {
           future.cancel(true); // this kills the transfer
        }
        return res;
    }

    static private Boolean isTransfering = false;
    static ArrayBlockingQueue<Dataset> queue = new ArrayBlockingQueue<>(1);
    
    private static void loadDatasetMutex(final Dataset ds) throws TimeoutException {
        //System.out.println("loadDatasetMutex");
        // here we want the following: one transfer at a time, but while the transfer occurs, we
        // can prepare the next one.
        
        // first thing, we add the dataset to the queue, waiting for the queue to get empty fist
        // this means that we're either at the beginning of the program or that a transfer is occuring
        try {
            queue.put(ds);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        // if a transfer is occuring, we return, as the thread will take care of consuming the entire queue
        if (isTransfering) {
            return;
        }
        
        final Callable<Void> task = new Callable<Void>() {
           public Void call() throws InterruptedException {
               // we consume the queue
               Dataset ds = queue.poll();
               while (ds != null) {
                   //System.out.println("starting transfer of one dataset");
                   fuConn.loadDataset(ds);
                   ds = queue.poll();
               }
               //System.out.println("finish transfer of all datasets");
               return null;
           }
        };
        Future<Void> future = TransferHelpers.executor.submit(task);
        try {
           future.get(TransferHelpers.TRANSFER_TO, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            TransferHelpers.logger.error("interrupted during datast load", e);
        } catch (ExecutionException e) {
            TransferHelpers.logger.error("execution error during dataset load, this shouldn't happen, quitting...", e);
            System.exit(1);
        } finally {
           isTransfering = false;
           System.out.println("finally: release locks");
           future.cancel(true); // this kills the transfer
        }
    }
    
    static Dataset currentDataset = null;
    static int triplesInDataset = 0;
    static void addToTransferBulk(final String graphName, final Model m) {
        if (currentDataset == null)
            currentDataset = DatasetFactory.createGeneral();
        currentDataset.addNamedModel(graphName, m);
        triplesInDataset += m.size();
        if (triplesInDataset > initialLoadBulkSize) {
            try {
                loadDatasetMutex(currentDataset);
                currentDataset = null;
                triplesInDataset = 0;
            } catch (TimeoutException e) {
                e.printStackTrace();
                return;
            }
        }
    }
    
    static void finishDatasetTransfers() {
        // if map is not empty, transfer the last one
        if (currentDataset != null) {
            try {
                loadDatasetMutex(currentDataset);
            } catch (TimeoutException e) {
                e.printStackTrace();
                return;
            }
        }
    }
     
    static void transferModel(final String graphName, final Model m, final boolean firstTransfer) throws TimeoutException {
        if (!firstTransfer) {
            callFuseki("putModel", graphName, m);
        } else {
            addToTransferBulk(graphName, m);
        }
    }
    
    static void deleteModel(String graphName) throws TimeoutException {
        callFuseki("deleteModel", graphName, null);
    }
    
    static Model getModel(String graphName) throws TimeoutException {
        return callFuseki("getModel", graphName, null);
    }
}
