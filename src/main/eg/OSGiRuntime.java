package eg;

import org.osgi.framework.*;
import org.apache.felix.framework.Felix;
import org.apache.felix.framework.util.StringMap;
import org.apache.felix.main.AutoActivator;

import java.io.*;
import java.util.*;

public class OSGiRuntime {

	private final Felix runtime;

	public OSGiRuntime() throws Exception {
		Map config = new StringMap(false);
		config.put(Constants.FRAMEWORK_SYSTEMPACKAGES,
        "org.osgi.framework; version=1.3.0," +
				"org.osgi.service.packageadmin; version=1.2.0," +
				"org.osgi.service.startlevel; version=1.0.0," +
				"org.osgi.service.url; version=1.0.0");
		config.put(AutoActivator.AUTO_START_PROP + ".1",
				"file:bundle/org.apache.felix.shell-1.0.0.jar " +
				"file:bundle/org.apache.felix.shell.tui-1.0.0.jar");
		config.put("felix.embedded.execution", "true");

		runtime = new Felix(config);
		runtime.start();
	}

	public Bundle loadBundleFile(String location) throws Exception {
		File file = new File(location).getCanonicalFile();
		if(!file.exists()) throw new FileNotFoundException("Could not find bundle file at " + file);
		BundleContext context = runtime.getBundleContext();
		if(context != null) {
			return context.installBundle("file://" + file);
		} else {
			return null;
		}
	}

	public void stop() throws Exception {
		runtime.stop();
		runtime.waitForStop(60L * 1000L);
	}

}
