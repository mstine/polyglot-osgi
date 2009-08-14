package eg.impl;

import eg.*;
import org.osgi.framework.*;
import org.apache.felix.framework.util.StringMap;

import java.util.*;

public abstract class HelloWorldBundle {

	private ServiceRegistration registration;

	protected abstract HelloWorld getHelloWorld();

	public void start(BundleContext context) throws Exception {
		registration = context.registerService(HelloWorld.class.getName(), getHelloWorld(), null);
	}


	public void stop(BundleContext context) throws Exception {
		if(registration != null) {
			registration.unregister();
			registration = null;
		}
	}
	

}
