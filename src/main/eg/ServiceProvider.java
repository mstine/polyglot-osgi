package eg;

import org.osgi.framework.*;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import java.util.*;

public class ServiceProvider<M> {

	final ServiceTracker tracker;
	private final Class<M> cls;

	public ServiceProvider(final BundleContext context, Class<M> toProvide) {
		this.cls = toProvide;
		this.tracker = new ServiceTracker(context, toProvide.getName(), new ServiceTrackerCustomizer() {
        public Object addingService(ServiceReference reference) { 
					return context.getService(reference); 
				}
        public void modifiedService(ServiceReference reference, Object service) {}
        public void removedService(ServiceReference reference, Object service) {}
		});
		this.tracker.open();
	}

	public List<M> getServices() {
		Object[] services = tracker.getServices();
		if(services == null) return new ArrayList<M>(0);
		final List<M> toReturn = new ArrayList<M>(services.length); 
		for(Object service : services) {
			if(!cls.isInstance(service)) {
				throw new ClassCastException("Cannot cast " + service.getClass() + " to " + cls);
			}
			toReturn.add((M)service); // Type erasure prevents this from throwing an exception
		}
		return toReturn;
	}

	public void close() {
		tracker.close();
	}

}
