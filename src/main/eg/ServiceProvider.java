package eg;

import org.osgi.util.tracker.ServiceTracker;
import java.util.*;

public class ServiceProvider<M> {

	private final Class<M> cls;
	private final ServiceTracker tracker;

	public ServiceProvider(Class<M> cls, ServiceTracker tracker) {
		this.cls = cls;
		this.tracker = tracker;
	}

	public List<M> getServices() {
		Object[] services = tracker.getServices();
		if(services == null) return new ArrayList<M>(0);
		List toReturn = new ArrayList<M>(services.length);
		for(Object service : services) {
			if(!cls.isInstance(service)) {
				throw new ClassCastException("Cannot cast instance of " + service.getClass() + " to " + cls);
			}
			toReturn.add(service);
		}
		return toReturn;
	}

}
