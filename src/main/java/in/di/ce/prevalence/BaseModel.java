package in.di.ce.prevalence;

import in.di.ce.Tracking;
import lombok.Getter;
import lombok.Setter;

import org.apache.log4j.Logger;
import org.prevayler.Prevayler;
import org.prevayler.PrevaylerFactory;
import org.prevayler.foundation.monitor.SimpleMonitor;
import org.prevayler.foundation.serialization.XStreamSerializer;

public class BaseModel {
	
public Logger logger = Logger.getLogger(getClass());
	
	@Setter private String prevalenceDirectory;
	@Getter private Prevayler prevayler;
	@Setter private PrevaylerFactory prevaylerFactory;
	
	public BaseModel(){}
	
	public void init(){
		prevaylerFactory = new PrevaylerFactory();
		prevaylerFactory.configurePrevalenceDirectory(prevalenceDirectory);
		prevaylerFactory.configurePrevalentSystem(new Tracking());
		prevaylerFactory.configureMonitor(new SimpleMonitor(System.err));
		/*
		 * food taster off
		 */
		prevaylerFactory.configureTransactionFiltering(false);
		
		/*
		 * XML format
		 */
		XStreamSerializer s = new XStreamSerializer();
		prevaylerFactory.configureJournalSerializer(s);
		prevaylerFactory.configureSnapshotSerializer(s);

		try{
		prevayler = prevaylerFactory.create(); 
		}catch(Exception e){
			logger.error("FAILED TO LOAD PREVALENT SYSTEM " + e);
			logger.error(e.getMessage());
			System.exit(1);
		}
	}
	
	public Tracking getModel(){
		return (Tracking) prevayler.prevalentSystem();
	}

//	public User loadUserByUserId(String id) {
//		return getModel().loadUserByUserId(id);
//	}
}
