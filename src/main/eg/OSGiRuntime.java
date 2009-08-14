package eg;

import org.osgi.framework.Constants;
import org.apache.felix.framework.Felix;
import org.apache.felix.main.AutoActivator;
import org.apache.felix.framework.util.StringMap;
import java.util.*;

public class OSGiRuntime {

	final Felix runtime;

	public OSGiRuntime() throws Exception {
		Map config = new StringMap(false);
		config.put(Constants.FRAMEWORK_SYSTEMPACKAGES,
        "org.osgi.framework; version=1.3.0," +
				"org.osgi.service.packageadmin; version=1.2.0," +
				"org.osgi.service.startlevel; version=1.0.0," +
				"org.osgi.service.url; version=1.0.0");
		configMap.put(AutoActivator.AUTO_START_PROP + ".1",
				"file:bundle/org.apache.felix.shell-1.0.0.jar " +
				"file:bundle/org.apache.felix.shell.tui-1.0.0.jar");
		configMap.put(BundleCache.CACHE_PROFILE_DIR_PROP, "cache");
		configMap.put("felix.embedded.execution", "true");

		List list = new ArrayList();
		list.add(new AutoActivator(configMap));
		list.add(new RunBundlesActivator());

		runtime = new Felix(configMap, list);
		runtime.start();
	}

	public void loadBundleFile(String location) {

	}

	public void stop() throws Exception {
		runtime.stopAndWait();
	}

}
