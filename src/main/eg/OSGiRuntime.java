package eg;

import org.osgi.framework.*;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.apache.felix.framework.Felix;
import org.apache.felix.framework.util.StringMap;
import org.apache.felix.main.AutoActivator;

import java.io.*;
import java.util.*;

public class OSGiRuntime {

	private final Felix runtime;
	private final List<ServiceTracker> serviceTrackers = new ArrayList<ServiceTracker>(8);

	public OSGiRuntime() throws Exception {
		Map config = new StringMap(false);
		config.put(Constants.FRAMEWORK_SYSTEMPACKAGES,
				"javax.swing,"+
				"javax.swing.table,"+
				"javax.swing.tree,"+
				"org.w3c.dom," + 
				"org.w3c.dom.bootstrap," + 
				"org.w3c.dom.events," + 
				"org.w3c.dom.ls," + 
        "org.osgi.framework; version=1.3.0," +
				"org.osgi.service.packageadmin; version=1.2.0," +
				"org.osgi.service.startlevel; version=1.0.0," +
				"org.osgi.service.url; version=1.0.0");
		config.put("felix.embedded.execution", "true");

		runtime = new Felix(config);
		runtime.start();
	}

	public ServiceProvider trackService(String cls) {
		final BundleContext context = runtime.getBundleContext();
		if(context != null) {
			ServiceTrackerCustomizer handler = new ServiceTrackerCustomizer() {
				public Object addingService(ServiceReference reference) { return context.getService(reference); }

				public void modifiedService(ServiceReference reference, Object service) {}

				public void removedService(ServiceReference reference, Object service) {}
			};
			ServiceTracker it = new ServiceTracker(context, cls, handler);
			it.open();
			serviceTrackers.add(it);
			return new ServiceProvider(it);
		} else {
			return null;
		}
	}

	public Bundle loadBundleFile(String location) throws Exception {
		// BEGIN DEBUG CODE
		assert(runtime.loadClass("org.w3c.dom.NodeList") != null);
		// END DEBUG CODE

		File file = new File(location).getCanonicalFile();
		if(!file.exists()) throw new FileNotFoundException("Could not find bundle file at " + file);
		BundleContext context = runtime.getBundleContext();
		if(context != null) {
			Bundle toReturn = context.installBundle("file://" + file);
			toReturn.start();
			return toReturn;
		} else {
			return null;
		}
	}

	public void stop() throws Exception {
		for(ServiceTracker it : serviceTrackers) {
			it.close();
		}
		runtime.stop();
		runtime.waitForStop(60L * 1000L);
	}

}
