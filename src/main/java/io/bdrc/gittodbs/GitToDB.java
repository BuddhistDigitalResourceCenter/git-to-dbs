package io.bdrc.gittodbs;

import java.util.StringTokenizer;

import org.slf4j.LoggerFactory;

public class GitToDB {
	static String VERSION =  TransferHelpers.class.getPackage().getImplementationVersion();

	static String fusekiHost = "localhost";
	static String fusekiPort = "13180";
	static String fusekiName = "bdrcrw";
	static String gitDir = null;
	static int howMany = Integer.MAX_VALUE;
	
	static TransferHelpers.DocType docType = null;
	
	private static void printHelp() {
		System.err.print("java -jar GitToDB.jar OPTIONS\n"
		        + "Synchronize couchdb JSON-LD documents with fuseki\n"
		        + "Options:\n" 
		        + "-fuseki             - do transfer to Fuseki\n"
		        + "-fusekiHost <host>  - host fuseki is running on. Defaults to localhost\n"
		        + "-fusekiPort <port>  - port fuseki is running on. Defaults to 13180\n"
                + "-fusekiName <name>  - name of the fuseki endpoint. Defaults to 'bdrcrw'\n"
                + "-type <typeName>    - name of the type to transfer: person, item, place, work, topic, lineage, office, product, etext, corporation, etextcontent\n"
		        + "-gitDir <path>      - path to the git directory\n"
                + "-timeout <int>      - specify how seconds to wait for a doc transfer to complete. Defaults to 15 seconds\n"
                + "-n <int>            - specify how many resources to transfer; for testing. Default MaxInt\n"
                + "-bulkSz <int>       - specify how many triples to transfer in a bulk transaction. Default 50000\n"
                + "-progress           - enables progress output during transfer\n"
		        + "-debug              - enables DEBUG log level - mostly jena logging\n"
		        + "-trace              - enables TRACE log level - mostly jena logging\n"
		        + "-help               - prints this message and exits\n"
		        + "-version            - prints the version and exits\n"
		        + "\nset log level with the VM argument -Dorg.slf4j.simpleLogger.defaultLogLevel=XXX\n"
		        + "\nFusekiTransfer version: " + VERSION + "\n"
				);
	}

	public static void main(String[] args) {
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.equals("-fusekiHost")) {
				fusekiHost = (++i < args.length ? args[i] : null);
			} else if (arg.equals("-fusekiPort")) {
				fusekiPort = (++i < args.length ? args[i] : null);
			} else if (arg.equals("-fusekiName")) {
                fusekiName = (++i < args.length ? args[i] : null);
            } else if (arg.equals("-type")) {
                String typeName = (++i < args.length ? args[i] : null);
                docType  = TransferHelpers.DocType.getType(typeName);
            } else if (arg.equals("-gitDir")) {
                gitDir = (++i < args.length ? args[i] : null);
            } else if (arg.equals("-n")) {
                howMany = (++i < args.length ? Integer.parseInt(args[i]) : null);
            } else if (arg.equals("-bulkSz")) {
                FusekiHelpers.initialLoadBulkSize = (++i < args.length ? Integer.parseInt(args[i]) : null);
			} else if (arg.equals("-timeout")) {
				TransferHelpers.TRANSFER_TO = (++i < args.length ? Integer.parseInt(args[i]) : null);
            } else if (arg.equals("-progress")) {
                TransferHelpers.progress = true;
			} else if (arg.equals("-debug")) {
		        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "DEBUG");
		        TransferHelpers.logger = LoggerFactory.getLogger("fuseki-couchdb");
			} else if (arg.equals("-trace")) {
		        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE");
		        TransferHelpers.logger = LoggerFactory.getLogger("fuseki-couchdb");
			} else if (arg.equals("-help")) {
				printHelp();
				System.exit(0);
			} else if (arg.equals("-version")) {
				System.err.println("FusekiTransfer version: " + VERSION);

				if (TransferHelpers.logger.isDebugEnabled()) {
					System.err.println("Current java.library.path:");
					String property = System.getProperty("java.library.path");
					StringTokenizer parser = new StringTokenizer(property, ";");
					while (parser.hasMoreTokens()) {
						System.err.println(parser.nextToken());
					}
				}

				System.exit(0);
			}
		}

        if (gitDir == null || gitDir.isEmpty()) {
            TransferHelpers.logger.error("please specify the git directory");
            System.exit(1);
        }
        
        if (!gitDir.endsWith("/"))
            gitDir+='/';
		
        GitHelpers.init();
        
		try {
			TransferHelpers.init();
		} catch (Exception e) {
			TransferHelpers.logger.error("error in initialization", e);
			System.exit(1);
		}
        
        
        if (docType != null) {
            try {
                TransferHelpers.syncType(docType, howMany);
                TransferHelpers.closeConnections();
            } catch (Exception ex) {
                TransferHelpers.logger.error("error transfering" + docType, ex);
                System.exit(1);
            }
        } else {
            try {
                TransferHelpers.sync(howMany);
            } catch (Exception ex) {
                TransferHelpers.logger.error("error in complete transfer", ex);
                System.exit(1);
            }
        }

        TransferHelpers.logger.info("FusekiTranser done");
	}
}
