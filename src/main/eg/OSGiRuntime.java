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
	private final List<ServiceProvider> providers = new ArrayList<ServiceProvider>(8);

	private static String buildPackages(String[]... packages) {
		StringBuilder builder = new StringBuilder();
		for(String[] pkgs : packages) {
			for(String pkg : pkgs) {
				builder.append(pkg);
				builder.append(',');
			}
		}
		builder.setLength(builder.length()-1); // remove trailing comma
		return builder.toString();
	}

	public OSGiRuntime(String... userPackages) throws Exception {
		String[] jvmPackages = new String[] {
			"javax.jms",
			"javax.mail",
			"javax.mail.internet",
			"javax.naming",
			"javax.script",
			"javax.swing",
			"javax.swing.border",
			"javax.swing.event",
			"javax.swing.filechooser",
			"javax.swing.plaf.basic",
			"javax.swing.table",
			"javax.swing.text",
			"javax.swing.tree",
			"javax.xml.namespace",
			"javax.xml.parsers",
			"javax.xml.transform",
			"javax.xml.transform.dom",
			"javax.xml.transform.stream",
			"org.w3c.dom",
			"org.w3c.dom.bootstrap",
			"org.w3c.dom.events",
			"org.w3c.dom.ls",
			"org.xml.sax",
			"org.xml.sax.ext",
			"org.xml.sax.helpers"
		};

		String[] osgiPackages = new String[] {
			"org.osgi.framework; version=1.3.0",
			"org.osgi.service.packageadmin; version=1.2.0",
			"org.osgi.service.startlevel; version=1.0.0",
			"org.osgi.service.url; version=1.0.0"
		};
	
		Map config = new StringMap(false);
		config.put(Constants.FRAMEWORK_SYSTEMPACKAGES,
			buildPackages(userPackages, jvmPackages, osgiPackages)
		);
		config.put("felix.embedded.execution", "true");

		runtime = new Felix(config);
		runtime.start();
	}

	public <M> ServiceProvider<M> trackService(Class<M> cls) {
		final BundleContext context = runtime.getBundleContext();
		if(context != null) {
			ServiceProvider<M> it = new ServiceProvider<M>(context, cls);
			providers.add(it);
			return it;
		} else {
			return null;
		}
	}

	public Bundle loadBundleFile(String location) throws Exception {
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
		for(ServiceProvider it : providers) {
			it.close();
		}
		runtime.stop();
		runtime.waitForStop(60L * 1000L);
	}

}
