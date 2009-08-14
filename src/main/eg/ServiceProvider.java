package eg;

import org.osgi.util.tracker.ServiceTracker;
import java.util.*;

public class ServiceProvider {

	private final ServiceTracker tracker;

	public ServiceProvider(ServiceTracker tracker) {
		this.tracker = tracker;
	}

	public Object[] getServices() {
		Object[] services = tracker.getServices();
		if(services == null) return new Object[0];
		return services;
	}

}
