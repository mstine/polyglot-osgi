package eg.api.osgi.helpers;

import eg.api.*;
import org.osgi.framework.*;
import org.apache.felix.framework.util.StringMap;

import java.util.*;

public abstract class HelloWorldBundle implements BundleActivator {

	private static final String KEY = HelloWorld.class.getName();

	private ServiceRegistration registration;

	protected abstract HelloWorld getHelloWorld();

	public void start(BundleContext context) throws Exception {
		HelloWorld impl = getHelloWorld();
		System.out.println("Registering the implementation from " + impl.getClass());
		registration = context.registerService(KEY, impl, null);
	}


	public void stop(BundleContext context) throws Exception {
		if(registration != null) {
			registration.unregister();
			registration = null;
		}
	}
	

}
